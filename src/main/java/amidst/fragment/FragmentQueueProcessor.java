package amidst.fragment;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledByAny;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.Fragment.State;
import amidst.fragment.layer.LayerManager;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;
import amidst.threading.TaskCancellation;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * The loader tick owns dispatch, state transitions and recycling. Worker threads only write a
 * reserved fragment's data and post completion. In particular, a fragment is reserved BEFORE
 * executor submission and never reused before its completion is consumed, even when the EDT removes
 * it from the graph meanwhile.
 */
@NotThreadSafe
public class FragmentQueueProcessor {
    private final ConcurrentLinkedQueue<Fragment> loadingQueue;
    private final ConcurrentLinkedQueue<Fragment> recycleQueue;
    private final ConcurrentLinkedQueue<Load> completions = new ConcurrentLinkedQueue<>();
    private final Set<Fragment> pendingRecycle = new LinkedHashSet<>();
    private final FragmentCache cache;
    private final LayerManager layerManager;
    private final ThreadPoolExecutor fragWorkers;
    private final ThreadPoolExecutor overlayWorkers;
    private final Setting<Dimension> dimensionSetting;
    private final Supplier<FragmentViewport> viewport;
    private final int maxConcurrentLoads;
    private final boolean separateTerrainAndOverlays;
    private volatile int activeLoadCount;
    private int activeOverlayLoadCount;
    private volatile boolean disposed;
    private Dimension lastDimension;
    private final boolean profileScheduling = Boolean.getBoolean("amidst.gtnh.profileQueries");
    private long scheduledLoads, completedLoads, cancelledLoads, recycledFragments, failedLoads;
    private long lastProfileNanos, lastProfileWork = -1;

    @CalledByAny
    public FragmentQueueProcessor(
            ConcurrentLinkedQueue<Fragment> loadingQueue,
            ConcurrentLinkedQueue<Fragment> recycleQueue,
            FragmentCache cache,
            LayerManager layerManager,
            ThreadPoolExecutor fragWorkers,
            Setting<Dimension> dimensionSetting,
            Supplier<FragmentViewport> viewport,
            int maxConcurrentLoads) {
        this(
                loadingQueue,
                recycleQueue,
                cache,
                layerManager,
                fragWorkers,
                dimensionSetting,
                viewport,
                maxConcurrentLoads,
                false);
    }

    public FragmentQueueProcessor(
            ConcurrentLinkedQueue<Fragment> loadingQueue,
            ConcurrentLinkedQueue<Fragment> recycleQueue,
            FragmentCache cache,
            LayerManager layerManager,
            ThreadPoolExecutor fragWorkers,
            Setting<Dimension> dimensionSetting,
            Supplier<FragmentViewport> viewport,
            int maxConcurrentLoads,
            boolean separateTerrainAndOverlays) {
        this(
                loadingQueue,
                recycleQueue,
                cache,
                layerManager,
                fragWorkers,
                dimensionSetting,
                viewport,
                maxConcurrentLoads,
                separateTerrainAndOverlays,
                null);
    }

    FragmentQueueProcessor(
            ConcurrentLinkedQueue<Fragment> loadingQueue,
            ConcurrentLinkedQueue<Fragment> recycleQueue,
            FragmentCache cache,
            LayerManager layerManager,
            ThreadPoolExecutor fragWorkers,
            Setting<Dimension> dimensionSetting,
            Supplier<FragmentViewport> viewport,
            int maxConcurrentLoads,
            boolean separateTerrainAndOverlays,
            ThreadPoolExecutor overlayExecutor) {
        this.loadingQueue = loadingQueue;
        this.recycleQueue = recycleQueue;
        this.cache = cache;
        this.layerManager = layerManager;
        this.dimensionSetting = dimensionSetting;
        this.fragWorkers = fragWorkers;
        this.viewport = viewport;
        this.maxConcurrentLoads =
                Math.max(1, Math.min(maxConcurrentLoads, fragWorkers.getMaximumPoolSize()));
        this.separateTerrainAndOverlays = separateTerrainAndOverlays;
        overlayWorkers =
                !separateTerrainAndOverlays
                        ? null
                        : overlayExecutor != null
                                ? overlayExecutor
                                : new ThreadPoolExecutor(
                                        Math.min(4, this.maxConcurrentLoads),
                                        Math.min(4, this.maxConcurrentLoads),
                                        5L,
                                        java.util.concurrent.TimeUnit.SECONDS,
                                        new java.util.concurrent.LinkedBlockingQueue<>(),
                                        task -> {
                                            Thread thread =
                                                    new Thread(task, "Amidst RWG2 POI requests");
                                            thread.setDaemon(true);
                                            return thread;
                                        });
        if (overlayWorkers != null && overlayExecutor == null)
            overlayWorkers.allowCoreThreadTimeOut(true);
    }

