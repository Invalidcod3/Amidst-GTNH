package amidst.gtnh.worker;

import net.minecraft.world.ChunkPosition;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.LongFunction;

/**
 * Game-thread-owned searches for independent seeds. Retaining a job across socket timeouts
 * preserves its random walk; completed jobs also avoid replaying the same terrain on every poll.
 * Configuration is fixed for the game process. Loaded-world spawn records bypass this cache.
 */
final class SpawnSearchCache {
    private static final int MAX_SEARCHES = 4;
    private final LongFunction<SurfaceBiomeSampler> samplers;
    private final Map<String, SpawnSearch.Job> searches =
            new LinkedHashMap<String, SpawnSearch.Job>(MAX_SEARCHES, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, SpawnSearch.Job> entry) {
                    return size() > MAX_SEARCHES;
                }
            };

    SpawnSearchCache(LongFunction<SurfaceBiomeSampler> samplers) {
        this.samplers = samplers;
    }

    SpawnSearch.Job get(long seed, String provider) {
        String key = seed + ":" + provider;
        SpawnSearch.Job search = searches.get(key);
        if (search == null) {
            SurfaceBiomeSampler sampler = samplers.apply(seed);
            search =
                    new SpawnSearch.Job(
                            seed,
                            provider,
                            new SpawnSearch.Terrain() {
                                @Override
                                public ChunkPosition initial(Random random) {
                                    return sampler.findSpawnBiomePosition(random);
                                }

                                @Override
                                public SpawnSearch.Surface surface(int x, int z) {
                                    return sampler.getSpawnSurface(x, z);
                                }
                            });
            searches.put(key, search);
        }
        return search;
    }
}
