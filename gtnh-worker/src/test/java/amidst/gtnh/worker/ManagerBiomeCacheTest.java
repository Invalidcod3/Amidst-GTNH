package amidst.gtnh.worker;

import static org.junit.Assert.*;
import org.junit.Test;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

public class ManagerBiomeCacheTest {
    @Test public void nativeFinalLayerMatchesPointLookupAcrossSeedsEdgesAndEviction() {
        for (long seed : new long[] {0, -19431, 2777967236474336022L}) {
            WorldChunkManager reference = new WorldChunkManager(seed, WorldType.DEFAULT);
            ManagerBiomeCache actual = new ManagerBiomeCache(new WorldChunkManager(seed, WorldType.DEFAULT));
            for (int step : new int[] {1, 4}) {
                for (int z = -133; z < 135; z += step) for (int x = -139; x < 131; x += step) {
                    assertSame(reference.getBiomeGenAt(x, z), actual.getBiomeAt(x, z));
                }
            }
            for (int page = 0; page < 80; page++) {
                int x = page * 128 - 14000, z = 6000 - page * 64;
                assertSame(reference.getBiomeGenAt(x, z), actual.getBiomeAt(x, z));
            }
            assertSame(reference.getBiomeGenAt(-1, -65), actual.getBiomeAt(-1, -65));
        }
    }

    @Test public void unknownPointOverridesAreNotReplacedByTheBulkLayer() {
        WorldChunkManager custom = new WorldChunkManager(1L, WorldType.DEFAULT) {
            @Override public BiomeGenBase getBiomeGenAt(int x, int z) { return BiomeGenBase.hell; }
            @Override public BiomeGenBase[] getBiomeGenAt(BiomeGenBase[] out, int x, int z, int w, int h, boolean cached) {
                throw new AssertionError("Unknown bulk implementation must not be assumed equivalent");
            }
        };
        ManagerBiomeCache cache = new ManagerBiomeCache(custom);
        assertSame(BiomeGenBase.hell, cache.getBiomeAt(-65, 127));
        assertSame(BiomeGenBase.hell, cache.getBiomeAt(-65, 127));
    }
}
