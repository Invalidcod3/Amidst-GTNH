package amidst.gtnh.worker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.junit.Test;

public class SurfaceBiomeSamplerTest {
    @Test
    public void preservesV02BiomeResultsForNewAreasAndRepeatedQueries() throws Exception {
        for (long seed : new long[] { 0L, -124987L, 9876543210123L }) {
            for (boolean uniform : new boolean[] { true, false }) {
                SurfaceBiomeSampler expected = SurfaceSamplerFixture.reference(new SurfaceSamplerFixture.Manager(seed, uniform));
                SurfaceBiomeSampler actual = SurfaceSamplerFixture.current(new SurfaceSamplerFixture.Manager(seed, uniform));
                for (int pass = 0; pass < 2; pass++) {
                    for (int z = -128; z < 128; z += 4) {
                        for (int x = -128; x < 128; x += 4) {
                            assertEquals(expected.getBiomeAt(x + 2, z + 2).biomeID, actual.getBiomeAt(x + 2, z + 2).biomeID);
                        }
                    }
                }
                for (int z = -17; z <= 17; z++) {
                    for (int x = -17; x <= 17; x++) {
                        assertEquals(expected.getBiomeAt(x, z).biomeID, actual.getBiomeAt(x, z).biomeID);
                    }
                }
            }
        }
    }

    @Test
    public void preservesEveryInterpolationWeightAcrossAdjacentChunks() throws Exception {
        SurfaceBiomeSampler expected = SurfaceSamplerFixture.reference(new SurfaceSamplerFixture.Manager(4567, false));
        SurfaceBiomeSampler actual = SurfaceSamplerFixture.current(new SurfaceSamplerFixture.Manager(4567, false));
        for (int chunkZ = -5; chunkZ < 6; chunkZ++) {
            for (int chunkX = -5; chunkX < 6; chunkX++) {
                Object oldChunk = blendChunk(expected, chunkX, chunkZ);
                Object newChunk = blendChunk(actual, chunkX, chunkZ);
                assertArrayEquals((int[]) field(oldChunk, "biomeIds"), (int[]) field(newChunk, "biomeIds"));
                assertArrayEquals((float[]) field(oldChunk, "weights"), (float[]) field(newChunk, "weights"), 0.0F);
            }
        }
    }

    @Test
    public void preservesResultsAfterCacheEvictionAndAtLargeCoordinates() throws Exception {
        SurfaceBiomeSampler expected = SurfaceSamplerFixture.reference(new SurfaceSamplerFixture.Manager(47, false));
        SurfaceBiomeSampler actual = SurfaceSamplerFixture.current(new SurfaceSamplerFixture.Manager(47, false));
        for (int i = 0; i < 2300; i++) {
            int x = i * 128 - 150000;
            int z = i * -80 + 60000;
            assertEquals(expected.getBiomeAt(x, z).biomeID, actual.getBiomeAt(x, z).biomeID);
        }
        for (int x : new int[] { -150000, 0, 29999970, -29999970 }) {
            assertEquals(expected.getBiomeAt(x, -x).biomeID, actual.getBiomeAt(x, -x).biomeID);
        }
    }

    private static Object blendChunk(SurfaceBiomeSampler sampler, int x, int z) throws Exception {
        Method method = sampler.getClass().getDeclaredMethod("getBlendChunk", int.class, int.class);
        method.setAccessible(true);
        return method.invoke(sampler, x, z);
    }

    private static Object field(Object instance, String name) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
