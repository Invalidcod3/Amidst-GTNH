package amidst.gtnh.worker;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

public class RwgChunkBiomeReplayTest {
    @Test
    public void registeredOverridesCanWriteAnyBiomeAndShareTheWholeChunk() throws Exception {
        CallbackBiome biome = new CallbackBiome();
        biome.mode = 1;
        SurfaceBiomeSampler sampler = sampler(biome);
        // No surface field, dune class or biome-name mapping exists in this fixture.
        assertSame(BiomeGenBase.icePlains, sampler.getPredictedBiomeAt(-32, 16));
        assertSame(BiomeGenBase.mushroomIsland, sampler.getPredictedBiomeAt(-17, 31));
        assertEquals(256, biome.replacements);
        assertEquals(1, biome.maps);
        assertTrue(biome.mapBeforeSurface);
        // Every sample in this chunk uses the same completed result; callbacks
        // may even update another column, so early per-column caching is wrong.
        for (int z = 16; z < 32; z++) for (int x = -32; x < -16; x++) sampler.getPredictedBiomeAt(x, z);
        assertEquals(256, biome.replacements);
        sampler.getPredictedBiomeAt(-16, 16);
        assertEquals(512, biome.replacements);
        assertEquals(2, biome.maps);
    }

    @Test
    public void riverAssignmentPrecedesEveryRegisteredSurfaceCallback() throws Exception {
        CallbackBiome biome = new CallbackBiome();
        biome.mode = 2;
        SurfaceBiomeSampler sampler = sampler(biome);
        assertSame(BiomeGenBase.extremeHills, sampler.getPredictedBiomeAt(3, -8));
        assertEquals(256, biome.riverInputs);
        SurfaceBiomeSampler.Prediction stages = sampler.describePrediction(3, -8);
        assertEquals(BiomeGenBase.forest.biomeID, stages.baseId);
        assertEquals(BiomeGenBase.river.biomeID, stages.riverId);
        assertEquals(BiomeGenBase.extremeHills.biomeID, stages.predictedId);
        assertEquals(CallbackBiome.class.getName(), stages.realisticType);
    }

    @Test
    public void callbackRandomSequenceIncludesBedrockAndResetsAcrossChunks() throws Exception {
        CallbackBiome biome = new CallbackBiome();
        biome.mode = 3;
        SurfaceBiomeSampler sampler = sampler(biome);
        for (int cx : new int[] { -2, 7, -3 }) {
            int cz = 4;
            Random expected = new Random((long) cx * 341873128712L + (long) cz * 132897987541L);
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BiomeGenBase wanted = expected.nextBoolean() ? BiomeGenBase.jungle : BiomeGenBase.taiga;
                    assertSame(wanted, sampler.getPredictedBiomeAt(cx * 16 + x, cz * 16 + z));
                    for (int bound = 2; bound <= 5; bound++) expected.nextInt(bound);
                }
            }
        }
    }

    @Test
    public void mapCallbacksReceiveAllPositiveCenterWeightsInRegistryOrder() throws Exception {
        SurfaceSamplerFixture.Manager manager = new SurfaceSamplerFixture.Manager(47, false);
        SurfaceBiomeSampler sampler = SurfaceSamplerFixture.current(manager);
        java.lang.reflect.Field effects = sampler.getClass().getDeclaredField("surfaceEffects");
        effects.setAccessible(true);
        effects.set(sampler, new RwgSurfaceEffects(type -> null)); // Exercise the full-replay fallback.
        java.lang.reflect.Method chunkMethod = sampler.getClass().getDeclaredMethod("getBlendChunk", int.class, int.class);
        chunkMethod.setAccessible(true);
        Object chunk = chunkMethod.invoke(sampler, -2, 1);
        java.lang.reflect.Field ids = chunk.getClass().getDeclaredField("biomeIds"); ids.setAccessible(true);
        java.lang.reflect.Field weights = chunk.getClass().getDeclaredField("weights"); weights.setAccessible(true);
        int[] order = (int[]) ids.get(chunk);
        float[] values = (float[]) weights.get(chunk);
        SurfaceSamplerFixture.mapOrder.clear();
        sampler.getPredictedBiomeAt(-32, 16);
        java.util.List<Integer> expected = new java.util.ArrayList<>();
        for (int i = 0; i < order.length; i++) if (values[312 * order.length + i] > 0) expected.add(order[i]);
        assertEquals(expected, SurfaceSamplerFixture.mapOrder);
    }

    @Test
    public void failedCallbackDoesNotCacheAPartiallyGeneratedChunk() throws Exception {
        CallbackBiome biome = new CallbackBiome(); biome.mode = 4;
        SurfaceBiomeSampler sampler = sampler(biome);
        try {
            sampler.getPredictedBiomeAt(2, 3);
            fail("Expected callback failure");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("2,3"));
        }
        biome.mode = 1;
        assertSame(BiomeGenBase.icePlains, sampler.getPredictedBiomeAt(0, 0));
        assertEquals(2, biome.maps);
    }

    private static SurfaceBiomeSampler sampler(CallbackBiome biome) throws Exception {
        return SurfaceSamplerFixture.current(new SurfaceSamplerFixture.Manager(47, true) {
            @Override public SurfaceSamplerFixture.RealisticBiome getBiomeDataAt(int x, int z) { return biome; }
            @Override public float getRiverStrength(int x, int z) { return -1.0F; }
        });
    }

    public static class CallbackBiome extends SurfaceSamplerFixture.RealisticBiome {
        private static final Block MARKER = new Block(net.minecraft.block.material.Material.rock) {};
        int mode, maps, replacements, riverInputs;
        boolean mapBeforeSurface;
        CallbackBiome() { super(203, BiomeGenBase.forest); }
        @Override public void generateMapGen(Block[] blocks, byte[] metadata, Long seed, World world,
                SurfaceSamplerFixture.Manager manager, Random random, int cx, int cz,
                SurfaceSamplerFixture.Noise noise, Object cell, float[] heights) {
            maps++;
            // A previous callback must not have modified a reusable template.
            assertNotSame(MARKER, blocks[200]);
            blocks[200] = MARKER;
            // Simulate a registered terrain modifier, then require rReplace to
            // observe that change. Heights and metadata use X-major indexing.
            heights[9 * 16 + 5] = 123.25F;
            Arrays.fill(metadata, (byte) 37);
        }
        @Override public void rReplace(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x,
                int depth, World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) {
            replacements++;
            assertEquals(Math.floorMod(bx, 16), x);
            assertEquals(Math.floorMod(bz, 16), z);
            assertEquals(-1, depth);
            assertEquals(123.25F, heights[9 * 16 + 5], 0);
            assertEquals(37, metadata[(x * 16 + z) * 256 + 200]);
            mapBeforeSurface = true;
            if (biomes[z * 16 + x] == BiomeGenBase.river) riverInputs++;
            if (mode == 4) throw new IllegalStateException("Deliberate painter failure");
            if (mode == 1) {
                biomes[z * 16 + x] = BiomeGenBase.icePlains;
                if (x == 15 && z == 15) biomes[255] = BiomeGenBase.mushroomIsland;
            } else if (mode == 2) {
                assertSame(BiomeGenBase.river, biomes[z * 16 + x]);
                biomes[z * 16 + x] = BiomeGenBase.extremeHills;
            } else if (mode == 3) {
                biomes[z * 16 + x] = random.nextBoolean() ? BiomeGenBase.jungle : BiomeGenBase.taiga;
            }
        }
    }
}
