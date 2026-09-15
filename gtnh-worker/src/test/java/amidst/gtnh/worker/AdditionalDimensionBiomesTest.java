package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class AdditionalDimensionBiomesTest {
    @Test
    public void singleBiomeWorldsKeepTheirOwnIdsAndConfiguredDimension() {
        Map<String, BiomeGenBase[]> sources = new LinkedHashMap<>();
        for (String key : AdditionalDimensionBiomes.keys())
            sources.put(key, new BiomeGenBase[] {BiomeGenBase.desert});
        AtomicInteger dimension = new AtomicInteger();
        Set<Integer> ids = new HashSet<>();
        try (AdditionalDimensionBiomes sampler =
                new AdditionalDimensionBiomes(
                        () -> sources,
                        seed -> {
                            throw new AssertionError(
                                    "A single-biome world must not create a manager");
                        },
                        id -> {
                            dimension.set(id);
                            return null;
                        })) {
            for (String key : AdditionalDimensionBiomes.keys()) {
                if ("Everglades".equals(key)) continue;
                int[] actual = sampler.sample(-9123, 8765, key, -257, 127, 5, 3, 4);
                assertEquals(8765, dimension.get());
                assertTrue(ids.add(actual[0]));
                for (int id : actual)
                    assertEquals(
                            AdditionalDimensionBiomes.displayId(key, BiomeGenBase.desert.biomeID),
                            id);
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> sampler.sample(1, 0, "Unknown", 0, 0, 1, 1, 1));
        }
    }

    @Test
    public void seededManagerUsesFinalBlockCoordinatesAcrossNegativeEdgesAndEviction() {
        List<Long> created = new ArrayList<>();
        AtomicInteger cleaned = new AtomicInteger();
        try (AdditionalDimensionBiomes sampler =
                new AdditionalDimensionBiomes(
                        () ->
                                Collections.singletonMap(
                                        "Everglades",
                                        new BiomeGenBase[] {
                                            BiomeGenBase.desert, BiomeGenBase.swampland
                                        }),
                        seed -> {
                            created.add(seed);
                            return new WorldChunkManager(seed, WorldType.LARGE_BIOMES) {
                                @Override
                                public BiomeGenBase getBiomeGenAt(int x, int z) {
                                    return expected(seed, x, z);
                                }

                                @Override
                                public void cleanupCache() {
                                    cleaned.incrementAndGet();
                                }
                            };
                        },
                        id -> null)) {
            for (long seed = -3; seed <= 6; seed++) {
                for (int step : new int[] {1, 4}) {
                    int[] actual = sampler.sample(seed, 73, "Everglades", -129, -65, 67, 35, step);
                    for (int row = 0; row < 35; row++)
                        for (int col = 0; col < 67; col++) {
                            assertEquals(
                                    AdditionalDimensionBiomes.displayId(
                                            "Everglades",
                                            expected(seed, -129 + col * step, -65 + row * step)
                                                    .biomeID),
                                    actual[row * 67 + col]);
                        }
                }
            }
            assertEquals(10, created.size()); // Both resolutions reuse the same seeded manager.
            assertEquals(2, cleaned.get());
            sampler.sample(-3, 73, "Everglades", -1, -1, 1, 1, 1);
            assertEquals(11, created.size());
        }
        assertEquals(11, cleaned.get());
    }

    private static BiomeGenBase expected(long seed, int x, int z) {
        return Math.floorMod(seed + x * 17L + z * 31L, 7) < 3
                ? BiomeGenBase.desert
                : BiomeGenBase.swampland;
    }

    @Test
    public void everyRawIdFitsTheFragmentStorageWithoutScopeCollisions() {
        Set<Integer> ids = new HashSet<>();
        for (String key : AdditionalDimensionBiomes.keys())
            for (int raw = 0; raw < 256; raw++) {
                int id = AdditionalDimensionBiomes.displayId(key, raw);
                assertTrue(id >= 8192 && id <= Short.MAX_VALUE);
                assertTrue(ids.add(id));
            }
        assertEquals(24 * 256, ids.size());
        assertFalse(AdditionalDimensionBiomes.supports("Overworld"));
        assertFalse(AdditionalDimensionBiomes.supports(null));
        assertEquals(
                BiomeGenBase.forest.biomeID,
                AdditionalDimensionBiomes.sampledDisplayId(
                        "Venus",
                        new BiomeGenBase[] {BiomeGenBase.desert},
                        BiomeGenBase.forest.biomeID));
    }
}
