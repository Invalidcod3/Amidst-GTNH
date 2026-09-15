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

    private Algorithm algorithm;

    private Object currentEndDefinition;
    private Object currentWorldDefinition;
    private final AsteroidProspecting currentProspecting = new AsteroidProspecting();

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
                        algorithm == Algorithm.CURRENT ? "EXACT_SEED" : "POSSIBLE", candidate.y));
            }
        }
        return result;
    }

    private Candidate selectCurrent(long worldSeed, int chunkX, int chunkZ) {
        net.minecraft.world.WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(END_DIMENSION);
        if (world == null || world.getSeed() != worldSeed) return null;
        try {
            if (!(Boolean) ProspectingReflection.call(currentWorldDefinition, "generatesAsteroids")) return null;
            Object effective = AsteroidProspecting.effectiveDefinition(worldSeed, END_DIMENSION,
                    currentWorldDefinition, chunkX, chunkZ);
            if (effective != currentEndDefinition) return null;
            amidst.gtnh.prospecting.ProspectingData.Deposit deposit = currentProspecting.predict(
                    worldSeed, END_DIMENSION, chunkX, chunkZ, effective);
            if (deposit == null || !"ASTEROID_VEIN".equals(deposit.kind)) return null;
            String kind = kindForMix(deposit.id.substring("asteroid.vein:".length()));
            return kind == null ? null : new Candidate(kind, deposit.x, deposit.z, deposit.y);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to select a GalacticGreg End asteroid", e);
        }
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

    private void prepareCurrentRegistry() throws ReflectiveOperationException {
        Class<?> definitions = Class.forName("galacticgreg.api.enums.DimensionDef");
        currentEndDefinition = AsteroidProspecting.endAsteroids();
        currentWorldDefinition = ProspectingReflection.field(
                ProspectingReflection.field(definitions, "TheEnd"), "modDimensionDef");
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
        private final Integer y;

        private Candidate(String kind, int x, int z) {
            this(kind, x, z, null);
        }
        private Candidate(String kind, int x, int z, Integer y) {
            this.kind = kind;
            this.x = x;
            this.z = z;
            this.y = y;
        }
    }
}
