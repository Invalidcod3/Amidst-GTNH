package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Replays the seed-only grid selection performed by AE2's
 * {@code MeteoriteWorldGen}. The final block and height checks are deliberately
 * left to Minecraft, so every result is exposed as a possible meteorite.
 */
final class Ae2MeteoritePredictor {

    private static final int OVERWORLD = 0;
    private static final int MAX_GRIDS = 65536;

    private final Object config;
    private final Object meteoriteFeature;
    private final Method isFeatureEnabled;
    private final Field minimumDistance;
    private final Field spawnChance;
    private final Field dimensionWhitelist;

    Ae2MeteoritePredictor() {
        try {
            Class<?> configClass = Class.forName("appeng.core.AEConfig");
            config = configClass.getField("instance").get(null);
            if (config == null) {
                throw new IllegalStateException("AE2 configuration is not initialized");
            }
            Class<?> featureClass = Class.forName("appeng.core.features.AEFeature");
            meteoriteFeature = featureClass.getField("MeteoriteWorldGen").get(null);
            isFeatureEnabled = configClass.getMethod("isFeatureEnabled", featureClass);
            minimumDistance = configClass.getField("minMeteoriteDistance");
            spawnChance = configClass.getField("meteoriteSpawnChance");
            dimensionWhitelist = configClass.getField("meteoriteDimensionWhitelist");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AE2 meteorite prediction API is unavailable", e);
        }
    }

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int dimension,
            int minX,
            int minZ,
            int width,
            int height) {
        if (dimension != OVERWORLD
                || !invokeFeatureEnabled()
                || !isDimensionWhitelisted(dimension)) {
            return new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        }

        int distance = Math.max(8, readDimensionInt(minimumDistance, dimension));
        int margin = Math.max(1, distance / 10);
        int randomSpan = distance - margin * 2;
        if (randomSpan < 1) {
            throw new IllegalStateException("AE2 meteorite distance leaves no candidate area");
        }
        double surfaceChance = readDimensionDouble(spawnChance, dimension);

        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        int minGridX = floorDiv((long) minX - (distance - margin - 1L), distance);
        int maxGridX = floorDiv(maxX - 1L - margin, distance);
        int minGridZ = floorDiv((long) minZ - (distance - margin - 1L), distance);
        int maxGridZ = floorDiv(maxZ - 1L - margin, distance);
        validateGridCount(minGridX, maxGridX, minGridZ, maxGridZ);

        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        Random random = new Random();
        for (int gridZ = minGridZ; gridZ <= maxGridZ; gridZ++) {
            for (int gridX = minGridX; gridX <= maxGridX; gridX++) {
                seedFromGrid(random, seed, gridX, gridZ);
                boolean surfaceBiased = random.nextDouble() < surfaceChance;
                int x = gridX * distance + random.nextInt(randomSpan) + margin;
                int z = gridZ * distance + random.nextInt(randomSpan) + margin;
                if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
                    result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                            "AE2_METEORITE",
                            surfaceBiased ? "SURFACE_BIASED" : "UNDERGROUND_BIASED",
                            x,
                            z,
                            "POSSIBLE"));
                }
            }
        }
        return result;
    }

    private boolean invokeFeatureEnabled() {
        try {
            return ((Boolean) isFeatureEnabled.invoke(config, meteoriteFeature)).booleanValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read AE2 meteorite feature flag", e);
        }
    }

    private boolean isDimensionWhitelisted(int dimension) {
        for (String entry : getStrings(dimensionWhitelist)) {
            String[] parts = entry.split(",");
            if (parts.length > 0 && parseInt(parts[0], "meteorite dimension") == dimension) {
                return true;
            }
        }
        return false;
    }

    private int readDimensionInt(Field field, int dimension) {
        String value = findDimensionValue(getStrings(field), dimension);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "AE2 " + field.getName() + " has invalid value " + value,
                    e);
        }
    }

    private double readDimensionDouble(Field field, int dimension) {
        String value = findDimensionValue(getStrings(field), dimension);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "AE2 " + field.getName() + " has invalid value " + value,
                    e);
        }
    }

    private String[] getStrings(Field field) {
        try {
            return (String[]) field.get(config);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read AE2 " + field.getName(), e);
        }
    }

    private static String findDimensionValue(String[] entries, int dimension) {
        String fallback = null;
        for (String entry : entries) {
            String[] parts = entry.split("=", 2);
            if (parts.length != 2) {
                throw new IllegalStateException("invalid AE2 dimension value " + entry);
            }
            int entryDimension = parseInt(parts[0], "dimension");
            if (entryDimension == dimension) {
                return parts[1].trim();
            }
            if (entryDimension == OVERWORLD && fallback == null) {
                fallback = parts[1].trim();
            }
        }
        if (fallback == null) {
            throw new IllegalStateException("AE2 has no overworld meteorite configuration");
        }
        return fallback;
    }

    private static int parseInt(String value, String description) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid AE2 " + description + " " + value, e);
        }
    }

    private static void seedFromGrid(Random random, long worldSeed, long gridX, long gridZ) {
        random.setSeed(worldSeed);
        long xSeed = random.nextLong() >> 3;
        long zSeed = random.nextLong() >> 3;
        random.setSeed((xSeed * gridX + zSeed * gridZ) ^ worldSeed);
    }

    private static int floorDiv(long value, int divisor) {
        long result = Math.floorDiv(value, (long) divisor);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("AE2 meteorite grid is outside the integer range");
        }
        return (int) result;
    }

    private static void validateGridCount(
            int minGridX,
            int maxGridX,
            int minGridZ,
            int maxGridZ) {
        long count = ((long) maxGridX - minGridX + 1L)
                * ((long) maxGridZ - minGridZ + 1L);
        if (count > MAX_GRIDS) {
            throw new IllegalArgumentException("structure query covers too many AE2 meteorite grids");
        }
    }
}
