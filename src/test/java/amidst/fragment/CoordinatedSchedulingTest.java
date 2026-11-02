package amidst.fragment;

import static org.junit.Assert.*;

import amidst.fragment.layer.*;
import amidst.fragment.loader.FragmentLoader;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.settings.Setting;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.*;

public class CoordinatedSchedulingTest {
    @Test
    public void terrainCompletionImmediatelyLendsCapacityToBoundedPoiBacklog() {
        Harness h = new Harness(8, true, true);
        try {
            for (int i = 0; i < 20; i++) h.request(i);
            h.processor.processQueues();
            while (!h.executor.tasks.isEmpty()) {
                assertTrue("Terrain phase admits at most one POI", h.overlay.tasks.size() <= 1);
                h.executor.tasks.remove().run();
                h.processor.processQueues();
            }
            assertEquals(20, h.terrain);
            assertEquals(
                    "No idle timeout is needed before four POIs can proceed",
                    4,
                    h.overlay.tasks.size());
            assertEquals(4, h.processor.getActiveLoadCount());
            h.request(30);
            h.processor.processQueues();
            assertEquals(
                    "Panning can immediately resume terrain despite running POIs",
                    1,
                    h.executor.tasks.size());
            h.drain();
            assertEquals(21, h.icons);
            assertEquals(0, h.processor.getActiveLoadCount());
        } finally {
            h.close();
        }
    }

    @Test
    public void blockedPoiCannotStopFineTerrainEvenWithOneTerrainThread() {
        Harness h = new Harness(1, true);
        try {
            List<Fragment> tiles = new ArrayList<>();
            for (int i = 0; i < 8; i++) tiles.add(h.request(i));
            h.processor.processQueues();
            h.executor.tasks.remove().run(); // first fine tile
            h.processor.processQueues();
            Runnable slowPoi = h.executor.tasks.remove(); // leave this query pending
            for (int i = 1; i < 8; i++) {
                h.executor.tasks.remove().run();
                h.processor.processQueues();
            }
            assertEquals(8, h.terrain);
            assertEquals(0, h.icons);
            for (Fragment tile : tiles) {
                assertTrue(tile.hasDisplayLayer(Dimension.OVERWORLD, 3));
                assertFalse(tile.hasDisplayLayer(Dimension.OVERWORLD, 4));
            }
            slowPoi.run();
            h.drain();
            assertEquals(8, h.icons);
        } finally {
            h.close();
        }
    }

    @Test
    public void localChunkChangesDoNotCancelOrRestartUnrelatedTiles() {
        Harness h = new Harness(16, true);
        try {
            List<Fragment> tiles = new ArrayList<>();
            for (int i = 0; i < 24; i++) tiles.add(h.request(i));
            h.processor.processQueues();
            long epoch = h.layers.getInvalidationEpoch();
            // Change a chunk while the first wave is active. Notifications coalesce.
            var affected = ChangedChunkTiles.fromChunks(new int[] {0}, new int[] {0});
            h.processor.requestOverworldReload(affected);
            h.processor.requestOverworldReload(affected);
            assertEquals(epoch, h.layers.getInvalidationEpoch());
            assertEquals(16, h.processor.getActiveLoadCount());
            h.drain();
            assertEquals(24, h.icons);
            assertEquals(26, h.terrain); // Only tile 0 and its neighbor repeat terrain.
            for (Fragment tile : tiles) assertTrue(tile.hasDisplayLayer(Dimension.OVERWORLD, 4));
        } finally {
            h.close();
        }
    }

    @Test
    public void affectedCompletionCannotPublishAndAlphaAlsoRefreshesStructureLayers() {
        for (boolean synchronizedDisplay : new boolean[] {true, false}) {
            Harness h = new Harness(1, true);
            h.cache.setSynchronizedPublication(synchronizedDisplay);
            try {
                Fragment tile = h.request(0);
                h.drain();
                assertEquals(1, h.icons);
                var affected = Set.of(tile.getCorner());
                h.processor.requestOverworldReload(affected);
                h.processor.processQueues();
                h.executor.tasks.remove().run(); // terrain
                h.processor.processQueues();
                h.executor.tasks.remove().run(); // icons completed, not yet published
                h.processor.requestOverworldReload(affected);
                h.processor.processQueues();
                assertEquals(Fragment.State.LOADING, tile.getState());
                assertTrue(
                        tile.hasDisplayData(Dimension.OVERWORLD)); // old complete picture retained
                h.drain();
                assertEquals(3, h.icons);
                assertEquals(3, h.terrain);
                assertFalse(tile.hasBiomeReloadRequested());
                assertTrue(tile.hasDisplayLayer(Dimension.OVERWORLD, 4));
            } finally {
                h.close();
            }
        }
    }