    /** One nonblocking tick: never wait for a whole batch or hide a backlog in the executor. */
    @CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
    public synchronized void processQueues() {
        if (disposed) {
            return;
        }
        processCompletions();
        processRecycleQueue();
        Dimension dimension = dimensionSetting.get();
        if (activeLoadCount == 0) {
            // Layer declarations and revision numbers are shared by loaders.
            // Only update them between active batches, never during a layer load.
            lastDimension = dimension;
            if (layerManager.updateAll(dimension)) {
                cache.reloadAll();
            }
        }
        if (dimension != lastDimension) {
            return; // Old-dimension tasks exit at their next cancellation checkpoint.
        }
        if (activeLoadCount > 0 && layerManager.hasPendingUpdates(dimension)) return;
        while (dimension == dimensionSetting.get()) {
            Fragment fragment = takeNextFragment();
            if (fragment == null) {
                break;
            }
            Load load = new Load(fragment, dimension);
            fragment.setState(State.LOADING);
            activeLoadCount++;
            if (load.overlay) activeOverlayLoadCount++;
            scheduledLoads++;
            try {
                (load.overlay && dimension == Dimension.OVERWORLD ? overlayWorkers : fragWorkers)
                        .execute(load);
            } catch (RejectedExecutionException e) {
                activeLoadCount--;
                if (load.overlay) activeOverlayLoadCount--;
                fragment.setState(load.previousState);
                if (load.biomeReloadRequested) {
                    fragment.requestBiomeReload();
                }
                loadingQueue.offer(fragment);
                throw e;
            }
        }
        logSchedulingIfNeeded();
    }

    private void logSchedulingIfNeeded() {
        if (!profileScheduling) return;
        long now = System.nanoTime();
        long work =
                scheduledLoads
                        + completedLoads
                        + recycledFragments
                        + cache.cacheHits()
                        + cache.evictions();
        if (work != lastProfileWork
                && (lastProfileNanos == 0 || now - lastProfileNanos >= 1_000_000_000L)) {
            AmidstLogger.info(
                    "GTNH scheduling: active={}/{} pending={} scheduled={} cancelled={} retired={}"
                            + " failed={} retained={} cacheHits={} evicted={} order=frontier",
                    activeLoadCount,
                    maxConcurrentLoads,
                    loadingQueue.size(),
                    scheduledLoads,
                    cancelledLoads,
                    recycledFragments,
                    failedLoads,
                    cache.retainedSize(),
                    cache.cacheHits(),
                    cache.evictions());
            lastProfileNanos = now;
            lastProfileWork = work;
        }
    }

    private Fragment takeNextFragment() {
        FragmentViewport currentViewport = viewport.get();
        List<Fragment> candidates = new ArrayList<>();
        int overlayLimit = overlayLimit(dimensionSetting.get());
        for (Fragment candidate : loadingQueue) {
            State state = candidate.getState();
            if (candidate.isRecyclingRequested()
                    || state == State.UNINITIALIZED
                    || state == State.LOADING) {
                loadingQueue.remove(candidate);
            } else if (isOverlayLoad(candidate, dimensionSetting.get())
                    ? activeOverlayLoadCount < overlayLimit
                    : activeLoadCount - activeOverlayLoadCount < maxConcurrentLoads) {
                candidates.add(candidate);
            }
        }
        FragmentFrontier frontier =
                new FragmentFrontier(
                        currentViewport, candidates, cache.frontierMarks(dimensionSetting.get()));
        Fragment best = candidates.stream().min(frontier::compare).orElse(null);
        if (best != null) {
            removeFromLoadingQueue(best);
        }
        return best;
    }

    private int overlayLimit(Dimension dimension) {
        if (dimension != Dimension.OVERWORLD) return Math.min(4, maxConcurrentLoads);
        if (activeLoadCount > activeOverlayLoadCount) return 1;
        for (Fragment pending : loadingQueue)
            if (!pending.isRecyclingRequested()
                    && pending.getState() != State.UNINITIALIZED
                    && pending.getState() != State.LOADING
                    && layerManager.requiresTerrainLoad(pending)) return 1;
        return Math.min(4, maxConcurrentLoads);
    }

