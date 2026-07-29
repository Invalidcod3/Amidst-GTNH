package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Replays GTNH's End asteroid seed and ore-mix selection without generating
 * chunks. GalacticGreg (current GTNH) and the former GT5U generator use
 * different random streams, so the available implementation is detected at
 * runtime.
 */
final class EndAsteroidPredictor {

    private static final int END_DIMENSION = 1;
    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int MAX_CANDIDATES = 262144;
    private static final long GALACTIC_GREG_OFFSET = 588283L;

    private Algorithm algorithm;

    private Object currentEndDefinition;
    private Object currentAsteroidConfig;
    private Method currentGetAsteroidMaterial;
    private Method currentGetStoneCategory;
    private Method currentVeins;
    private Method currentInDimension;
    private Method currentInStone;
    private Method currentFindRandom;
    private Field currentEnabled;
    private Field currentProbability;
    private Field currentMinY;
    private Field currentMaxY;
    private Method currentHeeExclusion;
    private Method currentChaosExclusion;
    private int currentChaosRadius;

    private List<?> legacyLayers;
    private Field legacyWeight;
    private Field legacyEndAsteroid;
    private int legacyTotalWeight;
    private int legacyAttempts;
    private boolean legacyEnabled;
    private int legacyProbability;

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long worldSeed,
            int minX,
            int minZ,
            int width,
            int height) {
        prepareRuntimeRegistry();

        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        int minChunkX = checkedInt(Math.floorDiv((long) minX, BLOCKS_PER_CHUNK));
        int maxChunkX = checkedInt(Math.floorDiv(maxX - 1L, BLOCKS_PER_CHUNK));
        int minChunkZ = checkedInt(Math.floorDiv((long) minZ, BLOCKS_PER_CHUNK));
        int maxChunkZ = checkedInt(Math.floorDiv(maxZ - 1L, BLOCKS_PER_CHUNK));
        long count = ((long) maxChunkX - minChunkX + 1L)
                * ((long) maxChunkZ - minChunkZ + 1L);
        if (count > MAX_CANDIDATES) {
            throw new IllegalArgumentException("structure query covers too many End asteroid chunks");
        }

        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                Candidate candidate = algorithm == Algorithm.CURRENT
                        ? selectCurrent(worldSeed, chunkX, chunkZ)
                        : selectLegacy(worldSeed, chunkX, chunkZ);
                if (candidate == null
                        || candidate.x < minX
                        || candidate.x >= maxX
                        || candidate.z < minZ
                        || candidate.z >= maxZ) {
                    continue;
                }
                result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                        candidate.kind,
                        algorithm == Algorithm.CURRENT
                                ? "GalacticGreg End asteroid"
                                : "Legacy GT End asteroid",
                        candidate.x,
                        candidate.z,
                        algorithm == Algorithm.CURRENT ? "EXACT_SEED" : "POSSIBLE"));
            }
        }
        return result;
    }

    private Candidate selectCurrent(long worldSeed, int chunkX, int chunkZ) {
        try {
            if (!currentEnabled.getBoolean(currentAsteroidConfig)
                    || isCurrentExclusion(chunkX, chunkZ)) {
                return null;
            }
            int probability = currentProbability.getInt(currentAsteroidConfig);
            long seed = currentSeed(worldSeed, chunkX, chunkZ);
            if (new GtnhXstrRandom(seed).nextInt(100) > probability) {
                return null;
            }

            GtnhXstrRandom random = new GtnhXstrRandom(seed);
            Object stone = currentGetAsteroidMaterial.invoke(
                    currentEndDefinition,
                    (Random) random.copy());
            if (stone == null) {
                return null;
            }
            Object stoneCategory = currentGetStoneCategory.invoke(stone);

            // One in five GalacticGreg asteroids selects a small ore instead
            // of an ore vein; none of the requested markers is a small ore.
            if (random.nextInt(5) == 0) {
                return null;
            }
            Object query = currentVeins.invoke(null);
            currentInDimension.invoke(query, currentEndDefinition);
            currentInStone.invoke(query, stoneCategory);
            Object layer = currentFindRandom.invoke(query, (Random) random.copy());
            String kind = kindForMix(worldgenName(layer));
            if (kind == null) {
                return null;
            }

            int x = chunkX * BLOCKS_PER_CHUNK + random.nextInt(BLOCKS_PER_CHUNK);
            int minY = currentMinY.getInt(currentAsteroidConfig);
            int maxY = currentMaxY.getInt(currentAsteroidConfig);
            random.nextInt(maxY - minY);
            int z = chunkZ * BLOCKS_PER_CHUNK + random.nextInt(BLOCKS_PER_CHUNK);
            return new Candidate(kind, x, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a GalacticGreg End asteroid", e);
        }
    }

    private boolean isCurrentExclusion(int chunkX, int chunkZ)
            throws ReflectiveOperationException {
        if ((long) chunkX * chunkX + (long) chunkZ * chunkZ <= 16L * 16L) {
            return true;
        }
        if (currentHeeExclusion != null
                && ((Boolean) currentHeeExclusion.invoke(null, chunkX, chunkZ)).booleanValue()) {
            return true;
        }
        return currentChaosExclusion != null
                && ((Boolean) currentChaosExclusion.invoke(
                        null,
                        chunkX,
                        chunkZ,
                        currentChaosRadius)).booleanValue();
    }

    private Candidate selectLegacy(long worldSeed, int chunkX, int chunkZ) {
        if (!legacyEnabled) {
            return null;
        }
        long seed = worldSeed
                + (long) chunkX * chunkX * 91777L
                + (long) chunkZ * chunkZ * 137413L
                + (long) chunkX * chunkZ * 1853L
                + chunkX * 3L
                + chunkZ * 17L;
        GtnhXstrRandom random = new GtnhXstrRandom(seed);
        if (legacyProbability > 1 && random.nextInt(legacyProbability) != 0) {
            return null;
        }
        try {
            Object selected = null;
            for (int attempt = 0; attempt < legacyAttempts; attempt++) {
                selected = selectLegacyEndAsteroid(random);
                if (selected != null) {
                    break;
                }
            }
            String kind = selected == null ? null : kindForMix(worldgenName(selected));
            if (kind == null) {
                return null;
            }
            int x = chunkX * BLOCKS_PER_CHUNK + random.nextInt(BLOCKS_PER_CHUNK);
            random.nextInt(150);
            int z = chunkZ * BLOCKS_PER_CHUNK + random.nextInt(BLOCKS_PER_CHUNK);
            return new Candidate(kind, x, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a legacy GT End asteroid", e);
        }
    }

    private Object selectLegacyEndAsteroid(Random random) throws IllegalAccessException {
        if (legacyTotalWeight <= 0 || legacyLayers.isEmpty()) {
            return null;
        }
        int remaining = random.nextInt(legacyTotalWeight);
        for (Object layer : legacyLayers) {
            remaining -= legacyWeight.getInt(layer);
            /*
             * The old generator deliberately did not break after landing on
             * a non-End layer. Once the weight reaches zero it keeps walking
             * and accepts the first later End-asteroid layer, if any.
             */
            if (remaining <= 0 && legacyEndAsteroid.getBoolean(layer)) {
                return layer;
            }
        }
        return null;
    }

    private void prepareRuntimeRegistry() {
        if (algorithm != null) {
            return;
        }
        try {
            try {
                prepareCurrentRegistry();
                algorithm = Algorithm.CURRENT;
            } catch (ClassNotFoundException e) {
                prepareLegacyRegistry();
                algorithm = Algorithm.LEGACY;
            }
            AmidstGtnhWorkerLog.LOG.info(
                    "GT End asteroid locator is using the {} selector",
                    algorithm == Algorithm.CURRENT ? "GalacticGreg" : "legacy GT5U");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GT End asteroid registry is unavailable", e);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void prepareCurrentRegistry() throws ReflectiveOperationException {
        Class<?> dimensionDefClass = Class.forName("galacticgreg.api.enums.DimensionDef");
        Object dimensionEnum = Enum.valueOf((Class<? extends Enum>) dimensionDefClass, "EndAsteroids");
        currentEndDefinition = dimensionDefClass.getField("modDimensionDef").get(dimensionEnum);

        Class<?> modDimensionDefClass = Class.forName("galacticgreg.api.ModDimensionDef");
        currentGetAsteroidMaterial =
                modDimensionDefClass.getMethod("getRandomAsteroidMaterial", Random.class);
        currentGetStoneCategory =
                Class.forName("gregtech.api.interfaces.IStoneType").getMethod("getCategory");

        Class<?> dynamicConfigClass =
                Class.forName("galacticgreg.dynconfig.DynamicDimensionConfig");
        Method getAsteroidConfig =
                dynamicConfigClass.getMethod("getAsteroidConfig", modDimensionDefClass);
        currentAsteroidConfig =
                getAsteroidConfig.invoke(null, currentEndDefinition);
        if (currentAsteroidConfig == null) {
            throw new IllegalStateException("End asteroid dynamic config is not initialized");
        }
        Class<?> asteroidConfigClass = currentAsteroidConfig.getClass();
        currentEnabled = asteroidConfigClass.getField("Enabled");
        currentProbability = asteroidConfigClass.getField("Probability");
        currentMinY = asteroidConfigClass.getField("AsteroidMinY");
        currentMaxY = asteroidConfigClass.getField("AsteroidMaxY");

        Class<?> queryClass = Class.forName("gregtech.common.worldgen.WorldgenQuery");
        currentVeins = queryClass.getMethod("veins");
        currentInDimension = queryClass.getMethod("inDimension", modDimensionDefClass);
        currentInStone = queryClass.getMethod(
                "inStone",
                Class.forName("gregtech.api.interfaces.IStoneCategory"));
        currentFindRandom = queryClass.getMethod("findRandom", Random.class);

        currentHeeExclusion = optionalMethod(
                "gregtech.common.worldgen.HEEIslandScanner",
                "isWithinRangeOfIsland",
                Integer.TYPE,
                Integer.TYPE);
        currentChaosExclusion = optionalMethod(
                "gregtech.common.worldgen.ChaosIslandLocator",
                "isWithinRange",
                Integer.TYPE,
                Integer.TYPE,
                Integer.TYPE);
        currentChaosRadius = readChaosRadius();
    }

    private void prepareLegacyRegistry() throws ReflectiveOperationException {
        Class<?> layerClass = Class.forName("gregtech.common.WorldgenGTOreLayer");
        legacyLayers = (List<?>) layerClass.getField("sList").get(null);
        legacyTotalWeight = layerClass.getField("sWeight").getInt(null);
        legacyWeight = layerClass.getField("mWeight");
        legacyEndAsteroid = layerClass.getField("mEndAsteroid");
        legacyAttempts = Class.forName("gregtech.api.enums.GTValues")
                .getField("oreveinAttempts")
                .getInt(null);

        Object config = Class.forName("gregtech.common.config.Worldgen")
                .getField("endAsteroids")
                .get(null);
        legacyEnabled = config.getClass()
                .getField("generateEndAsteroids")
                .getBoolean(config);
        legacyProbability = config.getClass()
                .getField("EndAsteroidProbability")
                .getInt(config);
    }

    private static Method optionalMethod(String className, String methodName, Class<?>... types) {
        try {
            return Class.forName(className).getMethod(methodName, types);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    private static int readChaosRadius() {
        try {
            Class<?> galacticGreg = Class.forName("galacticgreg.GalacticGreg");
            Object config = galacticGreg.getField("GalacticConfig").get(null);
            return config.getClass().getField("ChaosIslandExclusionRadius").getInt(config);
        } catch (ReflectiveOperationException | LinkageError e) {
            return 0;
        }
    }

    private static long currentSeed(long worldSeed, int chunkX, int chunkZ) {
        return chunkX * 341873128712L
                + chunkZ * 132897987541L
                + END_DIMENSION
                + GALACTIC_GREG_OFFSET
                + worldSeed;
    }

    private static String kindForMix(String name) {
        if ("ore.mix.naquadah".equals(name)) {
            return "END_NAQUADAH_ASTEROID";
        }
        if ("ore.mix.tungstate".equals(name)) {
            return "END_SCHEELITE_ASTEROID";
        }
        if ("ore.mix.platinum".equals(name)) {
            return "END_PLATINUM_ASTEROID";
        }
        return null;
    }

    private static String worldgenName(Object layer) throws ReflectiveOperationException {
        if (layer == null) {
            return null;
        }
        try {
            Method getName = layer.getClass().getMethod("getName");
            return (String) getName.invoke(layer);
        } catch (NoSuchMethodException e) {
            return (String) findField(layer.getClass(), "mWorldGenName").get(layer);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static int checkedInt(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("End asteroid query is outside Minecraft's chunk range");
        }
        return (int) value;
    }

    private enum Algorithm {
        CURRENT,
        LEGACY
    }

    private static final class Candidate {
        private final String kind;
        private final int x;
        private final int z;

        private Candidate(String kind, int x, int z) {
            this.kind = kind;
            this.x = x;
            this.z = z;
        }
    }
}
