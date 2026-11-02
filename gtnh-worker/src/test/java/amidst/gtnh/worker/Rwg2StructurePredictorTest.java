package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.block.Block;
import net.minecraft.world.WorldServer;

import org.junit.Test;

import java.util.*;

public class Rwg2StructurePredictorTest {
    private static final long CENTER = (long) -3 << 32 ^ 8L;
    private static final int TWILIGHT_SEED = 16;

    private static class Terrain implements Rwg2StructurePredictor.Terrain {
        boolean cave, river, volcano;
        int calls, lavaCalls, riverCalls;

        public long lavaCenter(int x, int z) {
            calls++;
            lavaCalls++;
            return cave ? CENTER : Long.MIN_VALUE;
        }

        public boolean volcano(int x, int z) {
            return volcano;
        }

        public int[] riverCenter(int x, int z) {
            calls++;
            riverCalls++;
            return river ? new int[] {-3, 8} : null;
        }

        public String biomeName(int x, int z) {
            return "Deep Ocean";
        }
    }

    private static Rwg2StructurePredictor predictor(
            Terrain terrain, long seed, boolean loot, boolean twilight, boolean monuments) {
        return new Rwg2StructurePredictor(
                terrain, seed, true, loot, twilight, monuments, null, null);
    }

    private static List<RoguelikeDungeonPredictor.StructureDescriptor> run(
            Rwg2StructurePredictor predictor) {
        Rwg2StructurePredictor.Job job = predictor.begin(-32, 0, 64, 32, null);
        assertTrue(job.advance(Long.MAX_VALUE));
        return job.result;
    }

    @Test
    public void disabledLandmarksSkipTheirAlgorithmsAndSelectionDoesNotPoisonCache() {
        Terrain terrain = new Terrain();
        terrain.river = true;
        Rwg2StructurePredictor predictor = predictor(terrain, TWILIGHT_SEED, true, true, true);
        Rwg2StructurePredictor.Job monuments =
                predictor.begin(
                        -32,
                        0,
                        64,
                        32,
                        null,
                        StructureSelection.of(Arrays.asList("OCEAN_MONUMENT")));
        assertTrue(monuments.advance(Long.MAX_VALUE));
        assertEquals(0, terrain.calls);
        Rwg2StructurePredictor.Job portal =
                predictor.begin(
                        -32,
                        0,
                        64,
                        32,
                        null,
                        StructureSelection.of(Arrays.asList("RWG2_TWILIGHT_PORTAL")));
        assertTrue(portal.advance(Long.MAX_VALUE));
        assertEquals(0, terrain.lavaCalls);
        assertEquals(8, terrain.riverCalls);
        assertEquals(1, portal.result.size());
        assertEquals("RWG2_TWILIGHT_PORTAL", portal.result.get(0).kind);
        Rwg2StructurePredictor.Job game =
                predictor.begin(
                        -32,
                        0,
                        64,
                        32,
                        null,
                        StructureSelection.of(Arrays.asList("RWG2_RIVER_CHAMBER_GAME")));
        assertTrue(game.advance(Long.MAX_VALUE));
        assertTrue(
                "Hiding the portal must not turn its location into a game", game.result.isEmpty());
        assertEquals("The shared river scan is reused", 8, terrain.riverCalls);
        Rwg2StructurePredictor.Job none =
                predictor.begin(
                        -32,
                        0,
                        64,
                        32,
                        null,
                        StructureSelection.of(Collections.<String>emptyList()));
        assertTrue(none.advance(Long.MAX_VALUE));
        assertTrue(none.result.isEmpty());
        Rwg2StructurePredictor.Job all = predictor.begin(-32, 0, 64, 32, null);
        assertTrue(all.advance(Long.MAX_VALUE));
        assertEquals(8, terrain.lavaCalls);
        assertEquals(8, terrain.riverCalls);
        assertEquals(portal.result.get(0).kind, all.result.get(0).kind);
    }

    @Test
    public void strongholdOnlyDoesNotReadVillageConfigOrBiomes() {
        assertFalse(
                new RwgStructurePredictor("missing.Config")
                        .predict(
                                7,
                                -4096,
                                -4096,
                                8192,
                                8192,
                                null,
                                false,
                                StructureSelection.of(Arrays.asList("STRONGHOLD")))
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> StructureSelection.of(Arrays.asList("UNKNOWN")));
    }

    @Test
    public void lavaCentersChooseGameOrPortalAndRespectOwningChunk() {
        Terrain terrain = new Terrain();
        terrain.cave = true;
        List<RoguelikeDungeonPredictor.StructureDescriptor> games =
                run(predictor(terrain, 0, true, true, false));
        assertEquals(1, games.size());
        assertEquals("LOOTGAMES_DUNGEON", games.get(0).kind);
        assertEquals("RWG2_LAVA_CAVE", games.get(0).subtype);
        assertEquals(Integer.valueOf(39), games.get(0).y);
        assertEquals("TERRAIN_CANDIDATE", games.get(0).certainty);
        assertEquals(
                "RWG2_NETHER_PORTAL", run(predictor(terrain, 6, true, true, false)).get(0).kind);
        assertTrue(run(predictor(terrain, 0, false, true, false)).isEmpty());
        terrain.volcano = true;
        assertTrue(run(predictor(terrain, 0, true, true, false)).isEmpty());
        assertTrue(run(predictor(terrain, 6, true, true, false)).isEmpty());
    }

