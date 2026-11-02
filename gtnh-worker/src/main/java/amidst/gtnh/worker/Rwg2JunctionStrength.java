package amidst.gtnh.worker;

import java.lang.reflect.Method;

/** RWG2 2.0.6 CellNoise junction geometry, with exact feature/axis reuse and two square roots. */
final class Rwg2JunctionStrength {
    private static final double SQRT_2 = 1.4142135623730950488;
    private final Object noise;
    private final Method noise1;
    private final long seed, seedOffset;
    private final int[] axes = new int[2048];
    private final float[] warps = new float[2048];
    private final boolean[] axisPresent = new boolean[2048];
    private final long[] cells = new long[16];
    private final boolean[] cellPresent = new boolean[16];
    private final double[][] pointsX = new double[16][25], pointsZ = new double[16][25];
    long warpHits, warpMisses, cellHits, cellMisses;

    Rwg2JunctionStrength(Object manager) throws Exception {
        Class<?> type = Class.forName("rwg2.util.CellNoise");
        Rwg2Runtime.verify(
                Rwg2Runtime.bytes(type),
                "ef9239cc22a8dc6fbb6c37e52a90c077965c609620c96ab890c8dae39f35795d");
        noise = Rwg2Runtime.get(manager, "perlin");
        noise1 = noise.getClass().getMethod("noise1", float.class);
        Object cell = Rwg2Runtime.get(manager, "cell");
        seed = (Long) Rwg2Runtime.get(cell, "seed");
        seedOffset = (Long) Rwg2Runtime.get(cell, "seedOffset");
    }

    private float warp(int axis) {
        int slot = axis & (axes.length - 1);
        if (axisPresent[slot] && axes[slot] == axis) {
            warpHits++;
            return warps[slot];
        }
        try {
            float value = (Float) noise1.invoke(noise, axis / 240f);
            axes[slot] = axis;
            warps[slot] = value;
            axisPresent[slot] = true;
            warpMisses++;
            return value;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot sample RWG2 river warp", e);
        }
    }

    float at(int x, int z) {
        float warpedX = x + warp(z) * 220f, warpedZ = z + warp(x) * 220f;
        double px = warpedX / 1250D, pz = warpedZ / 1250D;
        // Preserve native truncation at negative integer/zero boundaries (not Math.floor).
        int cx = px > 0D ? (int) px : (int) px - 1;
        int cz = pz > 0D ? (int) pz : (int) pz - 1;
        long key = (long) cx << 32 ^ cz & 0xffffffffL;
        int slot = (cx * 31 + cz) & 15;
        double[] xs = pointsX[slot], zs = pointsZ[slot];
        if (!cellPresent[slot] || cells[slot] != key) {
            int i = 0;
            for (int dz = cz - 2; dz <= cz + 2; dz++)
                for (int dx = cx - 2; dx <= cx + 2; dx++) {
                    xs[i] = dx + valueNoise(dx, dz, seed);
                    zs[i++] = dz + valueNoise(dx, dz, seedOffset);
                }
            cells[slot] = key;
            cellPresent[slot] = true;
            cellMisses++;
        } else cellHits++;
        double first = Double.POSITIVE_INFINITY, second = first, third = first;
        for (int i = 0; i < 25; i++) {
            double dx = xs[i] - px, dz = zs[i] - pz, squared = dx * dx + dz * dz;
            if (squared < first) {
                third = second;
                second = first;
                first = squared;
            } else if (squared < second) {
                third = second;
                second = squared;
            } else if (squared < third) third = squared;
        }
        // sqrt(d)/SQRT_2 is monotone: its 1st/3rd order statistics equal transforming
        // the 1st/3rd squared distances. Evaluate the identical native arithmetic only twice.
        double difference = Math.sqrt(third) / SQRT_2 - Math.sqrt(first) / SQRT_2;
        return -(difference < 60D / 1250D ? ((float) (difference / (60D / 1250D)) - 1f) : 0f);
    }

    // Native GPL CellNoise valueNoise2D; retain int overflow before adding the long seed.
    private static double valueNoise(int x, int z, long seed) {
        long n = (1619 * x + 6971 * z + 1013 * seed) & 0x7fffffff;
        n = (n >> 13) ^ n;
        return 1.0
                - ((double) ((n * (n * n * 60493 + 19990303) + 1376312589) & 0x7fffffff)
                        / 1073741824.0);
    }
}
