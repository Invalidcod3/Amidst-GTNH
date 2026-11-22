package amidst.gtnh.worker;

import static org.junit.Assert.*;

import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.*;

import org.junit.Test;

import java.util.*;

public class RwgGeneratorSelectionTest {
    @Test
    public void installedBuildSelectsOneLabelAndRejectsTheOtherBuild() throws Exception {
        String[] versions = {"2.0.6", "2.0.9"};
        WorldType[] types = {WorldType.RWG2_SMOOTH_2_9_0, WorldType.RWG2_SMOOTH_2_9_1};
        for (int i = 0; i < versions.length; i++) {
            List<String> calls = new ArrayList<>();
            var root = new GtnhMinecraftInterface(new Source("RWG", calls, versions[i]));
            WorldType installed = types[i], other = types[1 - i];
            assertEquals(List.of(installed, WorldType.RWG_ALPHA), root.worldTypes());
            assertEquals(installed, root.resolveWorldType(WorldType.DEFAULT));
            assertEquals(installed, root.resolveWorldType(WorldType.RWG2_SMOOTH));
            assertTrue(root.forWorldType(installed).isRwg2());
            assertThrows(MinecraftInterfaceException.class, () -> root.forWorldType(other));
            // Both labels reach the native generator installed in that worker.
            root.forWorldType(installed).cacheIdentity(47);
            assertEquals(List.of("RWG2_CONTINENTS:cache"), calls);

            // The map title/options also resolve legacy names, without losing other options.
            var options =
                    new WorldOptions(
                            WorldSeed.fromUserInput("47"), WorldType.RWG2_SMOOTH, "preserved");
            World world = WorldBuilder.createSilentPlayerless().from(root, options);
            assertEquals(installed, world.getWorldOptions().getWorldType());
            assertEquals("preserved", world.getWorldOptions().getGeneratorOptions());
            assertEquals(47L, world.getWorldOptions().getWorldSeed().getLong());
        }
    }

    @Test
    public void missingOrUnknownVersionDoesNotClaimAKnownPackVersion() throws Exception {
        for (String version : Arrays.asList(null, "2.1.0")) {
            var root = new GtnhMinecraftInterface(new Source("RWG", new ArrayList<>(), version));
            assertEquals(List.of(WorldType.RWG2_SMOOTH, WorldType.RWG_ALPHA), root.worldTypes());
        }
    }

    @Test
    public void smoothEnablesValidatedStructureLayersAndPreservesAlphaLayers() throws Exception {
        var method =
                WorldBuilder.class.getDeclaredMethod(
                        "createGtnhEnabledLayers", boolean.class, Integer[].class);
        method.setAccessible(true);
        Integer[] base =
                java.util.stream.IntStream.rangeClosed(17, 34).boxed().toArray(Integer[]::new);
        List<?> smooth = (List<?>) method.invoke(null, true, base);
        List<?> alpha = (List<?>) method.invoke(null, false, base);
        for (int layer : new int[] {28, 29, 33, 84, 85, 86, 87, 88})
            assertTrue(smooth.contains(layer));
        for (int layer : new int[] {17, 25, 26, 27, 30, 31, 32}) assertTrue(smooth.contains(layer));
        for (int layer : base) assertTrue(alpha.contains(layer));
        for (int layer : new int[] {84, 85, 86, 87, 88}) assertFalse(alpha.contains(layer));
    }

