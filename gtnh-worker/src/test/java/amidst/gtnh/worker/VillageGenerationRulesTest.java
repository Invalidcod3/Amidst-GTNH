package amidst.gtnh.worker;

import static org.junit.Assert.*;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.IEventListener;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.gen.structure.MapGenVillage;
import net.minecraft.world.storage.WorldInfo;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.terraingen.InitMapGenEvent;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class VillageGenerationRulesTest {
    @Before
    public void resetConfig() {
        Config.noVillagesRadius = 0;
        Config.spawnBiomesNames = new String[] {" Plains | plains | oak "};
        Config.parsedInput = null;
    }

    @Test
    public void readsShadowedGeneratorFieldsAndRuntimeBiomeParser() throws Exception {
        ShadowedVillage generator = new ShadowedVillage(73, 21);
        VillageGenerationRules rules =
                VillageGenerationRules.readVillageNames(generator, Config.class);
        assertEquals(73, rules.distance);
        assertEquals(21, rules.separation);
        assertNotSame(Config.spawnBiomesNames, Config.parsedInput);
        assertArrayEquals(Config.spawnBiomesNames, Config.parsedInput);
        assertTrue(rules.accepts(10, 10, new Sampler(false), false));
        // Each request reads the current config; the prior immutable snapshot remains stable.
        Config.spawnBiomesNames = new String[] {"Desert|desert|sandstone"};
        VillageGenerationRules next =
                VillageGenerationRules.readVillageNames(generator, Config.class);
        assertFalse(next.accepts(10, 10, new Sampler(true), false));
        assertTrue(rules.accepts(10, 10, new Sampler(false), false));
    }

    @Test
    public void villageNamesUsesFinalBiomeInsteadOfRwgTerrainAndHonorsCase() throws Exception {
        Sampler sampler = new Sampler(false);
        VillageGenerationRules rules = vn(50, 30);
        assertTrue(rules.accepts(-10, 6, sampler, false));
        assertEquals(-152, sampler.lastX);
        assertEquals(104, sampler.lastZ);
        sampler.terrain = true;
        sampler.loaded = BiomeGenBase.desert;
        assertFalse(rules.accepts(-10, 6, sampler, false));
        Config.spawnBiomesNames = new String[] {"plains"};
        sampler.loaded = BiomeGenBase.plains;
        assertFalse(vn(50, 30).accepts(10, 10, sampler, false));
        assertEquals(0, sampler.terrainCalls);
    }

    @Test
    public void validationUsesIndependentBiomesEvenWhenLoadedDataDiffers() throws Exception {
        Sampler sampler = new Sampler(true);
        sampler.loaded = BiomeGenBase.plains;
        sampler.predicted = BiomeGenBase.desert;
        VillageGenerationRules rules = vn(50, 30);
        assertTrue(rules.accepts(10, 10, sampler, false));
        assertFalse(rules.accepts(10, 10, sampler, true));
        assertEquals(1, sampler.loadedCalls);
        assertEquals(1, sampler.predictedCalls);
    }

    @Test
    public void missingOrEmptyBiomeListAdmitsNothing() throws Exception {
        Config.spawnBiomesNames = null;
        Sampler sampler = new Sampler(true);
        assertFalse(vn(50, 30).accepts(100, 100, sampler, false));
        assertNull(Config.parsedInput);
        Config.spawnBiomesNames = new String[] {"  |plains|oak"};
        assertFalse(vn(50, 30).accepts(100, 100, sampler, false));
        assertEquals(0, sampler.loadedCalls);
    }

    @Test
    public void radiusIsInChunksAndIncludesTheBoundary() throws Exception {
        Config.noVillagesRadius = 5;
        VillageGenerationRules rules = vn(50, 30);
        Sampler sampler = new Sampler(false);
        assertFalse(rules.accepts(0, 0, sampler, false));
        assertFalse(rules.accepts(-3, -3, sampler, false));
        assertEquals(0, sampler.loadedCalls);
        assertTrue(rules.accepts(-3, -4, sampler, false));
        assertTrue(rules.accepts(6, 0, sampler, false));
    }

    @Test
    public void distantRadiusCheckPreservesNativeIntegerArithmetic() throws Exception {
        VillageGenerationRules rules = vn(50, 30);
        // The installed VN family squares ints, including at radius zero. Changing to long
        // would mark starts that the native selector rejects at these distant coordinates.
        assertFalse(rules.accepts(50000, 0, new Sampler(false), false));
        Config.noVillagesRadius = 50000;
        assertTrue(vn(50, 30).accepts(0, 0, new Sampler(false), false));
    }

    @Test
    public void reportedNativeCandidateUsesConfiguredSpacingAtNegativeCoordinates()
            throws Exception {
        // Frozen from Village Names 4.5.18 runtime bytecode, seed 0, spacing 50/30.
        Sampler sampler = new Sampler(false);
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                query(0, -3996, 1668, 1, 1, sampler, vn(50, 30));
        assertEquals(1, result.size());
        assertEquals(-3996, result.get(0).x);
        assertEquals(1668, result.get(0).z);
        assertEquals("POSSIBLE", result.get(0).certainty);
        assertTrue(
                query(0, -3996, 1668, 1, 1, new Sampler(true), VillageGenerationRules.rwg(24, 8))
                        .isEmpty());
    }

    @Test
    public void adjacentWindowsNeitherLoseNorDuplicateStarts() throws Exception {
        Sampler sampler = new Sampler(false);
        VillageGenerationRules rules = vn(50, 30);
        Set<String> whole = coordinates(query(-1, -4097, -2049, 8193, 4097, sampler, rules));
        Set<String> joined = new HashSet<String>();
        int count = 0;
        for (int x = -4097; x < 4096; x += 511) {
            for (int z = -2049; z < 2048; z += 513) {
                List<RoguelikeDungeonPredictor.StructureDescriptor> part =
                        query(
                                -1,
                                x,
                                z,
                                Math.min(511, 4096 - x),
                                Math.min(513, 2048 - z),
                                sampler,
                                rules);
                count += part.size();
                joined.addAll(coordinates(part));
            }
        }
        assertFalse(whole.isEmpty());
        assertEquals(whole, joined);
        assertEquals(joined.size(), count);
    }

    @Test
    public void vanillaRwgRulesMatchMinecraftSelectorAcrossSeedsAndNegativeRegions()
            throws Exception {
        for (int distance : new int[] {24, 36}) {
            Map<String, String> parameters =
                    Collections.singletonMap("distance", Integer.toString(distance));
            VillageGenerationRules rules =
                    VillageGenerationRules.read(new MapGenVillage(parameters));
            assertEquals(distance, rules.distance);
            assertEquals(8, rules.separation);
            NativeVillage nativeVillage = new NativeVillage(parameters);
            SelectorWorld world = allocate(SelectorWorld.class);
            world.manager =
                    new WorldChunkManager() {
                        @Override
                        public boolean areBiomesViable(int x, int z, int radius, List allowed) {
                            return Math.floorMod(x + z, 5) != 0;
                        }
                    };
            for (long seed : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
                world.seed = seed;
                NBTTagCompound data = new NBTTagCompound();
                data.setLong("RandomSeed", seed);
                world.info = new WorldInfo(data);
                nativeVillage.bind(world);
                Set<String> expected = new HashSet<String>();
                for (int z = -96; z < 96; z++)
                    for (int x = -96; x < 96; x++) {
                        if (nativeVillage.accepts(x, z))
                            expected.add((x * 16 + 4) + "," + (z * 16 + 4));
                    }
                Sampler sampler =
                        new Sampler(true) {
                            @Override
                            public boolean isVillageLocationViable(int x, int z) {
                                return world.manager.areBiomesViable(
                                        x, z, 0, Collections.emptyList());
                            }

                            @Override
                            public BiomeGenBase getBiomeAt(int x, int z) {
                                throw new AssertionError(
                                        "Vanilla RWG selection must use terrain viability");
                            }
                        };
                assertEquals(
                        expected,
                        coordinates(query(seed, -1532, -1532, 3072, 3072, sampler, rules)));
            }
        }
    }

    @Test
    public void previewUsesFinalForgeSelectionOnceWithoutGeneratingChunks() throws Exception {
        VillageHook hook = new VillageHook();
        // Register directly with Forge's listener list: EventBus.register would bootstrap
        // the entire FML mod loader in this save-free unit JVM.
        Field id = MinecraftForge.TERRAIN_GEN_BUS.getClass().getDeclaredField("busID");
        id.setAccessible(true);
        int bus = id.getInt(MinecraftForge.TERRAIN_GEN_BUS);
        java.lang.reflect.Constructor<InitMapGenEvent> constructor =
                InitMapGenEvent.class.getDeclaredConstructor(
                        InitMapGenEvent.EventType.class, net.minecraft.world.gen.MapGenBase.class);
        constructor.setAccessible(true);
        InitMapGenEvent event =
                constructor.newInstance(InitMapGenEvent.EventType.VILLAGE, hook.selected);
        IEventListener listener =
                value -> {
                    if (value instanceof InitMapGenEvent) hook.select((InitMapGenEvent) value);
                };
        event.getListenerList().register(bus, EventPriority.NORMAL, listener);
        try {
            SurfaceBiomeSampler sampler =
                    SurfaceSamplerFixture.current(new SurfaceSamplerFixture.Manager(0, true));
            Object selected = sampler.getVillageGenerator();
            assertSame(hook.selected, selected);
            assertSame(selected, sampler.getVillageGenerator());
            assertEquals(1, hook.calls);
            assertEquals(36, VillageGenerationRules.read(selected).distance);
        } finally {
            event.getListenerList().unregister(bus, listener);
        }
    }

    @Test
    public void unknownReplacementIsExplicitlyRejected() {
        try {
            VillageGenerationRules.read(new ShadowedVillage(50, 30));
            fail("Unknown subclasses must not silently use the vanilla algorithm");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Unsupported village generator"));
        }
    }

    private static VillageGenerationRules vn(int distance, int separation) throws Exception {
        return VillageGenerationRules.readVillageNames(
                new ShadowedVillage(distance, separation), Config.class);
    }

    private static List<RoguelikeDungeonPredictor.StructureDescriptor> query(
            long seed,
            int x,
            int z,
            int width,
            int height,
            SurfaceBiomeSampler sampler,
            VillageGenerationRules rules) {
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        RwgStructurePredictor.addVillages(
                seed, x, z, (long) x + width, (long) z + height, sampler, rules, false, result);
        return result;
    }

    private static Set<String> coordinates(
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        Set<String> values = new HashSet<String>();
        for (RoguelikeDungeonPredictor.StructureDescriptor d : result) values.add(d.x + "," + d.z);
        return values;
    }

    public static class Config {
        public static int noVillagesRadius;
        public static String[] spawnBiomesNames;
        static String[] parsedInput;

        public static Map<String, List<String>> unpackBiomes(String[] input) {
            parsedInput = input;
            List<String> names = new ArrayList<String>();
            for (String entry : input) {
                String name = entry.split("\\|", -1)[0].trim();
                if (!name.isEmpty()) names.add(name);
            }
            return Collections.singletonMap("BiomeNames", names);
        }
    }

    private static class ShadowedVillage extends MapGenVillage {
        private final int field_82665_g, field_82666_h;

        ShadowedVillage(int distance, int separation) {
            field_82665_g = distance;
            field_82666_h = separation;
        }
    }

    private static class Sampler implements SurfaceBiomeSampler {
        boolean terrain;
        BiomeGenBase loaded = BiomeGenBase.plains, predicted = BiomeGenBase.plains;
        int lastX, lastZ, terrainCalls, loadedCalls, predictedCalls;

        Sampler(boolean terrain) {
            this.terrain = terrain;
        }

        public BiomeGenBase getBiomeAt(int x, int z) {
            lastX = x;
            lastZ = z;
            loadedCalls++;
            return loaded;
        }

        public BiomeGenBase getPredictedBiomeAt(int x, int z) {
            predictedCalls++;
            return predicted;
        }

        public boolean isVillageLocationViable(int x, int z) {
            terrainCalls++;
            return terrain;
        }

        public float getApproximateSurfaceHeight(int x, int z) {
            throw new AssertionError();
        }

        public ChunkPosition findSpawnBiomePosition(Random random) {
            throw new AssertionError();
        }
    }

    private static class NativeVillage extends MapGenVillage {
        NativeVillage(Map<String, String> parameters) {
            super(parameters);
        }

        void bind(World world) {
            worldObj = world;
        }

        boolean accepts(int x, int z) {
            return canSpawnStructureAtCoords(x, z);
        }
    }

    public static class SelectorWorld extends WorldServer {
        long seed;
        WorldChunkManager manager;
        WorldInfo info;

        private SelectorWorld() {
            super(null, null, "unused", 0, null, null);
        }

        @Override
        public long getSeed() {
            return seed;
        }

        @Override
        public WorldChunkManager getWorldChunkManager() {
            return manager;
        }

        @Override
        public WorldInfo getWorldInfo() {
            return info;
        }

        @Override
        public net.minecraft.world.chunk.Chunk getChunkFromChunkCoords(int x, int z) {
            throw new AssertionError("Selection must not load or generate chunks");
        }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        T value = type.cast(((sun.misc.Unsafe) field.get(null)).allocateInstance(type));
        Field random = World.class.getDeclaredField("rand");
        random.setAccessible(true);
        random.set(value, new Random());
        return value;
    }

    public static class VillageHook {
        final MapGenVillage selected =
                new MapGenVillage(Collections.singletonMap("distance", "36"));
        int calls;

        public void select(InitMapGenEvent event) {
            if (event.type == InitMapGenEvent.EventType.VILLAGE) {
                calls++;
                event.newGen = selected;
            }
        }
    }
}
