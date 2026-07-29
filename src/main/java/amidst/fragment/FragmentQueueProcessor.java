package amidst.fragment;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.LockSupport;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledByAny;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.Fragment.State;
import amidst.fragment.layer.LayerManager;
import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;

@NotThreadSafe
public class FragmentQueueProcessor {
	private final ConcurrentLinkedQueue<Fragment> availableQueue;
	private final ConcurrentLinkedQueue<Fragment> loadingQueue;
	private final ConcurrentLinkedQueue<Fragment> recycleQueue;
	private final FragmentCache cache;
	private final LayerManager layerManager;
	private final ThreadPoolExecutor fragWorkers;
	private final Setting<Dimension> dimensionSetting;
	private volatile long dimensionRevision;
	private Dimension lastDimension;

	@CalledByAny
	public FragmentQueueProcessor(
			ConcurrentLinkedQueue<Fragment> availableQueue,
			ConcurrentLinkedQueue<Fragment> loadingQueue,
			ConcurrentLinkedQueue<Fragment> recycleQueue,
			FragmentCache cache,
			LayerManager layerManager,
			ThreadPoolExecutor fragWorkers,
			Setting<Dimension> dimensionSetting) {
		this.availableQueue = availableQueue;
		this.loadingQueue = loadingQueue;
		this.recycleQueue = recycleQueue;
		this.cache = cache;
		this.layerManager = layerManager;
		this.dimensionSetting = dimensionSetting;
		this.fragWorkers = fragWorkers;
	}
	
	private static final int PARK_MILLIS = 20;
	
	/**
	 * It is important that the dimension setting is the same while a fragment
	 * is loaded by different fragment loaders. This is why the dimension
	 * setting is read by the fragment loader thread.
	 */
	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void processQueues() {
		final Thread flThread = Thread.currentThread(); // the fragment loader thread
		Dimension dimension = dimensionSetting.get();
		if (lastDimension != dimension) {
			lastDimension = dimension;
			dimensionRevision++;
		}
		long revision = dimensionRevision;
		updateLayerManager(dimension);
		processRecycleQueue();
		/*
		 * We queue fragments to the thread pool only when a thread isn't working.
		 * This keeps the thread pool queue small and doesn't make the fragment
		 * loading thread think we're done when we're still processing fragments.
		 * While the latter does happen a small amount with this setup, it's not
		 * to the extent of if we were pushing to the thread pool queue as fast
		 * as possible.
		 */
		int maxSize = fragWorkers.getMaximumPoolSize();
		while (loadingQueue.isEmpty() == false
				&& revision == dimensionRevision
				&& dimension.equals(dimensionSetting.get())) {
			if (fragWorkers.getActiveCount() < maxSize) {
				fragWorkers.execute(() -> {
					Fragment f = loadingQueue.poll();
					if (f != null) {
						if (revision == dimensionRevision && dimension.equals(dimensionSetting.get())) {
							loadFragment(dimension, revision, f);
						} else if (!f.getState().equals(Fragment.State.UNINITIALIZED)) {
							loadingQueue.offer(f);
						}
						LockSupport.unpark(flThread);
					}
				});
			} else {
				LockSupport.parkNanos(PARK_MILLIS * 1000000); // if for some reason unpark was never called, unpark after time expires
			}
		}
		if (revision != dimensionRevision || !dimension.equals(dimensionSetting.get())) {
			return;
		}
		while (fragWorkers.getActiveCount() > 0 || !fragWorkers.getQueue().isEmpty()) {
			if (!dimension.equals(dimensionSetting.get())) {
				return;
			}
			LockSupport.parkNanos(PARK_MILLIS * 1000000);
		}
		processRecycleQueue();
		layerManager.clearInvalidatedLayers();
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void requestBiomeReloadForChunks(int[] chunkXs, int[] chunkZs) {
		cache.reloadBiomeChunks(chunkXs, chunkZs);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void requestBiomeReloadAllUsed() {
		cache.reloadBiomesAllUsed();
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void updateLayerManager(Dimension dimension) {
		if (layerManager.updateAll(dimension)) {
			cache.reloadAll();
		}
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void processRecycleQueue() {
		Fragment fragment;
		while ((fragment = recycleQueue.poll()) != null) {
			recycleFragment(fragment);
		}
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void loadFragment(Dimension dimension, long revision, Fragment fragment) {
		State initialState = fragment.getState();
		if (initialState.equals(State.UNINITIALIZED)) {
			return;
		}
		State previousState = fragment.getAndSetState(State.LOADING);
		if (previousState.equals(State.LOADING)) {
			return;
		}
		if (previousState.equals(State.UNINITIALIZED)) {
			fragment.setState(State.UNINITIALIZED);
			return;
		}
		boolean biomeReloadRequested = fragment.getAndClearBiomeReloadRequested();
		if (previousState.equals(State.LOADED)
				&& dimension.equals(fragment.getLoadedDimension())) {
			if (biomeReloadRequested) {
				layerManager.reloadBiomeLayers(dimension, fragment);
			} else {
				layerManager.reloadInvalidated(dimension, fragment);
			}
		} else {
			layerManager.loadAll(dimension, fragment);
		}
		if (revision == dimensionRevision && dimension.equals(dimensionSetting.get())) {
			fragment.setLoadedDimension(dimension);
			fragment.setState(State.LOADED);
			if (fragment.hasBiomeReloadRequested()) {
				loadingQueue.offer(fragment);
			}
		} else {
			fragment.setState(State.INITIALIZED);
			loadingQueue.offer(fragment);
		}
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void recycleFragment(Fragment fragment) {
		if (fragment.tryRecycle()) {
			removeFromLoadingQueue(fragment);
			availableQueue.offer(fragment);
		}
	}

	// TODO: Check performance with and without this. It is not needed, since
	// loadFragment checks for isInitialized(). It helps to keep the
	// loadingQueue small, but it costs time to remove fragments from the queue.
	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void removeFromLoadingQueue(Object fragment) {
		while (loadingQueue.remove(fragment)) {
			// noop
		}
	}
}