    @Test
    public void riverChoiceSkipsOrSelectsAnAvailableCenterpiece() {
        Terrain terrain = new Terrain();
        terrain.river = true;
        List<RoguelikeDungeonPredictor.StructureDescriptor> games =
                run(predictor(terrain, 0, true, true, false));
        assertEquals(1, games.size());
        assertEquals("LOOTGAMES_DUNGEON", games.get(0).kind);
        assertEquals(Integer.valueOf(66), games.get(0).y);
        assertTrue(run(predictor(terrain, 3, true, true, false)).isEmpty());
        // Seed 16 at (-3,8) is the native Twilight branch (independent fixed fixture).
        List<RoguelikeDungeonPredictor.StructureDescriptor> portal =
                run(predictor(terrain, TWILIGHT_SEED, true, true, false));
        assertEquals("RWG2_TWILIGHT_PORTAL", portal.get(0).kind);
        assertEquals(Integer.valueOf(64), portal.get(0).y);
        assertEquals(
                "LOOTGAMES_DUNGEON",
                run(predictor(terrain, TWILIGHT_SEED, true, false, false)).get(0).kind);
        assertTrue(run(predictor(terrain, TWILIGHT_SEED, false, false, false)).isEmpty());
    }

    @Test
    public void jobsYieldCachePureCentersAndClipHalfOpenBounds() {
        Terrain terrain = new Terrain();
        terrain.cave = true;
        Rwg2StructurePredictor p = predictor(terrain, 0, true, true, false);
        Rwg2StructurePredictor.Job job = p.begin(-16, 0, 16, 16, null);
        assertFalse(job.advance(0));
        assertEquals(0, terrain.calls);
        assertTrue(job.advance(Long.MAX_VALUE));
        assertEquals(1, job.result.size());
        int calls = terrain.calls;
        Rwg2StructurePredictor.Job repeat = p.begin(-16, 0, 13, 16, null);
        assertTrue(repeat.advance(Long.MAX_VALUE));
        assertTrue(repeat.result.isEmpty());
        assertEquals(calls, terrain.calls);
        Rwg2StructurePredictor disabled =
                new Rwg2StructurePredictor(terrain, 0, false, true, true, false, null, null);
        assertTrue(run(disabled).isEmpty());
    }

    @Test
    public void monumentChecksActualVillageNamesRadiiAndNegativeRegions() {
        int[] point = Rwg2StructurePredictor.monumentCandidate(-47, -1, -2);
        assertTrue(point[0] < 0 && point[1] < -512);
        for (int offset : new int[] {4, 5, 7, 8}) {
            Terrain terrain =
                    new Terrain() {
                        @Override
                        public String biomeName(int x, int z) {
                            if (x == point[0] + offset && z == point[1])
                                return offset == 5 ? "River" : "Plains";
                            return "Deep Ocean";
                        }
                    };
            Rwg2StructurePredictor.Job job =
                    predictor(terrain, -47, false, false, true)
                            .begin(point[0], point[1], 1, 1, null);
            assertTrue(job.advance(Long.MAX_VALUE));
            assertEquals(
                    "radius offset " + offset,
                    offset == 5 || offset == 8 ? 1 : 0,
                    job.result.size());
        }
    }

    public static class EvidenceWorld extends WorldServer {
        boolean loaded;
        int reads;
        Block existing;
        net.minecraft.world.storage.WorldInfo info;

        @Override
        public net.minecraft.world.storage.WorldInfo getWorldInfo() {
            return info;
        }

        private EvidenceWorld() {
            super(null, null, "unused", 0, null, null);
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return loaded;
        }

        @Override
        public Block getBlock(int x, int y, int z) {
            assertTrue("Only already loaded blocks may be read", loaded);
            assertEquals(-3, x);
            assertEquals(39, y);
            assertEquals(8, z);
            reads++;
            return existing;
        }

        @Override
        public net.minecraft.world.chunk.Chunk getChunkFromChunkCoords(int x, int z) {
            throw new AssertionError("Prediction must not load chunks");
        }
    }

    @Test
    public void evidenceIsRefreshedWithoutCachingWorldBlocksOrLoadingChunks() throws Exception {
        java.lang.reflect.Field unsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafe.setAccessible(true);
        EvidenceWorld world =
                (EvidenceWorld)
                        ((sun.misc.Unsafe) unsafe.get(null)).allocateInstance(EvidenceWorld.class);
        world.info =
                new net.minecraft.world.storage.WorldInfo(new net.minecraft.nbt.NBTTagCompound());
        Terrain terrain = new Terrain();
        terrain.cave = true;
        Block puzzle = new Block(net.minecraft.block.material.Material.rock) {};
        Rwg2StructurePredictor predictor =
                new Rwg2StructurePredictor(terrain, 0, true, true, true, false, puzzle, null);
        for (int phase = 0; phase < 3; phase++) {
            world.loaded = phase != 0;
            world.existing = phase == 1 ? puzzle : null;
            Rwg2StructurePredictor.Job job = predictor.begin(-16, 0, 16, 16, world);
            assertTrue(job.advance(Long.MAX_VALUE));
            assertEquals(
                    phase == 1 ? "RECORDED" : "TERRAIN_CANDIDATE", job.result.get(0).certainty);
            assertEquals(phase, world.reads);
        }
        net.minecraft.nbt.NBTTagCompound disabled = new net.minecraft.nbt.NBTTagCompound();
        disabled.setBoolean("MapFeatures", false);
        world.info = new net.minecraft.world.storage.WorldInfo(disabled);
        Rwg2StructurePredictor.Job job = predictor.begin(-16, 0, 16, 16, world);
        assertTrue(job.advance(Long.MAX_VALUE));
        assertEquals("RWG2 landmarks ignore the world map-features switch", 1, job.result.size());
    }
}
