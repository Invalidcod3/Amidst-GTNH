package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.world.ChunkPosition;
import net.minecraft.world.biome.BiomeGenBase;

import org.junit.Test;

import java.util.Random;

public class SpawnSearchCacheTest {
    @Test
    public void pollingRetainsSearchProgressAndSeparatesProviders() {
        final int[] created = {0};
        SpawnSearchCache cache =
                new SpawnSearchCache(
                        seed -> {
                            created[0]++;
                            return new Sampler();
                        });
        SpawnSearch.Job search = cache.get(123, SpawnSearch.BOP);
        assertFalse(search.advance(0));
        assertEquals(1, search.moves);
        assertSame(search, cache.get(123, SpawnSearch.BOP));
        assertEquals(1, created[0]);
        assertNotSame(search, cache.get(123, SpawnSearch.VANILLA));
        assertEquals(2, created[0]);
        assertTrue(search.advance(Long.MAX_VALUE));
        assertSame(search, cache.get(123, SpawnSearch.BOP));
        assertTrue(cache.get(123, SpawnSearch.BOP).done);
    }

    @Test
    public void evictsLeastRecentlyUsedSearchButKeepsRecentlyPolledSeed() {
        SpawnSearchCache cache = new SpawnSearchCache(seed -> new Sampler());
        SpawnSearch.Job first = cache.get(1, SpawnSearch.BOP);
        SpawnSearch.Job second = cache.get(2, SpawnSearch.BOP);
        cache.get(3, SpawnSearch.BOP);
        cache.get(4, SpawnSearch.BOP);
        assertSame(first, cache.get(1, SpawnSearch.BOP));
        cache.get(5, SpawnSearch.BOP);
        assertSame(first, cache.get(1, SpawnSearch.BOP));
        assertNotSame(second, cache.get(2, SpawnSearch.BOP));
    }

    private static class Sampler implements SurfaceBiomeSampler {
        @Override
        public ChunkPosition findSpawnBiomePosition(Random random) {
            return null;
        }

        @Override
        public SpawnSearch.Surface getSpawnSurface(int x, int z) {
            return new SpawnSearch.Surface(null, null, 63, false);
        }

        @Override
        public BiomeGenBase getBiomeAt(int x, int z) {
            throw new AssertionError();
        }

        @Override
        public boolean isVillageLocationViable(int x, int z) {
            throw new AssertionError();
        }

        @Override
        public float getApproximateSurfaceHeight(int x, int z) {
            throw new AssertionError();
        }
    }
}
