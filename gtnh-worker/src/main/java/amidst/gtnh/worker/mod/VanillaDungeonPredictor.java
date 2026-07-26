package amidst.gtnh.worker.mod;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldType;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import net.minecraftforge.event.terraingen.InitMapGenEvent.EventType;
import net.minecraftforge.event.terraingen.TerrainGen;

/**
 * Filters RWG's vanilla dungeon attempts against a deterministic underground
 * cave mask. Terrain-dependent lakes, structures and mod event handlers can
 * still change the final result, so records remain explicitly POSSIBLE.
 */
final class VanillaDungeonPredictor {

    private static final int MAX_CACHED_CAVE_CHUNKS = 2048;
    private static final int STRUCTURE_EXCLUSION_CHUNKS = 8;

    private final Field generateUndergroundLakes;
    private final Field generateUndergroundLavaLakes;
    private long caveSeed = Long.MIN_VALUE;
    private SurfaceBiomeSamplers.SeedOnlyWorld caveWorld;
    private MapGenBase caveGenerator;
    private final Map<Long, CaveMask> caveCache =
            new LinkedHashMap<Long, CaveMask>(256, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CaveMask> eldest) {
                    return size() > MAX_CACHED_CAVE_CHUNKS;
                }
            };

    VanillaDungeonPredictor() {
        try {
            Class<?> config = Class.forName("rwg.config.ConfigRWG");
            generateUndergroundLakes = config.getField("generateUndergroundLakes");
            generateUndergroundLavaLakes =
                    config.getField("generateUndergroundLavaLakes");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("RWG dungeon prediction API is unavailable", e);
        }
    }

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int minX,
            int minZ,
            int width,
            int height,
            SurfaceBiomeSampler sampler,
            List<RoguelikeDungeonPredictor.StructureDescriptor> rwgStructures) {
        prepareCaveContext(seed);
        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        int minChunkX = floorDiv((long) minX - 23L, 16);
        int maxChunkX = floorDiv(maxX - 1L - 8L, 16);
        int minChunkZ = floorDiv((long) minZ - 23L, 16);
        int maxChunkZ = floorDiv(maxZ - 1L - 8L, 16);
        java.util.ArrayList<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new java.util.ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();

        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (nearMapStructure(chunkX, chunkZ, rwgStructures)) {
                    continue;
                }
                Random random = rwgPopulationRandom(seed, chunkX, chunkZ);
                if (getBoolean(generateUndergroundLakes) && random.nextInt(10) == 0) {
                    continue;
                }
                if (getBoolean(generateUndergroundLavaLakes) && random.nextInt(18) == 0) {
                    continue;
                }

                for (int attempt = 0; attempt < 8; attempt++) {
                    int x = chunkX * 16 + random.nextInt(16) + 8;
                    int y = random.nextInt(128);
                    int z = chunkZ * 16 + random.nextInt(16) + 8;
                    int radiusX = random.nextInt(2) + 2;
                    int radiusZ = random.nextInt(2) + 2;
                    if (isPossibleDungeon(x, y, z, radiusX, radiusZ, sampler)) {
                        if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
                            result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                                    "VANILLA_SPAWNER_DUNGEON",
                                    Integer.toString(y),
                                    x,
                                    z,
                                    "POSSIBLE"));
                        }
                        // A successful dungeon consumes loot-table randomness,
                        // so later attempts no longer have seed-only positions.
                        break;
                    }
                }
            }
        }
        return result;
    }

    private boolean isPossibleDungeon(
            int centerX,
            int centerY,
            int centerZ,
            int radiusX,
            int radiusZ,
            SurfaceBiomeSampler sampler) {
        if (centerY < 6) {
            return false;
        }
        float surface = sampler.getApproximateSurfaceHeight(centerX, centerZ);
        if (centerY + 5 >= surface) {
            return false;
        }

        int openings = 0;
        for (int x = centerX - radiusX - 1; x <= centerX + radiusX + 1; x++) {
            for (int y = centerY - 1; y <= centerY + 4; y++) {
                for (int z = centerZ - radiusZ - 1; z <= centerZ + radiusZ + 1; z++) {
                    CaveMask mask = getCaveMask(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
                    int localX = Math.floorMod(x, 16);
                    int localZ = Math.floorMod(z, 16);
                    if ((y == centerY - 1 || y == centerY + 4)
                            && !mask.isSolid(localX, y, localZ)) {
                        return false;
                    }
                    boolean boundary = x == centerX - radiusX - 1
                            || x == centerX + radiusX + 1
                            || z == centerZ - radiusZ - 1
                            || z == centerZ + radiusZ + 1;
                    if (boundary
                            && y == centerY
                            && mask.isAir(localX, y, localZ)
                            && mask.isAir(localX, y + 1, localZ)) {
                        openings++;
                    }
                }
            }
        }
        return openings >= 1 && openings <= 5;
    }

    private CaveMask getCaveMask(int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
        CaveMask cached = caveCache.get(Long.valueOf(key));
        if (cached != null) {
            return cached;
        }
        Block[] blocks = new Block[16 * 16 * 256];
        Arrays.fill(blocks, Blocks.stone);
        caveGenerator.func_151539_a(null, caveWorld, chunkX, chunkZ, blocks);
        CaveMask created = new CaveMask(blocks);
        caveCache.put(Long.valueOf(key), created);
        return created;
    }

    private void prepareCaveContext(long seed) {
        if (caveWorld != null && caveSeed == seed) {
            return;
        }
        WorldType worldType = WorldType.parseWorldType("RWG");
        if (worldType == null) {
            throw new IllegalStateException("RWG world type is not registered");
        }
        caveSeed = seed;
        caveWorld = new SurfaceBiomeSamplers.SeedOnlyWorld(seed, worldType);
        caveGenerator = TerrainGen.getModdedMapGen(new MapGenCaves(), EventType.CAVE);
        caveCache.clear();
    }

    private static boolean nearMapStructure(
            int chunkX,
            int chunkZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> structures) {
        for (RoguelikeDungeonPredictor.StructureDescriptor structure : structures) {
            if (!"STRONGHOLD".equals(structure.kind)
                    && !"VILLAGE".equals(structure.kind)
                    && !"MINESHAFT".equals(structure.kind)) {
                continue;
            }
            int structureChunkX = Math.floorDiv(structure.x, 16);
            int structureChunkZ = Math.floorDiv(structure.z, 16);
            if (Math.abs((long) chunkX - structureChunkX) <= STRUCTURE_EXCLUSION_CHUNKS
                    && Math.abs((long) chunkZ - structureChunkZ) <= STRUCTURE_EXCLUSION_CHUNKS) {
                return true;
            }
        }
        return false;
    }

    private static Random rwgPopulationRandom(long seed, int chunkX, int chunkZ) {
        Random random = new Random(seed);
        long xSeed = random.nextLong() / 2L * 2L + 1L;
        long zSeed = random.nextLong() / 2L * 2L + 1L;
        random.setSeed((long) chunkX * xSeed + (long) chunkZ * zSeed ^ seed);
        return random;
    }

    private static int floorDiv(long value, int divisor) {
        return (int) Math.floorDiv(value, (long) divisor);
    }

    private static boolean getBoolean(Field field) {
        try {
            return field.getBoolean(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read RWG dungeon setting", e);
        }
    }

    private static final class CaveMask {

        private final BitSet air = new BitSet(16 * 16 * 256);
        private final BitSet solid = new BitSet(16 * 16 * 256);

        private CaveMask(Block[] blocks) {
            for (int i = 0; i < blocks.length; i++) {
                Block block = blocks[i] == null ? Blocks.air : blocks[i];
                if (block == Blocks.air) {
                    air.set(i);
                }
                if (block.getMaterial().isSolid()) {
                    solid.set(i);
                }
            }
        }

        private boolean isAir(int x, int y, int z) {
            return y >= 0 && y < 256 && air.get(index(x, y, z));
        }

        private boolean isSolid(int x, int y, int z) {
            return y >= 0 && y < 256 && solid.get(index(x, y, z));
        }

        private static int index(int x, int y, int z) {
            return (x * 16 + z) * 256 + y;
        }
    }
}
