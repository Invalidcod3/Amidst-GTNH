package amidst.gtnh.worker;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/** Final block-resolution biomes, owned by one seeded manager on the server thread. */
final class ManagerBiomeCache {
    private static final int PAGE_BITS = 6;
    private static final int PAGE_SIZE = 1 << PAGE_BITS;
    private final WorldChunkManager manager;
    private final boolean bulk;
    private final Map<Long, BiomeGenBase[]> pages = new LinkedHashMap<Long, BiomeGenBase[]>(64, .75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, BiomeGenBase[]> entry) {
            return size() > 64;
        }
    };
    private BiomeGenBase[] current;
    private int currentX, currentZ;

    ManagerBiomeCache(WorldChunkManager manager) {
        this(manager, supportsBulk(manager));
    }

    ManagerBiomeCache(WorldChunkManager manager, boolean bulk) {
        this.manager = manager;
        this.bulk = bulk;
    }

    BiomeGenBase getBiomeAt(int x, int z) {
        int px = x >> PAGE_BITS, pz = z >> PAGE_BITS;
        if (current == null || px != currentX || pz != currentZ) {
            long key = ((long) px << 32) ^ (pz & 0xffffffffL);
            current = pages.get(key);
            if (current == null) {
                // false avoids recursive native BiomeCache access. This is the FINAL
                // layer, never getBiomesForGeneration (the unzoomed generation layer).
                current = bulk ? manager.getBiomeGenAt(null, px << PAGE_BITS, pz << PAGE_BITS,
                        PAGE_SIZE, PAGE_SIZE, false).clone() : new BiomeGenBase[PAGE_SIZE * PAGE_SIZE];
                pages.put(key, current);
            }
            currentX = px;
            currentZ = pz;
        }
        int index = (x & (PAGE_SIZE - 1)) + (z & (PAGE_SIZE - 1)) * PAGE_SIZE;
        if (current[index] == null) current[index] = manager.getBiomeGenAt(x, z);
        return current[index];
    }

    private static boolean supportsBulk(WorldChunkManager manager) {
        // Unknown replacements can override point lookup with extra rules. Preserve
        // those virtual calls instead of assuming every manager's bulk API agrees.
        String name = manager.getClass().getName();
        return manager.getClass() == WorldChunkManager.class
                || name.equals("biomesoplenty.common.world.WorldChunkManagerBOPHell");
    }
}
