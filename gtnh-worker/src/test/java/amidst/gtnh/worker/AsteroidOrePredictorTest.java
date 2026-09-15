package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Random;

public class AsteroidOrePredictorTest {
    private static final Object STONE = new Object(), VEIN = new Object(), SMALL = new Object();

    private static AsteroidOrePredictor.Config config(int probability) {
        return new AsteroidOrePredictor.Config(true, probability, 48, 180, 3, 15);
    }

    private static AsteroidOrePredictor.Registry registry(final boolean consumeClones) {
        return new AsteroidOrePredictor.Registry() {
            public Object stone(Random random) {
                if (consumeClones) for (int i = 0; i < 500; i++) random.nextInt(117);
                return STONE;
            }

            public Object ore(Object stone, boolean small, Random random) {
                assertSame(STONE, stone);
                if (consumeClones) for (int i = 0; i < 300; i++) random.nextInt(37);
                return small ? SMALL : VEIN;
            }
        };
    }

    @Test
    public void matchesOfficialSourceVectorsAcrossDimensionsAndNegativeChunks() throws Exception {
        // Generated with the compiled upstream XSTR.java at GT5-Unofficial/5.09.54.133,
        // following the RNG prefix of WorldGeneratorSpace.AsteroidGenerator.forChunk.
        // seed, dimension, chunkX, chunkZ, chance roll, small branch, x, y, z, radius.
        long[][] vectors = {
            {0, -30, 0, 0, 86, 0, 14, 63, 8, 13},
            {0, 25, -17, 22, 65, 1, -258, 61, 364, 6},
            {0, 1, 321, -321, 31, 0, 5143, 174, -5121, 14},
            {0, 200, -512, -1025, 19, 0, -8187, 167, -16387, 4},
            {1, 25, -17, 22, 70, 1, -259, 176, 365, 7},
            {-1, 1, 321, -321, 26, 0, 5142, 71, -5122, 14},
            {Long.MIN_VALUE, -30, 0, 0, 30, 1, 14, 172, 10, 3},
            {Long.MIN_VALUE, 1, 321, -321, 75, 1, 5143, 167, -5123, 14},
            {123456789012345L, 1, 321, -321, 62, 0, 5142, 77, -5136, 14},
            {123456789012345L, 200, -512, -1025, 55, 1, -8182, 156, -16395, 15}
        };
        for (long[] v : vectors)
            for (boolean consume : new boolean[] {false, true}) {
                AsteroidOrePredictor.Candidate c =
                        AsteroidOrePredictor.select(
                                v[0],
                                (int) v[1],
                                (int) v[2],
                                (int) v[3],
                                config((int) v[4]),
                                registry(consume));
                assertNotNull(c);
                assertEquals(v[5] == 1, c.small);
                assertSame(c.small ? SMALL : VEIN, c.layer);
                assertEquals(v[6], c.x);
                assertEquals(v[7], c.y);
                assertEquals(v[8], c.z);
                assertEquals(v[9], c.radius);
                assertNull(
                        AsteroidOrePredictor.select(
                                v[0],
                                (int) v[1],
                                (int) v[2],
                                (int) v[3],
                                config((int) v[4] - 1),
                                registry(consume)));
            }
    }

    @Test
    public void zeroProbabilityKeepsUpstreamInclusiveZeroRoll() throws Exception {
        AsteroidOrePredictor.Candidate c =
                AsteroidOrePredictor.select(-588283L, 0, 0, 0, config(0), registry(false));
        assertNotNull(c);
        assertTrue(c.small);
        assertEquals(0, c.x);
        assertEquals(48, c.y);
        assertEquals(0, c.z);
    }

    @Test
    public void everyChunkCanSeedAnAsteroidWithoutAnOreGridGate() throws Exception {
        for (int z = -5; z <= 5; z++)
            for (int x = -5; x <= 5; x++) {
                AsteroidOrePredictor.Candidate c =
                        AsteroidOrePredictor.select(7, -30, x, z, config(100), registry(true));
                assertNotNull(c);
                assertEquals(x, Math.floorDiv(c.x, 16));
                assertEquals(z, Math.floorDiv(c.z, 16));
                assertTrue(c.y >= 48 && c.y < 180);
                assertTrue(c.radius >= 3 && c.radius <= 15);
            }
    }

    @Test
    public void disabledOrMissingRegistryEntriesNeverInventAnAsteroid() throws Exception {
        AsteroidOrePredictor.Registry missingStone =
                new AsteroidOrePredictor.Registry() {
                    public Object stone(Random random) {
                        return null;
                    }

                    public Object ore(Object stone, boolean small, Random random) {
                        throw new AssertionError();
                    }
                };
        assertNull(AsteroidOrePredictor.select(0, 0, 0, 0, config(100), missingStone));
        assertNull(
                AsteroidOrePredictor.select(
                        0,
                        0,
                        0,
                        0,
                        new AsteroidOrePredictor.Config(false, 100, 48, 180, 3, 15),
                        null));
        assertNull(
                AsteroidOrePredictor.select(
                        0,
                        0,
                        0,
                        0,
                        config(100),
                        new AsteroidOrePredictor.Registry() {
                            public Object stone(Random random) {
                                return STONE;
                            }

                            public Object ore(Object stone, boolean small, Random random) {
                                return null;
                            }
                        }));
    }
}
