package amidst.fragment;

import static org.junit.Assert.*;

import amidst.fragment.layer.*;
import amidst.fragment.loader.*;
import amidst.mojangapi.minecraftinterface.*;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.*;
import amidst.mojangapi.world.oracle.BiomeDataOracle;
import amidst.settings.Setting;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

public class FineBiomeLoadingTest {
    @Test
    public void preciseTerrainPublishesBeforePoiWithNoCoarsePass() throws Exception {
        List<String> calls = new ArrayList<>();
        var accessor =
                new ThreadedWorldAccessor(
                        ignored ->
                                new MinecraftInterface.WorldAccessor() {
                                    public Set<Dimension> supportedDimensions() {
                                        return Set.of(Dimension.OVERWORLD);
                                    }

                                    public <T> T getBiomeData(
                                            Dimension d,
                                            int x,
                                            int z,
                                            int w,
                                            int h,
                                            boolean quarter,
                                            Function<int[], T> mapper) {
                                        calls.add("fine:" + x * 4);
                                        assertTrue(quarter);
                                        assertEquals(128, w);
                                        int[] data = new int[w * h];
                                        Arrays.fill(data, 329);
                                        return mapper.apply(data);
                                    }
                                });
        var oracle =
                new BiomeDataOracle(
                        accessor, Dimension.OVERWORLD, null, new BiomeDataOracle.Config());
        var declarations = new ArrayList<LayerDeclaration>();
        for (int i = 0; i < 5; i++)
            declarations.add(
                    new LayerDeclaration(i, null, false, true, Setting.createImmutable(true)));
        var biomeLoader =
                new BiomeDataLoader(
                        declarations.get(LayerIds.BIOME_DATA),
                        oracle,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Map.of());
        var imageLoader =
                new ImageLoader(
                        declarations.get(LayerIds.BACKGROUND),
                        Resolution.QUARTER,
                        (d, f, cx, cz, x, z) -> 0xff000000 | f.getBiomeDataAt(x, z));
        int iconLayer = 4;
        var iconLoader =
                new FragmentLoader(declarations.get(iconLayer)) {
                    public void load(Dimension d, Fragment f) {
                        assertEquals(329, f.getBiomeDataAt(0, 0));
                        calls.add("icons:" + f.getCorner().getX());
                    }

                    public void reload(Dimension d, Fragment f) {
                        load(d, f);
                    }
                };
        var layerLoader = new LayerLoader(List.of(biomeLoader, imageLoader, iconLoader), 5);
        var layers = new LayerManager(declarations, layerLoader, List.of());
        var available = new ConcurrentLinkedQueue<Fragment>();
        var loading = new ConcurrentLinkedQueue<Fragment>();
        var recycling = new ConcurrentLinkedQueue<Fragment>();
        var cache =
                new FragmentCache(available, loading, List.of(f -> f.initBiomeData(128, 128)), 5);
        cache.setSynchronizedPublication(true);
        var tasks = new ArrayDeque<Runnable>();
        var executor =
                new ThreadPoolExecutor(
                        1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>()) {
                    public void execute(Runnable task) {
                        tasks.add(task);
                    }
                };
        var processor =
                new FragmentQueueProcessor(
                        loading,
                        recycling,
                        cache,
                        layers,
                        executor,
                        Setting.createDummy(Dimension.OVERWORLD),
                        () -> null,
                        1);
        Fragment a = cache.requestFragment(CoordinatesInWorld.from(-512, 0), Dimension.OVERWORLD);
        Fragment b = cache.requestFragment(CoordinatesInWorld.origin(), Dimension.OVERWORLD);
        processor.processQueues();
        tasks.remove().run();
        processor.processQueues();
        assertTrue(a.hasDisplayLayer(Dimension.OVERWORLD, LayerIds.BACKGROUND));
        assertFalse(a.hasDisplayLayer(Dimension.OVERWORLD, iconLayer));
        assertTrue(a.hasLayerData(LayerIds.BIOME_DATA));
        assertEquals(329, a.getBiomeDataAt(127, 127));
        assertNotNull(a.getImage(LayerIds.BACKGROUND));
        tasks.remove().run();
        processor.processQueues();
        assertTrue(a.hasDisplayLayer(Dimension.OVERWORLD, LayerIds.BACKGROUND));
        assertTrue(a.hasDisplayLayer(Dimension.OVERWORLD, iconLayer));
        var firstImage = a.getImage(LayerIds.BACKGROUND);
        for (int guard = 0; !tasks.isEmpty() && guard < 20; guard++) {
            tasks.remove().run();
            processor.processQueues();
        }
        assertEquals(List.of("fine:-512", "icons:-512", "fine:0", "icons:0"), calls);
        assertEquals(Fragment.State.LOADED, a.getState());
        assertEquals(Fragment.State.LOADED, b.getState());
        assertSame(firstImage, a.getImage(LayerIds.BACKGROUND));
        assertEquals(0xff000149, a.getImage(LayerIds.BACKGROUND).getRGB(127, 127));
        cache.requestRecycling(a);
        cache.retire(a);
        assertSame(a, cache.requestFragment(CoordinatesInWorld.from(-512, 0), Dimension.OVERWORLD));
        processor.processQueues();
        assertTrue(tasks.isEmpty());
        a.setCorner(CoordinatesInWorld.from(512, 512));
        assertFalse(a.hasDisplayData(Dimension.OVERWORLD));
        processor.dispose();
        executor.shutdownNow();
    }
}
