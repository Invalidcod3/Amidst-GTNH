package amidst.gtnh.worker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/**
 * Runtime-backed biome sampling for Galacticraft and GalaxySpace dimensions.
 *
 * <p>All configurable dimension IDs and biome instances are obtained from the
 * loaded GTNH mods. Io and Pluto use their real seeded world chunk managers;
 * dimensions which genuinely have one biome are filled directly.</p>
 */
final class SpaceDimensionSampler {

    private static final int MAX_CACHED_MANAGERS = 8;
    static final int CERES_DISPLAY_BIOME = 3100;
    static final int ENCELADUS_DISPLAY_BIOME = 3101;
    static final int PROTEUS_DISPLAY_BIOME = 3102;
    private static final int SCOPED_BIOME_BASE = 4096;
    private static final int SCOPED_BIOME_STRIDE = 256;
    private static final String[] SCOPED_BIOME_DIMENSIONS = {
        "galacticraftcore:moon",
        "galacticraftmars:mars",
        "galacticraftasteroids:asteroids",
        "galaxyspace:io",
        "galaxyspace:pluto",
        "amunra:asteroidbeltmehen",
        "galaxyspace:barnarda_c",
        "amunra:anubis",
        "amunra:horus",
        "bartworks:ross128b"
    };
    private static final String MARS_CONFIG =
            "micdoodle8.mods.galacticraft.planets.mars.ConfigManagerMars";
    private static final String ASTEROIDS_CONFIG =
            "micdoodle8.mods.galacticraft.planets.asteroids.ConfigManagerAsteroids";
    private static final String GS_CONFIG = "galaxyspace.core.config.GSConfigDimensions";
    private static final String LEGACY_BARNARDA_C_CONFIG =
            "galaxyspace.systems.BarnardsSystem.core.configs.BRConfigDimensions";
    private static final String[] BARNARDA_C_MANAGERS = {
        "galaxyspace.BarnardsSystem.planets.barnardaC.dimension"
                + ".WorldChunkManagerBarnardaC",
        "galaxyspace.systems.BarnardsSystem.planets.barnardaC.dimension"
                + ".WorldChunkManagerBarnardaC"
    };

