package amidst.gtnh.worker;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reuses the overlapping 21x21 biome blend inputs of adjacent native terrain chunks. */
final class Rwg2BiomeCache {
    private final Object manager;
    private final Method sample;
    private final boolean enabled, compact;
    private static final int SETS = 8192, WAYS = 4;
    private final long[] keys = new long[SETS * WAYS];
    private final Object[] values = new Object[SETS * WAYS];
    private final byte[] victims = new byte[SETS];
    private final Map<Long, Object> biomes =
            new LinkedHashMap<Long, Object>(1024, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Object> entry) {
                    return size() > 32768;
                }
            };
    long hits, misses;
    // Kept per exclusively leased sampler; never shares mutable state with the game.
    boolean optimizeMountainInfluence = true;
    private Rwg2MountainInfluence mountainInfluence;
    private boolean[] mountainTypes;

    float mountainInfluence(int[] biomes, int x, int z) throws ReflectiveOperationException {
        if (mountainInfluence == null) {
            Class<?> mountain =
                    Class.forName("rwg2.biomes.realistic.land.RealisticBiomeMountainChain");
            Method get =
                    Class.forName("rwg2.biomes.realistic.RealisticBiomeBase")
                            .getMethod("getBiome", int.class);
            mountainTypes = new boolean[256];
            for (int i = 0; i < mountainTypes.length; i++)
                mountainTypes[i] = mountain.isInstance(get.invoke(null, i));
            mountainInfluence = new Rwg2MountainInfluence();
        }
        // The pinned native column loop always starts at (0,0), even for stride overloads.
        if (x == 0 && z == 0) mountainInfluence.begin(biomes, mountainTypes);
        return mountainInfluence.at(x, z);
    }

    Rwg2BiomeCache(Object manager, boolean enabled) throws NoSuchMethodException {
        this(manager, enabled, true);
    }

    Rwg2BiomeCache(Object manager, boolean enabled, boolean compact) throws NoSuchMethodException {
        this.compact = compact;
        this.manager = manager;
        this.enabled = enabled;
        sample = manager.getClass().getMethod("getBiomeDataAt", int.class, int.class);
    }

    private static int hash(long key) {
        key ^= key >>> 33;
        key *= 0xff51afd7ed558ccdL;
        key ^= key >>> 33;
        key *= 0xc4ceb9fe1a85ec53L;
        return (int) (key ^ (key >>> 33));
    }

    Object sample(Object context, int x, int z) throws ReflectiveOperationException {
        if (context != manager) throw new IllegalStateException("RWG2 biome cache context changed");
        long key = (long) x << 32 ^ z & 0xffffffffL;
        int set = hash(key) & (SETS - 1), start = set * WAYS;
        Object result = null;
        if (enabled) {
            if (!compact) result = biomes.get(key);
            else
                for (int slot = start; slot < start + WAYS; slot++) {
                    if (values[slot] != null && keys[slot] == key) {
                        result = values[slot];
                        break;
                    }
                }
        }
        if (result != null) {
            hits++;
            return result;
        }
        misses++;
        result = sample.invoke(manager, x, z);
        if (enabled) {
            if (!compact) biomes.put(key, result);
            else {
                int target = start + (victims[set]++ & (WAYS - 1));
                for (int slot = start; slot < start + WAYS; slot++)
                    if (values[slot] == null) {
                        target = slot;
                        break;
                    }
                keys[target] = key;
                values[target] = result;
            }
        }
        return result;
    }
}
