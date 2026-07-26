package amidst.gtnh.worker.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ports the landmark forecast used by Twilight Forest's magic map. The
 * resulting coordinates are the exact centers of feature chunks.
 */
final class TwilightForestFeaturePredictor {

    private static final int REGION_BLOCKS = 16 * 16;
    private static final String KIND = "TWILIGHT_FEATURE";

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int minX,
            int minZ,
            int width,
            int height,
            TwilightForestBiomeSampler sampler) {
        long maxXExclusive = (long) minX + width;
        long maxZExclusive = (long) minZ + height;
        int minimumRegionX = floorDiv((long) minX - REGION_BLOCKS, REGION_BLOCKS);
        int maximumRegionX = floorDiv(maxXExclusive + REGION_BLOCKS, REGION_BLOCKS);
        int minimumRegionZ = floorDiv((long) minZ - REGION_BLOCKS, REGION_BLOCKS);
        int maximumRegionZ = floorDiv(maxZExclusive + REGION_BLOCKS, REGION_BLOCKS);
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();

        for (int regionX = minimumRegionX; regionX <= maximumRegionX; regionX++) {
            for (int regionZ = minimumRegionZ; regionZ <= maximumRegionZ; regionZ++) {
                FeatureCenter center = sampler.isOldMapGen()
                        ? oldCenter(regionX, regionZ)
                        : center(regionX, regionZ);
                if (center.x < minX
                        || center.x >= maxXExclusive
                        || center.z < minZ
                        || center.z >= maxZExclusive) {
                    continue;
                }
                String feature = featureAt(seed, center.x >> 4, center.z >> 4, sampler);
                if (feature != null) {
                    result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                            KIND,
                            feature,
                            center.x,
                            center.z,
                            "EXACT"));
                }
            }
        }
        return result;
    }

    private static String featureAt(
            long seed,
            int featureChunkX,
            int featureChunkZ,
            TwilightForestBiomeSampler sampler) {
        int chunkX = Math.round(featureChunkX / 16F) * 16;
        int chunkZ = Math.round(featureChunkZ / 16F) * 16;
        String biome = sampler.getFeatureBiomeKey((chunkX << 4) + 8, (chunkZ << 4) + 8);
        Random random = new Random(seed + chunkX * 25117L + chunkZ * 151121L);
        int randomValue = random.nextInt(16);

        if (sampler.isOldMapGen()) {
            return oldFeature(biome, randomValue);
        }
        if ("GLACIER".equals(biome)) return "ICE_TOWER";
        if ("SNOW".equals(biome)) return "YETI_LAIR";
        if ("LAKE".equals(biome)) return "QUEST_ISLAND";
        if ("ENCHANTED_FOREST".equals(biome)) return "QUEST_GROVE";
        if ("FIRE_SWAMP".equals(biome)) return "HYDRA_LAIR";
        if ("SWAMP".equals(biome)) return "LABYRINTH";
        if ("DARK_FOREST".equals(biome)) return "KNIGHT_STRONGHOLD";
        if ("DARK_FOREST_CENTER".equals(biome)) return "DARK_TOWER";
        if ("HIGHLANDS_CENTER".equals(biome)) return "FINAL_CASTLE";
        if ("HIGHLANDS".equals(biome)) return "TROLL_LAIR";
        if ("DEEP_MUSHROOMS".equals(biome)) return "MUSHROOM_TOWER";

        int regionOffsetX = Math.abs(((chunkX + 64) >> 4) % 8);
        int regionOffsetZ = Math.abs(((chunkZ + 64) >> 4) % 8);
        if ((regionOffsetX == 4 && regionOffsetZ == 5)
                || (regionOffsetX == 4 && regionOffsetZ == 3)) {
            return "LICH_TOWER";
        }
        if ((regionOffsetX == 5 && regionOffsetZ == 4)
                || (regionOffsetX == 3 && regionOffsetZ == 4)) {
            return "NAGA_COURTYARD";
        }
        switch (randomValue) {
            case 6:
            case 7:
            case 8:
                return "MEDIUM_HOLLOW_HILL";
            case 9:
                return "LARGE_HOLLOW_HILL";
            case 10:
            case 11:
                return "HEDGE_MAZE";
            case 12:
            case 13:
                return "NAGA_COURTYARD";
            case 14:
            case 15:
                return "LICH_TOWER";
            default:
                return "SMALL_HOLLOW_HILL";
        }
    }

    private static String oldFeature(String biome, int randomValue) {
        if ("GLACIER".equals(biome)) return "ICE_TOWER";
        if ("LAKE".equals(biome)) return "QUEST_ISLAND";
        if ("ENCHANTED_FOREST".equals(biome)) return "QUEST_GROVE";
        if ("FIRE_SWAMP".equals(biome)) return "HYDRA_LAIR";
        if ("CLEARING".equals(biome) || "OAK_SAVANNA".equals(biome)) return "LABYRINTH";
        if ("DARK_FOREST".equals(biome)) {
            if (randomValue % 3 == 1) return "DARK_TOWER";
            if (randomValue % 3 == 2) return "KNIGHT_STRONGHOLD";
        }
        if ("HIGHLANDS_CENTER".equals(biome)) return "FINAL_CASTLE";
        if ("HIGHLANDS".equals(biome)) return "TROLL_LAIR";
        if ("DEEP_MUSHROOMS".equals(biome)) return "MUSHROOM_TOWER";
        switch (randomValue) {
            case 7:
            case 8:
            case 9:
                return "MEDIUM_HOLLOW_HILL";
            case 10:
                return "LARGE_HOLLOW_HILL";
            case 11:
            case 12:
                return "HEDGE_MAZE";
            case 13:
                return "SWAMP".equals(biome) ? "HYDRA_LAIR" : "NAGA_COURTYARD";
            case 14:
            case 15:
                return "LICH_TOWER";
            default:
                return "SMALL_HOLLOW_HILL";
        }
    }

    private static FeatureCenter center(int regionX, int regionZ) {
        long mixedSeed = (regionX * 3129871L) ^ (long) regionZ * 116129781L;
        mixedSeed = mixedSeed * mixedSeed * 42317861L + mixedSeed * 7L;
        int centerX = 8 + (int) (mixedSeed >> 12 & 3L) - (int) (mixedSeed >> 15 & 3L);
        int centerZ = 8 + (int) (mixedSeed >> 18 & 3L) - (int) (mixedSeed >> 21 & 3L);
        int blockX = regionX >= 0
                ? (regionX * 16 + centerX - 8) * 16 + 8
                : (regionX * 16 + (16 - centerX) - 8) * 16 + 9;
        int blockZ = regionZ >= 0
                ? (regionZ * 16 + centerZ - 8) * 16 + 8
                : (regionZ * 16 + (16 - centerZ) - 8) * 16 + 9;
        return new FeatureCenter(blockX, blockZ);
    }

    private static FeatureCenter oldCenter(int regionX, int regionZ) {
        return new FeatureCenter(regionX * REGION_BLOCKS + 8, regionZ * REGION_BLOCKS + 8);
    }

    private static int floorDiv(long value, int divisor) {
        long result = Math.floorDiv(value, (long) divisor);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Twilight Forest feature query is outside supported range");
        }
        return (int) result;
    }

    private static final class FeatureCenter {

        private final int x;
        private final int z;

        private FeatureCenter(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