    @Test
    public void overworldChangesPreserveOtherDimensionTilesAndRetainedResults() {
        Harness h = new Harness(4, true);
        try {
            Fragment near = h.request(0), far = h.request(8);
            h.drain();
            h.cache.requestRecycling(far);
            assertTrue(h.cache.retire(far));
            Fragment nether = h.cache.requestFragment(near.getCorner(), Dimension.NETHER);
            h.processor.requestOverworldReload(Set.of(near.getCorner()));
            assertFalse(nether.hasBiomeReloadRequested());
            assertSame(far, h.request(8));
            assertFalse(far.hasBiomeReloadRequested());
            assertTrue(near.hasBiomeReloadRequested());
        } finally {
            h.close();
        }
    }

    @Test
    public void refreshingCompletedTilesKeepsTheFullTerrainConcurrency() throws Exception {
        Harness h = new Harness(16, true);
        try {
            for (int i = 0; i < 24; i++) h.request(i);
            h.processor.processQueues();
            h.drain();
            assertEquals(24, h.icons);
            h.layers.invalidateLayer(1);
            h.layers.invalidateLayer(3);
            h.layers.invalidateLayer(4);
            h.processor.processQueues();
            assertEquals(16, h.processor.getActiveLoadCount());
            var field = FragmentQueueProcessor.class.getDeclaredField("activeOverlayLoadCount");
            field.setAccessible(true);
            assertEquals(
                    "An invalidated terrain image is not structure-only work",
                    0,
                    field.getInt(h.processor));
            h.drain();
            assertEquals(48, h.icons);
        } finally {
            h.close();
        }
    }

    @Test
    public void onePoiQueryNeverConsumesTheTerrainSlots() throws Exception {
        Harness h = new Harness(16, true);
        try {
            for (int i = 0; i < 24; i++) h.request(i);
            h.processor.processQueues();
            assertEquals(16, h.processor.getActiveLoadCount());
            for (int i = 0; i < 5; i++) {
                h.executor.tasks.remove().run();
                h.processor.processQueues();
            }
            var field = FragmentQueueProcessor.class.getDeclaredField("activeOverlayLoadCount");
            field.setAccessible(true);
            assertEquals(
                    "One independent POI request overlaps terrain work",
                    1,
                    field.getInt(h.processor));
            assertEquals(
                    "The POI slot is additional to the terrain pipeline",
                    17,
                    h.processor.getActiveLoadCount());
            h.drain();
            assertEquals(24, h.icons);
        } finally {
            h.close();
        }
    }