    private final Map<WorldChunkManager, ManagerBiomeCache> predictions = new java.util.IdentityHashMap<>();
    private Ross128bBiomeRules rossRules;
    private net.minecraft.world.WorldServer liveContext;
    private WorldChunkManager liveManager;
    private ManagerBiomeCache livePredictions;
    private final Map<ManagerKey, WorldChunkManager> managers =
            new LinkedHashMap<ManagerKey, WorldChunkManager>(16, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<ManagerKey, WorldChunkManager> eldest) {
                    if (size() > MAX_CACHED_MANAGERS) {
                        predictions.remove(eldest.getValue());
                        eldest.getValue().cleanupCache();
                        return true;
                    }
                    return false;
                }
            };

    static int marsDimensionId() {
        return getStaticInt(MARS_CONFIG, "dimensionIDMars");
    }

    static int asteroidsDimensionId() {
        return getStaticInt(ASTEROIDS_CONFIG, "dimensionIDAsteroids");
    }

    static int ceresDimensionId() {
        return getStaticInt(GS_CONFIG, "dimensionIDCeres");
    }

    static int plutoDimensionId() {
        return getStaticInt(GS_CONFIG, "dimensionIDPluto");
    }

    static int ioDimensionId() {
        return getStaticInt(GS_CONFIG, "dimensionIDIo");
    }

    static int enceladusDimensionId() {
        return getStaticInt(GS_CONFIG, "dimensionIDEnceladus");
    }

    static int proteusDimensionId() {
        return getStaticInt(GS_CONFIG, "dimensionIDProteus");
    }

    static int mehenBeltDimensionId() {
        try {
            Object config = Class.forName("de.katzenpapst.amunra.AmunRa")
                    .getField("config")
                    .get(null);
            return config.getClass().getField("dimMehen").getInt(config);
        } catch (ReflectiveOperationException firstFailure) {
            try {
                return getStaticInt(
                        "de.katzenpapst.amunra.config.ConfigDimensions",
                        "dimMehen");
            } catch (IllegalStateException secondFailure) {
                secondFailure.addSuppressed(firstFailure);
                throw secondFailure;
            }
        }
    }

    static int ross128bDimensionId() {
        try {
            Object config = Class.forName("bartworks.common.configs.Configuration")
                    .getField("crossModInteractions")
                    .get(null);
            return config.getClass().getField("ross128BID").getInt(config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Ross 128b dimension configuration is unavailable",
                    e);
        }
    }

    static int barnardaCDimensionId() {
        try {
            return getStaticInt(GS_CONFIG, "dimensionIDBarnardaC");
        } catch (IllegalStateException modernFailure) {
            try {
                return getStaticInt(LEGACY_BARNARDA_C_CONFIG, "dimensionIDBarnardaC");
            } catch (IllegalStateException legacyFailure) {
                legacyFailure.addSuppressed(modernFailure);
                throw legacyFailure;
            }
        }
    }

    static int deepDarkDimensionId() {
        return getStaticInt("com.rwtema.extrautils.ExtraUtils", "underdarkDimID");
    }

    static int anubisDimensionId() {
        return amunRaDimensionId("dimAnubis");
    }

    static int horusDimensionId() {
        return amunRaDimensionId("dimHorus");
    }

    static boolean supports(int dimension) {
        return supports(null, dimension);
    }

    static boolean supports(String dimensionKey, int dimension) {
        return matches(dimensionKey, "galacticraftmars:mars", dimension, marsDimensionId())
                || matches(
                        dimensionKey,
                        "galacticraftasteroids:asteroids",
                        dimension,
                        asteroidsDimensionId())
                || matches(dimensionKey, "galaxyspace:ceres", dimension, ceresDimensionId())
                || matches(dimensionKey, "galaxyspace:io", dimension, ioDimensionId())
                || matches(
                        dimensionKey,
                        "galaxyspace:enceladus",
                        dimension,
                        enceladusDimensionId())
                || matches(dimensionKey, "galaxyspace:proteus", dimension, proteusDimensionId())
                || matches(dimensionKey, "galaxyspace:pluto", dimension, plutoDimensionId())
                || matches(
                        dimensionKey,
                        "amunra:asteroidbeltmehen",
                        dimension,
                        mehenBeltDimensionId())
                || matches(dimensionKey, "bartworks:ross128b", dimension, ross128bDimensionId())
                || matches(
                        dimensionKey,
                        "galaxyspace:barnarda_c",
                        dimension,
                        barnardaCDimensionId())
                || matches(
                        dimensionKey,
                        "extrautilities:deep_dark",
                        dimension,
                        deepDarkDimensionId())
                || matches(dimensionKey, "amunra:anubis", dimension, anubisDimensionId())
                || matches(dimensionKey, "amunra:horus", dimension, horusDimensionId());
    }

    int[] sample(
            long seed,
            int dimension,
            String dimensionKey,
            int x,
            int z,
            int width,
            int height,
            int step) {
        int virtualBiome = virtualBiomeId(dimensionKey, dimension);
        if (virtualBiome >= 0) {
            int[] result = new int[Math.multiplyExact(width, height)];
            Arrays.fill(result, virtualBiome);
            return result;
        }
        BiomeGenBase singleBiome = singleBiome(dimensionKey, dimension);
        int[] result = new int[Math.multiplyExact(width, height)];
        if (singleBiome != null) {
            Arrays.fill(result, displayBiomeId(dimensionKey, singleBiome.biomeID));
            return result;
        }

        boolean ross = matches(dimensionKey, "bartworks:ross128b", dimension, ross128bDimensionId());
        boolean deepDark = matches(dimensionKey, "extrautilities:deep_dark", dimension, deepDarkDimensionId());
        net.minecraft.world.WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(dimension);
        boolean useLive = (ross || deepDark) && world != null && world.getWorldInfo().getSeed() == seed;
        WorldChunkManager manager;
        if (matches(dimensionKey, "galaxyspace:io", dimension, ioDimensionId())) {
            manager = getManager(
                    "galaxyspace.SolarSystem.moons.io.dimension.WorldChunkManagerIo",
                    seed);
        } else if (matches(
                dimensionKey,
                "galaxyspace:pluto",
                dimension,
                plutoDimensionId())) {
            manager = getManager(
                    "galaxyspace.SolarSystem.planets.pluto.dimension.WorldChunkManagerPluto",
                    seed);
        } else if (matches(
                dimensionKey,
                "bartworks:ross128b",
                dimension,
                ross128bDimensionId())) {
            manager = getRoss128bManager(seed);
        } else if (matches(
                dimensionKey,
                "extrautilities:deep_dark",
                dimension,
                deepDarkDimensionId())) {
            manager = getVanillaManager(seed, "deep-dark");
        } else if (matches(
                dimensionKey,
                "galaxyspace:barnarda_c",
                dimension,
                barnardaCDimensionId())) {
            manager = getBarnardaCManager(seed);
        } else {
            throw new IllegalArgumentException(
                    "biome prediction does not support space dimension " + dimension);
        }
        ManagerBiomeCache cache;
        if (useLive) {
            if (liveContext != world || liveManager != world.getWorldChunkManager()) {
                liveContext = world;
                liveManager = world.getWorldChunkManager();
                livePredictions = new ManagerBiomeCache(liveManager);
            }
            cache = livePredictions;
        } else {
            cache = predictions.get(manager);
        }
        if (cache == null) {
            cache = new ManagerBiomeCache(manager);
            predictions.put(manager, cache);
        }
        if (ross && rossRules == null) rossRules = Ross128bBiomeRules.fromRuntime();
        for (int row = 0; row < height; row++) {
            int sampleZ = z + row * step;
            for (int column = 0; column < width; column++) {
                int sampleX = x + column * step;
                BiomeGenBase biome = cache.getBiomeAt(sampleX, sampleZ);
                if (biome == null) {
                    throw new IllegalStateException(
                            "space biome manager returned no biome at "
                                    + sampleX
                                    + ","
                                    + sampleZ);
                }
                if (ross) biome = rossRules.apply(biome);
                // Read existing chunks only; never load/generate terrain for a map.
                // Loaded truth already includes substitutions and player/mod edits.
                if (useLive && world.blockExists(sampleX, 0, sampleZ))
                    biome = world.getBiomeGenForCoords(sampleX, sampleZ);
                result[row * width + column] =
                        displayBiomeId(dimensionKey, biome.biomeID);
            }
        }
        return result;
    }

    void close() {
        for (WorldChunkManager manager : managers.values()) {
            manager.cleanupCache();
        }
        managers.clear();
        predictions.clear();
        liveContext = null;
        liveManager = null;
        livePredictions = null;
    }

    private static BiomeGenBase singleBiome(String dimensionKey, int dimension) {
        if (matches(dimensionKey, "galacticraftmars:mars", dimension, marsDimensionId())) {
            return getStaticBiome(
                    "micdoodle8.mods.galacticraft.planets.mars.world.gen.BiomeGenBaseMars",
                    "marsFlat");
        }
        if (matches(
                dimensionKey,
                "galacticraftasteroids:asteroids",
                dimension,
                asteroidsDimensionId())) {
            return getStaticBiome(
                    "micdoodle8.mods.galacticraft.planets.asteroids.world.gen.BiomeGenBaseAsteroids",
                    "asteroid");
        }
        if (matches(
                dimensionKey,
                "amunra:asteroidbeltmehen",
                dimension,
                mehenBeltDimensionId())) {
            return getStaticBiome(
                    "micdoodle8.mods.galacticraft.planets.asteroids.world.gen.BiomeGenBaseAsteroids",
                    "asteroid");
        }
        if (matches(dimensionKey, "amunra:anubis", dimension, anubisDimensionId())
                || matches(dimensionKey, "amunra:horus", dimension, horusDimensionId())) {
            return getSpaceBiome();
        }
        return null;
    }

    private static BiomeGenBase getSpaceBiome() {
        String[][] candidates = {
            {"galaxyspace.core.world.GSBiomeGenBase", "SPACE"},
            {"galaxyspace.core.world.GSBiomeGenBase", "GSSpace"},
            {"galaxyspace.core.world.gen.GSBiomeGenBase", "GSSpace"},
            {"micdoodle8.mods.galacticraft.core.world.gen.BiomeGenBaseOrbit", "space"}
        };
        IllegalStateException failure =
                new IllegalStateException("registered Space biome is unavailable");
        for (String[] candidate : candidates) {
            try {
                return getStaticBiome(candidate[0], candidate[1]);
            } catch (IllegalStateException e) {
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    private static int virtualBiomeId(String dimensionKey, int dimension) {
        if (matches(dimensionKey, "galaxyspace:ceres", dimension, ceresDimensionId())) {
            return CERES_DISPLAY_BIOME;
        }
        if (matches(
                dimensionKey,
                "galaxyspace:enceladus",
                dimension,
                enceladusDimensionId())) {
            return ENCELADUS_DISPLAY_BIOME;
        }
        if (matches(
                dimensionKey,
                "galaxyspace:proteus",
                dimension,
                proteusDimensionId())) {
            return PROTEUS_DISPLAY_BIOME;
        }
        return -1;
    }

    static int displayBiomeId(String dimensionKey, int biomeId) {
        if (biomeId < 0 || biomeId >= SCOPED_BIOME_STRIDE) {
            throw new IllegalArgumentException(
                    "runtime biome id is outside the Minecraft 1.7 range: " + biomeId);
        }
        for (int index = 0; index < SCOPED_BIOME_DIMENSIONS.length; index++) {
            if (SCOPED_BIOME_DIMENSIONS[index].equals(dimensionKey)) {
                return SCOPED_BIOME_BASE + index * SCOPED_BIOME_STRIDE + biomeId;
            }
        }
        return biomeId;
    }

    static String[] scopedBiomeDimensionKeys() {
        return SCOPED_BIOME_DIMENSIONS.clone();
    }

    static Map<String, BiomeGenBase[]> scopedBiomeSources() {
        Map<String, BiomeGenBase[]> result =
                new LinkedHashMap<String, BiomeGenBase[]>();
        result.put(
                "galacticraftcore:moon",
                new BiomeGenBase[] {MoonBiomeSampler.biome()});
        result.put(
                "galacticraftmars:mars",
                new BiomeGenBase[] {
                    getStaticBiome(
                            "micdoodle8.mods.galacticraft.planets.mars.world.gen"
                                    + ".BiomeGenBaseMars",
                            "marsFlat")
                });
        BiomeGenBase asteroid = getStaticBiome(
                "micdoodle8.mods.galacticraft.planets.asteroids.world.gen"
                        + ".BiomeGenBaseAsteroids",
                "asteroid");
        result.put(
                "galacticraftasteroids:asteroids",
                new BiomeGenBase[] {asteroid});
        result.put(
                "galaxyspace:io",
                getStaticBiomes(
                        "galaxyspace.SolarSystem.moons.io.world.BiomeGenBaseIo",
                        "IO",
                        "IO_ASH"));
        result.put(
                "galaxyspace:pluto",
                getStaticBiomes(
                        "galaxyspace.SolarSystem.planets.pluto.world.BiomeGenPluto",
                        "PLUTO_1",
                        "PLUTO_2",
                        "PLUTO_3",
                        "PLUTO_4"));
        result.put(
                "amunra:asteroidbeltmehen",
                new BiomeGenBase[] {asteroid});
        result.put("galaxyspace:barnarda_c", getBarnardaCBiomes());
        BiomeGenBase space = getSpaceBiome();
        result.put("amunra:anubis", new BiomeGenBase[] {space});
        result.put("amunra:horus", new BiomeGenBase[] {space});

        BiomeGenBase[] registry = BiomeGenBase.getBiomeGenArray();
        int count = 0;
        for (BiomeGenBase biome : registry) {
            if (biome != null) {
                count++;
            }
        }
        BiomeGenBase[] rossBiomes = new BiomeGenBase[count];
        int index = 0;
        for (BiomeGenBase biome : registry) {
            if (biome != null) {
                rossBiomes[index++] = biome;
            }
        }
        result.put("bartworks:ross128b", rossBiomes);
        return result;
    }

    private static BiomeGenBase[] getBarnardaCBiomes() {
        try {
            return getStaticBiomes(
                    "galaxyspace.BarnardsSystem.planets.barnardaC.world"
                            + ".BiomeGenBaseBarnardaC",
                    "BARNARDA_C_SHORES",
                    "BARNARDA_C_HILLS",
                    "BARNARDA_C_LOW_PLAINS",
                    "BARNARDA_C_FLOWERS",
                    "BARNARDA_C_OCEANS");
        } catch (IllegalStateException modernFailure) {
            try {
                return getStaticBiomes(
                        "galaxyspace.core.world.gen.GSBiomeGenBase",
                        "GSSpace",
                        "GSSpaceLowPlains",
                        "GSSpaceDeepOceans",
                        "GSSpaceOceans");
            } catch (IllegalStateException legacyFailure) {
                legacyFailure.addSuppressed(modernFailure);
                throw legacyFailure;
            }
        }
    }

    private static BiomeGenBase[] getStaticBiomes(
            String className,
            String... fieldNames) {
        BiomeGenBase[] result = new BiomeGenBase[fieldNames.length];
        for (int index = 0; index < fieldNames.length; index++) {
            result[index] = getStaticBiome(className, fieldNames[index]);
        }
        return result;
    }

    static boolean matches(
            String actualKey,
            String expectedKey,
            int actualId,
            int expectedId) {
        return actualKey == null || actualKey.isEmpty()
                ? actualId == expectedId
                : expectedKey.equals(actualKey);
    }

    private WorldChunkManager getRoss128bManager(long seed) {
        return getVanillaManager(seed, "ross128b");
    }

    private WorldChunkManager getBarnardaCManager(long seed) {
        IllegalStateException failure = null;
        for (String className : BARNARDA_C_MANAGERS) {
            try {
                return getManager(className, seed);
            } catch (IllegalStateException e) {
                if (failure == null) {
                    failure = new IllegalStateException(
                            "no compatible Barnarda C biome manager is available");
                }
                failure.addSuppressed(e);
            }
        }
        throw failure;
    }

    private WorldChunkManager getVanillaManager(long seed, String purpose) {
        // Galacticraft and Extra Utilities both construct WorldChunkManager(World).
        // Reproduce that world's terrain type, rather than forcing DEFAULT.
        net.minecraft.world.WorldServer world = net.minecraftforge.common.DimensionManager.getWorld(0);
        WorldType type = world != null && world.getWorldInfo().getSeed() == seed
                ? world.getWorldInfo().getTerrainType() : WorldType.parseWorldType("RWG");
        if (type == null) type = WorldType.DEFAULT;
        String className = WorldChunkManager.class.getName() + ":" + purpose + ":" + type.getWorldTypeName();
        ManagerKey key = new ManagerKey(className, seed);
        WorldChunkManager manager = managers.get(key);
        if (manager == null) {
            manager = new WorldChunkManager(seed, type);
            managers.put(key, manager);
            AmidstGtnhWorkerLog.LOG.info(
                    "Prepared {} biome preview context for seed {}, world type {}",
                    purpose, Long.valueOf(seed), type.getWorldTypeName());
        }
        return manager;
    }

    private WorldChunkManager getManager(String className, long seed) {
        ManagerKey key = new ManagerKey(className, seed);
        WorldChunkManager manager = managers.get(key);
        if (manager == null) {
            try {
                Class<?> managerClass = Class.forName(className);
                try {
                    Constructor<?> constructor = managerClass.getConstructor(Long.TYPE);
                    manager = (WorldChunkManager) constructor.newInstance(Long.valueOf(seed));
                } catch (NoSuchMethodException singleArgumentUnavailable) {
                    Constructor<?> constructor =
                            managerClass.getConstructor(Long.TYPE, WorldType.class);
                    manager = (WorldChunkManager)
                            constructor.newInstance(Long.valueOf(seed), WorldType.DEFAULT);
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "space biome manager " + className + " is unavailable",
                        e);
            }
            managers.put(key, manager);
            AmidstGtnhWorkerLog.LOG.info(
                    "Prepared {} biome preview context for seed {}",
                    className,
                    Long.valueOf(seed));
        }
        return manager;
    }

    private static int getStaticInt(String className, String fieldName) {
        try {
            return Class.forName(className).getField(fieldName).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "space dimension configuration "
                            + className
                            + "."
                            + fieldName
                            + " is unavailable",
                    e);
        }
    }

    private static int amunRaDimensionId(String fieldName) {
        try {
            Object config = Class.forName("de.katzenpapst.amunra.AmunRa")
                    .getField("config")
                    .get(null);
            return config.getClass().getField(fieldName).getInt(config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Amun-Ra dimension configuration " + fieldName + " is unavailable",
                    e);
        }
    }

    private static BiomeGenBase getStaticBiome(String className, String fieldName) {
        try {
            Field field = Class.forName(className).getField(fieldName);
            return (BiomeGenBase) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "space biome " + className + "." + fieldName + " is unavailable",
                    e);
        }
    }

    private static final class ManagerKey {

        private final String className;
        private final long seed;

        private ManagerKey(String className, long seed) {
            this.className = className;
            this.seed = seed;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ManagerKey)) {
                return false;
            }
            ManagerKey key = (ManagerKey) other;
            return seed == key.seed && className.equals(key.className);
        }

        @Override
        public int hashCode() {
            int result = className.hashCode();
            result = 31 * result + (int) (seed ^ seed >>> 32);
            return result;
        }
    }
}