    private boolean isOverlayLoad(Fragment fragment, Dimension dimension) {
        return separateTerrainAndOverlays
                && (dimension != Dimension.OVERWORLD
                        || !layerManager.requiresTerrainLoad(fragment));
    }

    private void processCompletions() {
        Load load;
        while ((load = completions.poll()) != null) {
            activeLoadCount--;
            if (load.overlay) activeOverlayLoadCount--;
            completedLoads++;
            if (load.cancelled) cancelledLoads++;
            else if (!load.succeeded) failedLoads++;
            boolean stale = load.epoch != layerManager.getInvalidationEpoch();
            if (load.succeeded
                    && !stale
                    && load.terrainStage
                    && load.dimension == dimensionSetting.get())
                cache.completeTerrainStage(load.fragment);
            else
                cache.completeLoad(
                        load.fragment,
                        load.dimension,
                        load.succeeded && !stale,
                        load.cancelled || stale,
                        dimensionSetting.get());
        }
    }

    private void processRecycleQueue() {
        Fragment fragment;
        while ((fragment = recycleQueue.poll()) != null) {
            pendingRecycle.add(fragment);
        }
        for (Iterator<Fragment> iterator = pendingRecycle.iterator(); iterator.hasNext(); ) {
            fragment = iterator.next();
            if (cache.retire(fragment)) {
                iterator.remove();
                recycledFragments++;
            }
        }
    }

    private void removeFromLoadingQueue(Fragment fragment) {
        while (loadingQueue.remove(fragment)) {
            // Reload notifications may have queued the same reserved fragment more than once.
        }
    }

    @CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
    public synchronized void requestBiomeReloadForChunks(int[] chunkXs, int[] chunkZs) {
        if (!disposed) cache.reloadBiomeChunks(chunkXs, chunkZs);
    }

    public synchronized void requestOverworldReload(
            java.util.Set<amidst.mojangapi.world.coordinates.CoordinatesInWorld> affected) {
        if (!disposed) cache.reloadOverworldTiles(affected);
    }

    @CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
    public synchronized void requestBiomeReloadAllUsed() {
        if (!disposed) cache.reloadBiomesAllUsed();
    }

    public int getActiveLoadCount() {
        return activeLoadCount;
    }

    /** Synchronize with the short dispatch tick before the manager clears shared queues. */
    public synchronized void dispose() {
        disposed = true;
        if (overlayWorkers != null) overlayWorkers.shutdown();
    }

    private final class Load implements Runnable {
        private final Fragment fragment;
        private final Dimension dimension;
        private final State previousState;
        private final boolean biomeReloadRequested;
        private final boolean overlay;
        private final long epoch;
        private boolean succeeded;
        private boolean cancelled;
        private boolean terrainStage;

        private Load(Fragment fragment, Dimension dimension) {
            this.fragment = fragment;
            this.dimension = dimension;
            this.previousState = fragment.getState();
            this.overlay = isOverlayLoad(fragment, dimension);
            this.epoch = layerManager.getInvalidationEpoch();
            this.biomeReloadRequested = fragment.getAndClearBiomeReloadRequested();
            fragment.prepareForLoad(dimension);
            layerManager.prepareFragment(fragment, biomeReloadRequested);
        }

        @Override
        public void run() {
            try (var selection =
                    amidst.gtnh.structure.StructureQuerySelection.open(
                            amidst.gtnh.structure.StructureQuerySelection.visibleTypes(
                                    layerManager.getDeclarations()))) {
                TaskCancellation.run(
                        () ->
                                disposed
                                        || fragment.isRecyclingRequested()
                                        || dimension != dimensionSetting.get()
                                        || epoch != layerManager.getInvalidationEpoch()
                                        || layerManager.hasPendingUpdates(dimension),
                        () -> {
                            if (layerManager.loadTerrainStage(dimension, fragment)) {
                                terrainStage = true;
                            } else if (previousState == State.LOADED
                                    && dimension == fragment.getLoadedDimension()) {
                                if (biomeReloadRequested) {
                                    layerManager.reloadBiomeLayers(dimension, fragment);
                                } else {
                                    layerManager.reloadInvalidated(dimension, fragment);
                                }
                            } else {
                                layerManager.loadAll(dimension, fragment);
                            }
                            // All layers finished, even if the view moved before the final check.
                            succeeded = true;
                        });
            } catch (CancellationException e) {
                cancelled = true;
            } catch (RuntimeException e) {
                AmidstLogger.error(
                        e, "Unable to load fragment at {} in {}", fragment.getCorner(), dimension);
            } finally {
                completions.offer(this);
            }
        }
    }
}
