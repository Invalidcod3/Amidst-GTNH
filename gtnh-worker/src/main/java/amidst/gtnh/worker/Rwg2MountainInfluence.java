package amidst.gtnh.worker;

import java.util.Arrays;

/** Exact nearest mountain sample for RWG2 2.0.6's fixed 21x21 blend grid. */
final class Rwg2MountainInfluence {
    private static final int COLUMNS = 256, SAMPLES = 21 * 21;
    private static final int[][] NEAREST = new int[COLUMNS][];
    private static final float[][] WEIGHTS = new float[COLUMNS][];

    static {
        for (int x = 0; x < 16; x++)
            for (int z = 0; z < 16; z++) {
                final int localX = x, localZ = z;
                Integer[] order = new Integer[SAMPLES];
                int count = 0;
                for (int sample = 0; sample < SAMPLES; sample++)
                    if (distanceSquared(sample, x, z) < 48 * 48) order[count++] = sample;
                Arrays.sort(
                        order,
                        0,
                        count,
                        (a, b) ->
                                Integer.compare(
                                        distanceSquared(a, localX, localZ),
                                        distanceSquared(b, localX, localZ)));
                int column = x * 16 + z;
                NEAREST[column] = new int[count];
                WEIGHTS[column] = new float[count];
                for (int i = 0; i < count; i++) {
                    NEAREST[column][i] = order[i];
                    // Preserve the native float operations, including the cast before division.
                    WEIGHTS[column][i] =
                            1f - (float) Math.sqrt(distanceSquared(order[i], x, z)) / 48f;
                }
            }
    }

    private static int distanceSquared(int sample, int x, int z) {
        int dx = (sample / 21 - 8) * 8 - 8 - x;
        int dz = (sample % 21 - 8) * 8 - 8 - z;
        return dx * dx + dz * dz;
    }

    private final boolean[] mountains = new boolean[SAMPLES];
    private boolean any;
    long lookups;

    void begin(int[] biomes, boolean[] mountainTypes) {
        if (biomes.length != SAMPLES)
            throw new IllegalArgumentException("Unsupported RWG2 blend grid");
        any = false;
        for (int i = 0; i < SAMPLES; i++) {
            mountains[i] = mountainTypes[biomes[i]];
            any |= mountains[i];
        }
    }

    float at(int x, int z) {
        if (!any) return 0f;
        int column = x * 16 + z;
        int[] candidates = NEAREST[column];
        for (int i = 0; i < candidates.length; i++) {
            lookups++;
            if (mountains[candidates[i]]) return WEIGHTS[column][i];
        }
        return 0f;
    }
}
