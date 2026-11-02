package amidst.gtnh.worker;

import java.util.concurrent.CancellationException;

/** Exact 2.0.6 LandmarkDecorations hill climb; preserve all probes and their order. */
final class Rwg2JunctionLocator {
    interface Terrain {
        float strength(int x, int z);

        boolean mountain(int x, int z);

        boolean lava(int x, int z);
    }

    private final Terrain terrain;

    Rwg2JunctionLocator(Terrain terrain) {
        this.terrain = terrain;
    }

    private float strength(int x, int z) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        return terrain.strength(x, z);
    }

    int[] find(int chunkX, int chunkZ) {
        int bestX = chunkX + 8, bestZ = chunkZ + 8;
        float best = -1f;
        for (int x = chunkX; x < chunkX + 16; x += 2)
            for (int z = chunkZ; z < chunkZ + 16; z += 2) {
                float value = strength(x, z);
                if (value > best) {
                    best = value;
                    bestX = x;
                    bestZ = z;
                }
            }
        if (best < .35f) return null;
        for (int step = 4; step >= 1; step /= 2) {
            boolean moved;
            do {
                moved = false;
                for (int dx = -step; dx <= step; dx += step)
                    for (int dz = -step; dz <= step; dz += step) {
                        int x = bestX + dx, z = bestZ + dz;
                        float value = strength(x, z);
                        if (value > best) {
                            best = value;
                            bestX = x;
                            bestZ = z;
                            moved = true;
                        }
                    }
            } while (moved);
        }
        if (best < .82f || !mountainNearby(bestX, bestZ) || terrain.lava(bestX, bestZ)) return null;
        return new int[] {bestX, bestZ};
    }

    private boolean mountainNearby(int x, int z) {
        for (int dx = -24; dx <= 24; dx += 12)
            for (int dz = -24; dz <= 24; dz += 12)
                if (terrain.mountain(x + dx, z + dz)) return true;
        return false;
    }
}
