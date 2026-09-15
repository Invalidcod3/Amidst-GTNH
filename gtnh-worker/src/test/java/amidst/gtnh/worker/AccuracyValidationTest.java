package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.structure.MapGenStructureData;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntBinaryOperator;

public class AccuracyValidationTest {
    /** Allocate a read-only world double without starting Minecraft or writing a save. */
    private static FakeWorld world(int biome) throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        FakeWorld world =
                (FakeWorld) ((sun.misc.Unsafe) field.get(null)).allocateInstance(FakeWorld.class);
        world.loaded = true;
        world.chunk = new Chunk(world, 0, 0);
        world.chunk.isTerrainPopulated = true;
        Arrays.fill(world.chunk.getBiomeArray(), (byte) biome);
        java.lang.reflect.Field storage =
                net.minecraft.world.World.class.getField("perWorldStorage");
        storage.setAccessible(true);
        storage.set(world, new net.minecraft.world.storage.MapStorage(null));
        return world;
    }

    public static class FakeWorld extends WorldServer {
        boolean loaded;
        Chunk chunk;

        private FakeWorld() {
            super(null, null, "unused", 0, null, null);
        }

        @Override
        public long getSeed() {
            return 77;
        }

        @Override
        public boolean blockExists(int x, int y, int z) {
            return loaded;
        }

        @Override
        public Chunk getChunkFromBlockCoords(int x, int z) {
            if (!loaded) throw new AssertionError("Must not load chunks");
            return chunk;
        }

        @Override
        public net.minecraft.world.biome.BiomeGenBase getBiomeGenForCoords(int x, int z) {
            throw new AssertionError("Must read the stored byte, not regenerate a missing biome");
        }
    }

    private AccuracyValidation.Job job(
            FakeWorld world, String category, IntBinaryOperator predictor) {
        return new AccuracyValidation.Job(
                77,
                0,
                0,
                0,
                16,
                16,
                4,
                category,
                "session",
                predictor,
                (id, unused) -> id,
                null,
                id -> world);
    }

    @Test
    public void comparesIndependentPredictionsWithStoredBiomesAndYields() throws Exception {
        FakeWorld world = world(4);
        AccuracyValidation.Job job = job(world, "BIOMES", (x, z) -> 2);
        assertFalse(job.advance(0));
        assertEquals(1, job.result.mismatched);
        assertTrue(job.advance(Long.MAX_VALUE));
        assertEquals(16, job.result.mismatched);
        assertEquals(0, job.result.matched);
        AccuracyValidation.Job equal = job(world, "BIOMES", (x, z) -> 4);
        assertTrue(equal.advance(Long.MAX_VALUE));
        assertEquals(16, equal.result.matched);
    }

    @Test
    public void additionalBiomeValidationBypassesLiveWorldOverrides() throws Exception {
        FakeWorld live = world(4);
        try (AdditionalDimensionBiomes sampler =
                new AdditionalDimensionBiomes(
                        () ->
                                Collections.singletonMap(
                                        "Venus",
                                        new net.minecraft.world.biome.BiomeGenBase[] {
                                            net.minecraft.world.biome.BiomeGenBase.desert
                                        }),
                        seed -> {
                            throw new AssertionError();
                        },
                        id -> live)) {
            assertEquals(
                    AdditionalDimensionBiomes.displayId("Venus", 2),
                    sampler.sample(77, 7, "Venus", 0, 0, 1, 1, 1, false)[0]);
        }
    }

    @Test
    public void unloadedOrMissingBiomeEvidenceCannotCountAsPass() throws Exception {
        FakeWorld world = world(255);
        IntBinaryOperator forbidden =
                (x, z) -> {
                    throw new AssertionError("No prediction needed without evidence");
                };
        AccuracyValidation.Job missing = job(world, "BIOMES", forbidden);
        missing.advance(Long.MAX_VALUE);
        assertEquals(16, missing.result.unverified);
        assertEquals(0, missing.result.matched);
        world.loaded = false;
        AccuracyValidation.Job unloaded = job(world, "BIOMES", forbidden);
        unloaded.advance(Long.MAX_VALUE);
        assertEquals(16, unloaded.result.unverified);
    }

    @Test
    public void sameSeedSaveReplacementInvalidatesInProgressReport() throws Exception {
        AtomicReference<WorldServer> current = new AtomicReference<>(world(4));
        AccuracyValidation.Job job =
                new AccuracyValidation.Job(
                        77,
                        0,
                        0,
                        0,
                        16,
                        16,
                        4,
                        "BIOMES",
                        "A",
                        (x, z) -> 4,
                        (id, u) -> id,
                        null,
                        id -> current.get());
        job.advance(0);
        current.set(world(4));
        assertThrows(IllegalStateException.class, () -> job.advance(Long.MAX_VALUE));
    }

    @Test
    public void structureAbsenceRequiresPopulatedChunksAndCanBeRechecked() throws Exception {
        FakeWorld world = world(4);
        MapGenStructureData data = new MapGenStructureData("Village");
        world.perWorldStorage.setData("Village", data);
        RoguelikeDungeonPredictor.StructureDescriptor candidate =
                new RoguelikeDungeonPredictor.StructureDescriptor("VILLAGE", "", 4, 4, "POSSIBLE");
        world.chunk.isTerrainPopulated = false;
        AccuracyValidation.Job initial = job(world, "STRUCTURES", (x, z) -> 4);
        initial.structures = Collections.singletonList(candidate);
        initial.advance(Long.MAX_VALUE);
        assertEquals(1, initial.result.unverified);
        world.chunk.isTerrainPopulated = true;
        AccuracyValidation.Job absent = job(world, "STRUCTURES", (x, z) -> 4);
        absent.structures = Collections.singletonList(candidate);
        absent.advance(Long.MAX_VALUE);
        assertEquals(1, absent.result.mismatched);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("ChunkX", 0);
        tag.setInteger("ChunkZ", 0);
        data.func_143043_a(tag, 0, 0);
        AccuracyValidation.Job present = job(world, "STRUCTURES", (x, z) -> 4);
        present.structures = Collections.singletonList(candidate);
        present.advance(Long.MAX_VALUE);
        assertEquals(1, present.result.matched);
        assertEquals(0, present.result.mismatched);
    }
}