    @Test
    public void supersededCompletedResultsCannotPublishAndPendingRefreshDrainsActiveWork() {
        Harness h = new Harness(1, true);
        try {
            Fragment fragment = h.request(0);
            h.processor.processQueues();
            h.executor.tasks.remove().run();
            h.processor.processQueues();
            h.executor
                    .tasks
                    .remove()
                    .run(); // Icons complete, but completion has not been consumed.
            h.layers.invalidateLayer(1);
            h.layers.invalidateLayer(3);
            h.layers.invalidateLayer(4);
            h.processor.processQueues();
            assertFalse(
                    "Superseded icons must never become visible",
                    fragment.hasDisplayLayer(Dimension.OVERWORLD, 4));
            h.layers.invalidateLayer(3); // Cancel the newly queued reservation before it starts.
            h.processor.processQueues();
            assertEquals(1, h.processor.getActiveLoadCount());
            h.drain();
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, 3));
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, 4));
            assertEquals(
                    2, h.icons); // Old generation and final generation, never the cancelled middle
            // one.
        } finally {
            h.close();
        }
    }

    @Test
    public void noEnabledStructuresPublishesTerrainWithoutASecondStage() {
        Harness h = new Harness(4, false);
        try {
            Fragment fragment = h.request(0);
            h.processor.processQueues();
            h.executor.tasks.remove().run();
            h.processor.processQueues();
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, 3));
            assertFalse(fragment.hasDisplayLayer(Dimension.OVERWORLD, 4));
            assertTrue(h.executor.tasks.isEmpty());
            assertEquals(0, h.icons);
        } finally {
            h.close();
        }
    }

    @Test
    public void hidingStructuresCancelsPendingWorkAndUnblocksTheSynchronizedTile() {
        Harness h = new Harness(1, true);
        try {
            Fragment fragment = h.request(0);
            h.processor.processQueues();
            h.executor.tasks.remove().run();
            h.processor.processQueues();
            h.iconsEnabled.set(false);
            h.drain();
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, 3));
            assertFalse(fragment.hasDisplayLayer(Dimension.OVERWORLD, 4));
            assertEquals(0, h.icons);
            h.iconsEnabled.set(true);
            h.processor.processQueues();
            h.drain();
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, 4));
            assertEquals(1, h.icons);
        } finally {
            h.close();
        }
    }

    private static class ManualExecutor extends ThreadPoolExecutor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        ManualExecutor(int count) {
            super(count, count, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
        }

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }
    }

    private static class Harness implements AutoCloseable {
        final ManualExecutor executor, overlay;
        final FragmentCache cache;
        final LayerManager layers;
        final FragmentQueueProcessor processor;
        int icons, terrain;
        final Setting<Boolean> iconsEnabled;

        Harness(int count, boolean enabledIcons) {
            this(count, enabledIcons, false);
        }

        Harness(int count, boolean enabledIcons, boolean separateOverlay) {
            iconsEnabled = Setting.createDummy(enabledIcons);
            executor = new ManualExecutor(count);
            overlay = separateOverlay ? new ManualExecutor(Math.min(4, count)) : executor;
            var available = new ConcurrentLinkedQueue<Fragment>();
            var loading = new ConcurrentLinkedQueue<Fragment>();
            var recycle = new ConcurrentLinkedQueue<Fragment>();
            cache = new FragmentCache(available, loading, List.of(), 5);
            cache.setSynchronizedPublication(true);
            List<LayerDeclaration> declarations = new ArrayList<>();
            List<FragmentLoader> loaders = new ArrayList<>();
            for (int id : new int[] {1, 3, 4}) {
                var declaration =
                        new LayerDeclaration(
                                id,
                                null,
                                false,
                                true,
                                id == 4 ? iconsEnabled : Setting.createImmutable(true));
                declarations.add(declaration);
                loaders.add(
                        new FragmentLoader(declaration) {
                            public void load(Dimension d, Fragment f) {
                                if (id == 4) icons++;
                                if (id == 1) terrain++;
                            }

                            public void reload(Dimension d, Fragment f) {
                                load(d, f);
                            }
                        });
            }
            layers = new LayerManager(declarations, new LayerLoader(loaders, 5), List.of());
            processor =
                    new FragmentQueueProcessor(
                            loading,
                            recycle,
                            cache,
                            layers,
                            executor,
                            Setting.createImmutable(Dimension.OVERWORLD),
                            () -> null,
                            count,
                            true,
                            overlay);
        }

        Fragment request(int x) {
            return cache.requestFragment(CoordinatesInWorld.from(x * 512, 0), Dimension.OVERWORLD);
        }

        void drain() {
            for (int i = 0; i < 200; i++) {
                if (!executor.tasks.isEmpty()) executor.tasks.remove().run();
                else if (!overlay.tasks.isEmpty()) overlay.tasks.remove().run();
                processor.processQueues();
                if (executor.tasks.isEmpty()
                        && overlay.tasks.isEmpty()
                        && processor.getActiveLoadCount() == 0) return;
            }
            fail("Scheduling failed to converge");
        }

        public void close() {
            processor.dispose();
            executor.shutdownNow();
            overlay.shutdownNow();
        }
    }
}
