package amidst.gtnh.worker.mod;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Save-free predictors for GTNH's Hardcore Ender Expansion biome islands and
 * dungeon towers, plus Draconic Evolution chaos islands.
 *
 * <p>HEE salts its structure regions with the number of previous dragon
 * deaths. Arbitrary-seed previews intentionally model a fresh End, where that
 * value is zero.</p>
 */
final class EndStructurePredictor {

    static final int END_VOID_BIOME = 3000;
    static final int CENTRAL_END_BIOME = 3001;
    static final int INFESTED_FOREST_BIOME = 3002;
    static final int BURNING_MOUNTAINS_BIOME = 3003;
    static final int ENCHANTED_ISLAND_BIOME = 3004;

    private static final int FRESH_END_DRAGON_SEED = 1;
    private static final int HEE_ISLAND_SPACING = 28;
    private static final int HEE_ISLAND_MIN_SPACING = 13;
    private static final int HEE_ISLAND_SIZE = 208;
    private static final int HEE_ISLAND_RADIUS = HEE_ISLAND_SIZE / 2;
    private static final int HEE_TOWER_SPACING = 15;
    private static final int HEE_TOWER_MIN_SPACING = 9;
    private static final int CENTRAL_END_RADIUS = 1024;

    int[] sampleBiomes(long seed, int x, int z, int width, int height, int step) {
        int[] result = new int[width * height];
        for (int row = 0; row < height; row++) {
            long sampleZ = (long) z + (long) row * step;
            for (int column = 0; column < width; column++) {
                long sampleX = (long) x + (long) column * step;
                double distanceSquared =
                        (double) sampleX * sampleX + (double) sampleZ * sampleZ;
                result[row * width + column] =
                        distanceSquared <= (double) CENTRAL_END_RADIUS * CENTRAL_END_RADIUS
                                ? CENTRAL_END_BIOME
                                : END_VOID_BIOME;
            }
        }

        long maxX = (long) x + (long) (width - 1) * step;
        long maxZ = (long) z + (long) (height - 1) * step;
        List<BiomeIsland> islands = findBiomeIslands(seed, x, z, maxX, maxZ);
        long radiusSquared = (long) HEE_ISLAND_RADIUS * HEE_ISLAND_RADIUS;
        for (BiomeIsland island : islands) {
            for (int row = 0; row < height; row++) {
                long sampleZ = (long) z + (long) row * step;
                long dz = sampleZ - island.centerZ;
                if (Math.abs(dz) > HEE_ISLAND_RADIUS) {
                    continue;
                }
                for (int column = 0; column < width; column++) {
                    long sampleX = (long) x + (long) column * step;
                    long dx = sampleX - island.centerX;
                    if (dx * dx + dz * dz <= radiusSquared) {
                        result[row * width + column] = island.biomeId;
                    }
                }
            }
        }
        return result;
    }

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int x,
            int z,
            int width,
            int height) {
        long maxX = (long) x + width - 1L;
        long maxZ = (long) z + height - 1L;
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();

        for (BiomeIsland island : findBiomeIslands(seed, x, z, maxX, maxZ)) {
            if (isInBounds(island.centerX, island.centerZ, x, z, maxX, maxZ)) {
                result.add(descriptor(
                        "HEE_BIOME_ISLAND",
                        biomeWireName(island.biomeId),
                        island.centerX,
                        island.centerZ,
                        "EXACT_FRESH_END"));
            }
        }

        addDungeonTowers(seed, x, z, maxX, maxZ, result);
        addChaosIslands(x, z, maxX, maxZ, result);
        return result;
    }

    private static List<BiomeIsland> findBiomeIslands(
            long seed,
            long minX,
            long minZ,
            long maxX,
            long maxZ) {
        int minimumStartChunkX = floorBlockToChunk(minX - HEE_ISLAND_SIZE);
        int minimumStartChunkZ = floorBlockToChunk(minZ - HEE_ISLAND_SIZE);
        int maximumStartChunkX = floorBlockToChunk(maxX);
        int maximumStartChunkZ = floorBlockToChunk(maxZ);
        int minimumRegionX = Math.floorDiv(minimumStartChunkX, HEE_ISLAND_SPACING);
        int minimumRegionZ = Math.floorDiv(minimumStartChunkZ, HEE_ISLAND_SPACING);
        int maximumRegionX = Math.floorDiv(maximumStartChunkX, HEE_ISLAND_SPACING);
        int maximumRegionZ = Math.floorDiv(maximumStartChunkZ, HEE_ISLAND_SPACING);

        List<BiomeIsland> result = new ArrayList<BiomeIsland>();
        for (int regionX = minimumRegionX; regionX <= maximumRegionX; regionX++) {
            for (int regionZ = minimumRegionZ; regionZ <= maximumRegionZ; regionZ++) {
                BiomeIsland island = biomeIslandInRegion(seed, regionX, regionZ);
                if (island != null
                        && island.centerX + HEE_ISLAND_RADIUS >= minX
                        && island.centerX - HEE_ISLAND_RADIUS <= maxX
                        && island.centerZ + HEE_ISLAND_RADIUS >= minZ
                        && island.centerZ - HEE_ISLAND_RADIUS <= maxZ) {
                    result.add(island);
                }
            }
        }
        return result;
    }

    private static BiomeIsland biomeIslandInRegion(long seed, int regionX, int regionZ) {
        Random random = new Random(
                regionX * 341873128712L
                        + regionZ * 132897987541L
                        + seed
                        + 358041L);
        random.nextInt(FRESH_END_DRAGON_SEED);
        int chunkX = regionX * HEE_ISLAND_SPACING
                + random.nextInt(HEE_ISLAND_SPACING - HEE_ISLAND_MIN_SPACING);
        int chunkZ = regionZ * HEE_ISLAND_SPACING
                + random.nextInt(HEE_ISLAND_SPACING - HEE_ISLAND_MIN_SPACING);
        long centerX = (long) chunkX * 16L + HEE_ISLAND_RADIUS;
        long centerZ = (long) chunkZ * 16L + HEE_ISLAND_RADIUS;
        double distance = Math.sqrt((double) centerX * centerX + (double) centerZ * centerZ);
        if (distance < 1600D || random.nextInt(7) > 4) {
            return null;
        }
        double ramp = distance < 1600D
                ? 0D
                : distance > 4000D ? 1D : (distance - 1600D) / 2400D;
        double chance = 0.65D + 0.35D * ramp;
        if (chance < 1D && random.nextDouble() >= chance) {
            return null;
        }

        int startX = chunkX * 16;
        int startZ = chunkZ * 16;
        Random biomeRandom = new Random(
                ((startX / 9) * 238504L + (startZ / 9) * 10058432215L) ^ seed);
        biomeRandom.nextInt(25);
        int biomeId = INFESTED_FOREST_BIOME + biomeRandom.nextInt(3);
        return new BiomeIsland(
                chunkX,
                chunkZ,
                clampToInt(centerX),
                clampToInt(centerZ),
                biomeId);
    }

    private static void addDungeonTowers(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        int minimumChunkX = floorBlockToChunk((long) minX - 16L);
        int minimumChunkZ = floorBlockToChunk((long) minZ - 16L);
        int maximumChunkX = floorBlockToChunk(maxX - 16L);
        int maximumChunkZ = floorBlockToChunk(maxZ - 16L);
        int minimumRegionX = Math.floorDiv(minimumChunkX, HEE_TOWER_SPACING);
        int minimumRegionZ = Math.floorDiv(minimumChunkZ, HEE_TOWER_SPACING);
        int maximumRegionX = Math.floorDiv(maximumChunkX, HEE_TOWER_SPACING);
        int maximumRegionZ = Math.floorDiv(maximumChunkZ, HEE_TOWER_SPACING);

        for (int regionX = minimumRegionX; regionX <= maximumRegionX; regionX++) {
            for (int regionZ = minimumRegionZ; regionZ <= maximumRegionZ; regionZ++) {
                Random random = new Random(
                        regionX * 341873128712L
                                + regionZ * 132897987541L
                                + seed
                                + 358041L);
                random.nextInt(FRESH_END_DRAGON_SEED);
                int chunkX = regionX * HEE_TOWER_SPACING
                        + random.nextInt(HEE_TOWER_SPACING - HEE_TOWER_MIN_SPACING);
                int chunkZ = regionZ * HEE_TOWER_SPACING
                        + random.nextInt(HEE_TOWER_SPACING - HEE_TOWER_MIN_SPACING);
                int centerX = chunkX * 16 + 16;
                int centerZ = chunkZ * 16 + 16;
                double distance = Math.sqrt(
                        (double) centerX * centerX + (double) centerZ * centerZ);
                if (distance < 350D || random.nextInt(100) > 28) {
                    continue;
                }
                if (distance > 900D) {
                    double falloff = distance > 3800D
                            ? 0D
                            : 1D - Math.pow((distance - 900D) / 2900D, 3D);
                    double chance = 0.25D + 0.75D * falloff;
                    if (random.nextDouble() >= chance) {
                        continue;
                    }
                }
                if (isNearBiomeIsland(seed, chunkX, chunkZ)) {
                    continue;
                }
                if (isInBounds(centerX, centerZ, minX, minZ, maxX, maxZ)) {
                    result.add(descriptor(
                            "HEE_DUNGEON_TOWER",
                            null,
                            centerX,
                            centerZ,
                            "EXACT_FRESH_END"));
                }
            }
        }
    }

    private static boolean isNearBiomeIsland(long seed, int towerChunkX, int towerChunkZ) {
        int towerCenterX = towerChunkX * 16 + 16;
        int towerCenterZ = towerChunkZ * 16 + 16;
        int minimumIslandRegionX = Math.floorDiv(towerChunkX - 12, HEE_ISLAND_SPACING);
        int minimumIslandRegionZ = Math.floorDiv(towerChunkZ - 12, HEE_ISLAND_SPACING);
        int maximumIslandRegionX = Math.floorDiv(towerChunkX + 12, HEE_ISLAND_SPACING);
        int maximumIslandRegionZ = Math.floorDiv(towerChunkZ + 12, HEE_ISLAND_SPACING);
        for (int regionX = minimumIslandRegionX; regionX <= maximumIslandRegionX; regionX++) {
            for (int regionZ = minimumIslandRegionZ; regionZ <= maximumIslandRegionZ; regionZ++) {
                BiomeIsland island = biomeIslandInRegion(seed, regionX, regionZ);
                if (island == null
                        || island.chunkX < towerChunkX - 12
                        || island.chunkX > towerChunkX + 12
                        || island.chunkZ < towerChunkZ - 12
                        || island.chunkZ > towerChunkZ + 12) {
                    continue;
                }
                double distance = Math.sqrt(
                        square((long) towerCenterX - island.centerX)
                                + square((long) towerCenterZ - island.centerZ));
                if (distance < 125D) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addChaosIslands(
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        DraconicConfig config = DraconicConfig.read();
        if (!config.enabled || config.separation < 1) {
            return;
        }
        long firstX = ceilToMultiple(minX, config.separation);
        long firstZ = ceilToMultiple(minZ, config.separation);
        for (long centerX = firstX; centerX <= maxX; centerX += config.separation) {
            for (long centerZ = firstZ; centerZ <= maxZ; centerZ += config.separation) {
                if (centerX == 0L && centerZ == 0L) {
                    continue;
                }
                result.add(descriptor(
                        "DRACONIC_CHAOS_ISLAND",
                        "SEPARATION_" + config.separation,
                        clampToInt(centerX),
                        clampToInt(centerZ),
                        "EXACT_CONFIG"));
            }
        }
    }

    private static long ceilToMultiple(long value, int multiple) {
        long floor = Math.floorDiv(value, (long) multiple) * multiple;
        return floor == value ? value : floor + multiple;
    }

    private static int floorBlockToChunk(long block) {
        long chunk = Math.floorDiv(block, 16L);
        return clampToInt(chunk);
    }

    private static int clampToInt(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("End prediction coordinate is outside the Minecraft integer range");
        }
        return (int) value;
    }

    private static long square(long value) {
        return value * value;
    }

    private static boolean isInBounds(
            long pointX,
            long pointZ,
            long minX,
            long minZ,
            long maxX,
            long maxZ) {
        return pointX >= minX && pointX <= maxX && pointZ >= minZ && pointZ <= maxZ;
    }

    private static String biomeWireName(int biomeId) {
        switch (biomeId) {
            case INFESTED_FOREST_BIOME:
                return "INFESTED_FOREST";
            case BURNING_MOUNTAINS_BIOME:
                return "BURNING_MOUNTAINS";
            case ENCHANTED_ISLAND_BIOME:
                return "ENCHANTED_ISLAND";
            default:
                return "UNKNOWN";
        }
    }

    private static RoguelikeDungeonPredictor.StructureDescriptor descriptor(
            String kind,
            String subtype,
            int x,
            int z,
            String certainty) {
        return new RoguelikeDungeonPredictor.StructureDescriptor(
                kind,
                subtype,
                x,
                z,
                certainty);
    }

    private static final class BiomeIsland {

        private final int chunkX;
        private final int chunkZ;
        private final int centerX;
        private final int centerZ;
        private final int biomeId;

        private BiomeIsland(
                int chunkX,
                int chunkZ,
                int centerX,
                int centerZ,
                int biomeId) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.biomeId = biomeId;
        }
    }

    private static final class DraconicConfig {

        private final boolean enabled;
        private final int separation;

        private DraconicConfig(boolean enabled, int separation) {
            this.enabled = enabled;
            this.separation = separation;
        }

        private static DraconicConfig read() {
            try {
                Class<?> configClass = Class.forName(
                        "com.brandon3055.draconicevolution.common.handler.ConfigHandler");
                Class<?> generatorClass = Class.forName(
                        "com.brandon3055.draconicevolution.common.world.DraconicWorldGenerator");
                boolean configured = getBoolean(configClass, "generateChaosIslands");
                boolean generatorEnabled = getBoolean(generatorClass, "chaosIslandsEnabled");
                int separation = getInt(configClass, "chaosIslandSeparation");
                return new DraconicConfig(configured && generatorEnabled, separation);
            } catch (ReflectiveOperationException e) {
                AmidstGtnhWorkerLog.LOG.warn(
                        "Draconic Evolution chaos-island configuration is unavailable");
                return new DraconicConfig(false, 10000);
            }
        }

        private static boolean getBoolean(Class<?> owner, String name)
                throws ReflectiveOperationException {
            Field field = owner.getField(name);
            return field.getBoolean(null);
        }

        private static int getInt(Class<?> owner, String name)
                throws ReflectiveOperationException {
            Field field = owner.getField(name);
            return field.getInt(null);
        }
    }
}
