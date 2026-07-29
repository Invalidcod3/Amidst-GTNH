package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Replays GT5U ore seed selection without generating chunks.
 *
 * <p>GTNH 2.8.x and 2.9.x use different selectors. Stable 2.8.x selects from
 * the legacy global ore lists, while newer GT5U uses {@code WorldgenQuery}
 * and independently hashed attempts. The worker detects the available API at
 * runtime and follows the corresponding random stream.</p>
 */
final class OreVeinPredictor {

    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int CHUNK_CENTER = 8;
    private static final int MAX_CANDIDATES = 262144;
    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    private Algorithm algorithm;
    private Method isOreChunk;

    private Object netherQuery;
    private Object ross128bQuery;
    private Method findRandom;
    private int currentNetherChance;
    private int currentRoss128bChance;

    private List<?> legacyNetherLayers;
    private Field legacyNetherWeight;
    private Field legacyNetherEnabled;
    private int legacyNetherTotalWeight;
    private int legacyNetherChance;
    private int legacyNetherAttempts;

    private Class<?> legacySpaceLayerClass;
    private List<?> legacySpaceLayers;
    private Field legacySpaceWeight;
    private Method legacySpaceEnabledForDimension;
    private Object legacyRoss128bDefinition;
    private int legacySpaceTotalWeight;

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long worldSeed,
            int dimension,
            int minX,
            int minZ,
            int width,
            int height) {
        boolean nether = dimension == -1;
        boolean ross128b = dimension == SpaceDimensionSampler.ross128bDimensionId();
        if (!nether && !ross128b) {
            return new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        }
        prepareRuntimeRegistry();

        String target = nether
                ? "ore.mix.molybdenum"
                : "ore.mix.ross128.Roquesit";
        String kind = nether
                ? "NETHER_MOLYBDENUM_VEIN"
                : "ROSS_128B_ARSENOPYRITE_VEIN";

        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        int minChunkX = checkedInt(ceilingDiv((long) minX - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        int maxChunkX = checkedInt(Math.floorDiv(maxX - 1L - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        int minChunkZ = checkedInt(ceilingDiv((long) minZ - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        int maxChunkZ = checkedInt(Math.floorDiv(maxZ - 1L - CHUNK_CENTER, BLOCKS_PER_CHUNK));
        long count = ((long) maxChunkX - minChunkX + 1L)
                * ((long) maxChunkZ - minChunkZ + 1L);
        if (count > MAX_CANDIDATES) {
            throw new IllegalArgumentException("structure query covers too many GT ore chunks");
        }

        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (!isOreChunk(chunkX, chunkZ)) {
                    continue;
                }
                boolean selected = algorithm == Algorithm.CURRENT
                        ? selectsCurrentTarget(
                                worldSeed,
                                dimension,
                                chunkX,
                                chunkZ,
                                nether,
                                target)
                        : nether
                                ? selectsLegacyNetherTarget(
                                        worldSeed,
                                        dimension,
                                        chunkX,
                                        chunkZ,
                                        target)
                                : selectsLegacyRoss128bTarget(
                                        worldSeed,
                                        chunkX,
                                        chunkZ,
                                        target);
                if (!selected) {
                    continue;
                }
                result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                        kind,
                        algorithm == Algorithm.CURRENT
                                ? "GT ore seed chunk"
                                : "Legacy GT ore seed chunk",
                        chunkX * BLOCKS_PER_CHUNK + CHUNK_CENTER,
                        chunkZ * BLOCKS_PER_CHUNK + CHUNK_CENTER,
                        "POSSIBLE"));
            }
        }
        return result;
    }

    private boolean selectsCurrentTarget(
            long worldSeed,
            int dimension,
            int chunkX,
            int chunkZ,
            boolean nether,
            String target) {
        long oreSeed = oreSeed(worldSeed, dimension, chunkX, chunkZ);
        GtnhXstrRandom percentageRandom = new GtnhXstrRandom(oreSeed);
        int chance = nether ? currentNetherChance : currentRoss128bChance;
        if (percentageRandom.nextInt(100) >= chance) {
            return false;
        }
        Object query = nether ? netherQuery : ross128bQuery;
        try {
            /*
             * GT retries only when the previously selected vein cannot be
             * placed in the generated terrain (for example, its probe block
             * is air). Treating every retry slot as an independent possible
             * result turns a rare weighted mix into "selected at least once
             * in N draws" and greatly overstates its frequency.
             *
             * The first draw is completely determined without generating or
             * mutating chunks, and is the normal final result. Later draws
             * are deliberately omitted because their reachability depends on
             * block-level placement checks that Amidst cannot safely replay.
             */
            long selectionSeed = hashStep(FNV_OFFSET_BASIS, oreSeed);
            selectionSeed = hashStep(selectionSeed, 0);
            Object layer = findRandom.invoke(
                    query,
                    (Random) new GtnhXstrRandom(selectionSeed));
            return layer != null && target.equals(worldgenName(layer));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a current GT ore mix", e);
        }
    }

    private boolean selectsLegacyNetherTarget(
            long worldSeed,
            int dimension,
            int chunkX,
            int chunkZ,
            String target) {
        long oreSeed = oreSeed(worldSeed, dimension, chunkX, chunkZ);
        GtnhXstrRandom random = new GtnhXstrRandom(oreSeed);
        if (random.nextInt(100) >= legacyNetherChance) {
            return false;
        }
        try {
            for (int attempt = 0; attempt < legacyNetherAttempts; attempt++) {
                Object selected = selectWeighted(
                        legacyNetherLayers,
                        legacyNetherWeight,
                        legacyNetherTotalWeight,
                        null,
                        random);
                if (selected == null) {
                    return false;
                }
                // A layer for the Nether normally succeeds and ends the retry
                // loop. Other dimensions' layers are rejected and consume an
                // attempt, matching stable GT5U's global-list selector.
                if (legacyNetherEnabled.getBoolean(selected)) {
                    return target.equals(worldgenName(selected));
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a legacy Nether ore mix", e);
        }
    }

    private boolean selectsLegacyRoss128bTarget(
            long worldSeed,
            int chunkX,
            int chunkZ,
            String target) {
        Random seedRandom = new Random(worldSeed);
        long xSeed = seedRandom.nextLong() >> 3;
        long zSeed = seedRandom.nextLong() >> 3;
        long chunkSeed = xSeed * chunkX + zSeed * chunkZ ^ worldSeed;
        Random forgeRandom = new Random(chunkSeed);
        Random random = new Random(forgeRandom.nextInt());
        try {
            for (int attempt = 0; attempt < 256; attempt++) {
                Object selected = selectWeighted(
                        legacySpaceLayers,
                        legacySpaceWeight,
                        legacySpaceTotalWeight,
                        legacySpaceLayerClass,
                        random);
                if (selected == null) {
                    return false;
                }
                boolean enabled = ((Boolean) legacySpaceEnabledForDimension.invoke(
                        selected,
                        legacyRoss128bDefinition)).booleanValue();
                if (enabled) {
                    return target.equals(worldgenName(selected));
                }
            }
            return false;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a legacy Ross 128b ore mix", e);
        }
    }

    private static Object selectWeighted(
            List<?> layers,
            Field weight,
            int totalWeight,
            Class<?> requiredClass,
            Random random) throws IllegalAccessException {
        if (totalWeight <= 0 || layers.isEmpty()) {
            return null;
        }
        int remaining = random.nextInt(totalWeight);
        for (Object layer : layers) {
            if (requiredClass == null || requiredClass.isInstance(layer)) {
                remaining -= weight.getInt(layer);
            }
            if (remaining <= 0) {
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
            isOreChunk = Class.forName("gregtech.common.GTWorldgenerator")
                    .getMethod("isOreChunk", Integer.TYPE, Integer.TYPE);
            try {
                prepareCurrentRegistry();
                algorithm = Algorithm.CURRENT;
            } catch (ClassNotFoundException e) {
                prepareLegacyRegistry();
                algorithm = Algorithm.LEGACY;
            }
            AmidstGtnhWorkerLog.LOG.info(
                    "GT ore locator is using the {} selector",
                    algorithm == Algorithm.CURRENT ? "current" : "legacy");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GT ore vein registry is unavailable", e);
        }
    }

    private void prepareCurrentRegistry() throws ReflectiveOperationException {
        Class<?> queryClass = Class.forName("gregtech.common.worldgen.WorldgenQuery");
        Method veins = queryClass.getMethod("veins");
        Method inDimension = queryClass.getMethod("inDimension", String.class);
        findRandom = queryClass.getMethod("findRandom", Random.class);
        netherQuery = inDimension.invoke(veins.invoke(null), "Nether");
        ross128bQuery = inDimension.invoke(veins.invoke(null), "ross128b");
        currentNetherChance = dimensionChance("Nether");
        currentRoss128bChance = dimensionChance("Ross128b");
    }

    @SuppressWarnings("unchecked")
    private void prepareLegacyRegistry() throws ReflectiveOperationException {
        Class<?> netherLayerClass = Class.forName("gregtech.common.WorldgenGTOreLayer");
        legacyNetherLayers = (List<?>) netherLayerClass.getField("sList").get(null);
        legacyNetherWeight = netherLayerClass.getField("mWeight");
        legacyNetherEnabled = netherLayerClass.getField("mNether");
        legacyNetherTotalWeight = netherLayerClass.getField("sWeight").getInt(null);
        legacyNetherChance = staticInt("gregtech.api.enums.GTValues", "oreveinPercentage");
        legacyNetherAttempts = staticInt("gregtech.api.enums.GTValues", "oreveinAttempts");

        legacySpaceLayerClass = Class.forName("galacticgreg.WorldgenOreLayerSpace");
        legacySpaceLayers = (List<?>) Class.forName("galacticgreg.GalacticGreg")
                .getField("oreVeinWorldgenList")
                .get(null);
        legacySpaceWeight = legacySpaceLayerClass.getField("mWeight");
        legacySpaceEnabledForDimension = legacySpaceLayerClass.getMethod(
                "isEnabledForDim",
                Class.forName("galacticgreg.api.ModDimensionDef"));
        legacySpaceTotalWeight = legacySpaceLayerClass.getField("sWeight").getInt(null);
        legacyRoss128bDefinition = dimensionDefinition("Ross128b");
    }

    private boolean isOreChunk(int chunkX, int chunkZ) {
        try {
            return ((Boolean) isOreChunk.invoke(
                    null,
                    Integer.valueOf(chunkX),
                    Integer.valueOf(chunkZ))).booleanValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to read the GT ore grid pattern", e);
        }
    }

    private static String worldgenName(Object layer) throws ReflectiveOperationException {
        return (String) layer.getClass().getField("mWorldGenName").get(layer);
    }

    private static int dimensionChance(String enumName) throws ReflectiveOperationException {
        Object definition = dimensionDefinition(enumName);
        Object chance = definition.getClass().getMethod("getOreVeinChance").invoke(definition);
        return ((Integer) chance).intValue();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object dimensionDefinition(String enumName) throws ReflectiveOperationException {
        Class<? extends Enum> dimensionClass =
                (Class<? extends Enum>) Class.forName("galacticgreg.api.enums.DimensionDef");
        Object dimension = Enum.valueOf(dimensionClass, enumName);
        return dimensionClass.getField("modDimensionDef").get(dimension);
    }

    private static int staticInt(String className, String fieldName)
            throws ReflectiveOperationException {
        return Class.forName(className).getField(fieldName).getInt(null);
    }

    private static long oreSeed(
            long worldSeed,
            int dimension,
            int chunkX,
            int chunkZ) {
        return (worldSeed << 16)
                ^ (((dimension & 0xffL) << 56)
                        | ((chunkX & 0xfffffffL) << 28)
                        | (chunkZ & 0xfffffffL));
    }

    private static long hashStep(long state, long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            state = hashByte(state, (byte) ((value >> shift) & 0xff));
        }
        return state;
    }

    private static long hashStep(long state, int value) {
        for (int shift = 24; shift >= 0; shift -= 8) {
            state = hashByte(state, (byte) ((value >> shift) & 0xff));
        }
        return state;
    }

    private static long hashByte(long state, byte value) {
        return (state ^ value) * FNV_PRIME;
    }

    private static long ceilingDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static int checkedInt(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "GT ore query coordinate is outside supported range");
        }
        return (int) value;
    }

    private enum Algorithm {
        CURRENT,
        LEGACY
    }
}