    @Test
    public void smoothIsDefaultAndSeparateMapsKeepTheirOwnGenerator() throws Exception {
        List<String> calls = new ArrayList<>();
        GtnhMinecraftInterface root = new GtnhMinecraftInterface(new Source("RWG", calls));
        assertEquals(List.of(WorldType.RWG2_SMOOTH, WorldType.RWG_ALPHA), root.worldTypes());
        var smooth = root.forWorldType(WorldType.RWG2_SMOOTH);
        var alpha = root.forWorldType(WorldType.RWG_ALPHA);
        assertTrue(smooth.isRwg2());
        assertFalse(alpha.isRwg2());
        assertTrue(root.forWorldType(WorldType.DEFAULT).isRwg2());
        for (var entry : List.of(smooth, alpha, smooth)) {
            WorldType type = entry.isRwg2() ? WorldType.RWG2_SMOOTH : WorldType.RWG_ALPHA;
            var accessor =
                    entry.createWorldAccessor(
                            new WorldOptions(WorldSeed.fromUserInput("47"), type));
            int[] ids =
                    accessor.getBiomeData(Dimension.OVERWORLD, -1, -1, 1, 1, false, data -> data);
            assertEquals(entry.isRwg2() ? 329 : 200, ids[0]);
            entry.cacheIdentity(47);
            entry.getWorldSpawnPoint(47);
        }
        assertEquals(
                List.of(
                        "RWG2_CONTINENTS:biomes",
                        "RWG2_CONTINENTS:cache",
                        "RWG2_CONTINENTS:spawn",
                        "RWG:biomes",
                        "RWG:cache",
                        "RWG:spawn",
                        "RWG2_CONTINENTS:biomes",
                        "RWG2_CONTINENTS:cache",
                        "RWG2_CONTINENTS:spawn"),
                calls);
        assertThrows(
                MinecraftInterfaceException.class, () -> root.forWorldType(WorldType.LARGE_BIOMES));
    }

    @Test
    public void savesAndPreferencesResolveLabelsWithoutAddingModTypesToVanilla() {
        assertSame(WorldType.RWG2_SMOOTH, WorldType.findInstance("RWG2_CONTINENTS"));
        assertSame(WorldType.RWG2_SMOOTH, WorldType.findInstance("RWG2 Smooth"));
        assertSame(WorldType.RWG_ALPHA, WorldType.findInstance("RWG"));
        assertSame(WorldType.RWG_ALPHA, WorldType.findInstance("RWG-Alpha"));
        assertFalse(WorldType.getSelectable().contains(WorldType.RWG2_SMOOTH));
        for (WorldType type : List.of(WorldType.RWG2_SMOOTH_2_9_0, WorldType.RWG2_SMOOTH_2_9_1)) {
            assertSame(type, WorldType.findInstance(type.getName()));
            assertTrue(type.isRwg2());
            assertFalse(WorldType.getSelectable().contains(type));
        }
    }

    private record Source(String generator, List<String> calls, String version)
            implements GtnhBiomeSource {
        Source(String generator, List<String> calls) {
            this(generator, calls, null);
        }

        @Override
        public GtnhBiomeSource withGenerator(String type) {
            return new Source(type, calls, version);
        }

        @Override
        public GtnhWorkerInfo getWorkerInfo() {
            return new GtnhWorkerInfo(
                    PROTOCOL_VERSION,
                    47,
                    "RWG2_CONTINENTS",
                    "provider",
                    "manager",
                    7,
                    -28,
                    -29,
                    -30,
                    -1007,
                    -1013,
                    -1016,
                    -1019,
                    -1008,
                    25,
                    64,
                    -1022,
                    100,
                    22,
                    23,
                    List.of(
                            new GtnhBiomeDescriptor(329, "RWG2", 0, .5f, .5f, 0, 0),
                            new GtnhBiomeDescriptor(200, "RWG", 0, .5f, .5f, 0, 0)),
                    version);
        }

        @Override
        public int[] sampleBiomes(
                long seed, int dimension, int x, int z, int width, int height, int step) {
            calls.add(generator + ":biomes");
            return new int[] {generator.equals("RWG") ? 200 : 329};
        }

        @Override
        public String cacheIdentity(long seed) {
            calls.add(generator + ":cache");
            return generator + seed;
        }

        @Override
        public GtnhSpawnPoint sampleSpawnPoint(long seed, int dimension) {
            calls.add(generator + ":spawn");
            return null;
        }
    }
}
