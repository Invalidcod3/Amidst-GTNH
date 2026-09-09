package amidst.gtnh.worker;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Retains pure predictions beyond the smaller terrain/blend working set.
 * Loaded chunk truth is checked on EVERY request and never stored as a seed
 * prediction. Chunk loads, unloads and in-place biome edits therefore cannot
 * turn cached predictions into stale authoritative data.
 * All access belongs to the existing Worker query thread.
 */
final class OverworldTileCache {
    interface LoadedBiome { int getOrMissing(int x, int z); }
    private Object worldContext, managerContext;
    private final Map<Key, int[]> predictions;

    OverworldTileCache(int limit) {
        predictions = new LinkedHashMap<Key, int[]>(16, 0.75F, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Key, int[]> eldest) { return size() > limit; }
        };
    }

    int[] sample(Object world, Object manager, int x, int z, int width, int height, int step,
            LoadedBiome loaded, IntBinaryOperator predict) {
        Sampling sampling = begin(world, manager, x, z, width, height, step, loaded, predict);
        sampling.advance(Long.MAX_VALUE);
        return sampling.result;
    }

    Sampling begin(Object world, Object manager, int x, int z, int width, int height, int step,
            LoadedBiome loaded, IntBinaryOperator predict) {
        if (worldContext != world || managerContext != manager) {
            predictions.clear();
            worldContext = world;
            managerContext = manager;
        }
        Key key = new Key(x, z, width, height, step);
        int[] cached = predictions.get(key);
        if (cached == null) {
            cached = new int[width * height];
            Arrays.fill(cached, -1);
            predictions.put(key, cached);
        }
        return new Sampling(cached, x, z, width, step, loaded, predict);
    }

    /** Resumes at the next pixel; neither cached nor completed live samples are redone. */
    static final class Sampling {
        private final int[] cached;
        final int[] result;
        private final int x, z, width, step, offset;
        private final LoadedBiome loaded;
        private final IntBinaryOperator predict;
        private int index;

        Sampling(int[] cached, int x, int z, int width, int step, LoadedBiome loaded, IntBinaryOperator predict) {
            this.cached = cached;
            this.result = new int[cached.length];
            this.x = x; this.z = z; this.width = width; this.step = step;
            this.offset = step == 4 ? 2 : 0;
            this.loaded = loaded; this.predict = predict;
        }

        boolean advance(long deadline) {
            while (index < result.length) {
                if (System.nanoTime() >= deadline) return false;
                int bx = x + (index % width) * step + offset;
                int bz = z + (index / width) * step + offset;
                int actual = loaded.getOrMissing(bx, bz);
                if (actual >= 0) {
                    result[index] = actual;
                } else {
                    if (cached[index] < 0) cached[index] = predict.applyAsInt(bx, bz);
                    result[index] = cached[index];
                }
                index++;
            }
            return true;
        }
    }

    void clear() {
        predictions.clear();
        worldContext = null;
        managerContext = null;
    }

    private static final class Key {
        private final int[] values;
        Key(int x, int z, int width, int height, int step) { values = new int[] { x, z, width, height, step }; }
        @Override public int hashCode() { return Arrays.hashCode(values); }
        @Override public boolean equals(Object other) {
            return other instanceof Key && Arrays.equals(values, ((Key) other).values);
        }
    }
}
