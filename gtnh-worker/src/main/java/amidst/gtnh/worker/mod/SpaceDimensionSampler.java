package amidst.gtnh.worker.mod;

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
    private static final String MARS_CONFIG =
            "micdoodle8.mods.galacticraft.planets.mars.ConfigManagerMars";
    private static final String ASTEROIDS_CONFIG =
            "micdoodle8.mods.galacticraft.planets.asteroids.ConfigManagerAsteroids";
    private static final String GS_CONFIG = "galaxyspace.core.config.GSConfigDimensions";

    private final Map<ManagerKey, WorldChunkManager> managers =
            new LinkedHashMap<ManagerKey, WorldChunkManager>(16, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<ManagerKey, WorldChunkManager> eldest) {
                    if (size() > MAX_CACHED_MANAGERS) {
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

    static boolean supports(int dimension) {
        return dimension == marsDimensionId()
                || dimension == asteroidsDimensionId()
                || dimension == ceresDimensionId()
                || dimension == ioDimensionId()
                || dimension == enceladusDimensionId()
                || dimension == proteusDimensionId()
                || dimension == plutoDimensionId()
                || dimension == mehenBeltDimensionId()
                || dimension == ross128bDimensionId();
    }

    int[] sample(
            long seed,
            int dimension,
            int x,
            int z,
            int width,
            int height,
            int step) {
        BiomeGenBase singleBiome = singleBiome(dimension);
        int[] result = new int[Math.multiplyExact(width, height)];
        if (singleBiome != null) {
            Arrays.fill(result, singleBiome.biomeID);
            return result;
        }

        WorldChunkManager manager;
        if (dimension == ioDimensionId()) {
            manager = getManager(
                    "galaxyspace.SolarSystem.moons.io.dimension.WorldChunkManagerIo",
                    seed);
        } else if (dimension == plutoDimensionId()) {
            manager = getManager(
                    "galaxyspace.SolarSystem.planets.pluto.dimension.WorldChunkManagerPluto",
                    seed);
        } else if (dimension == ross128bDimensionId()) {
            manager = getRoss128bManager(seed);
        } else {
            throw new IllegalArgumentException(
                    "biome prediction does not support space dimension " + dimension);
        }
        for (int row = 0; row < height; row++) {
            int sampleZ = z + row * step;
            for (int column = 0; column < width; column++) {
                int sampleX = x + column * step;
                BiomeGenBase biome = manager.getBiomeGenAt(sampleX, sampleZ);
                if (biome == null) {
                    throw new IllegalStateException(
                            "space biome manager returned no biome at "
                                    + sampleX
                                    + ","
                                    + sampleZ);
                }
                result[row * width + column] = biome.biomeID;
            }
        }
        return result;
    }

    void close() {
        for (WorldChunkManager manager : managers.values()) {
            manager.cleanupCache();
        }
        managers.clear();
    }

    private static BiomeGenBase singleBiome(int dimension) {
        if (dimension == marsDimensionId()) {
            return getStaticBiome(
                    "micdoodle8.mods.galacticraft.planets.mars.world.gen.BiomeGenBaseMars",
                    "marsFlat");
        }
        if (dimension == asteroidsDimensionId()) {
            return getStaticBiome(
                    "micdoodle8.mods.galacticraft.planets.asteroids.world.gen.BiomeGenBaseAsteroids",
                    "asteroid");
        }
        if (dimension == mehenBeltDimensionId()) {
            return getStaticBiome(
                    "micdoodle8.mods.galacticraft.planets.asteroids.world.gen.BiomeGenBaseAsteroids",
                    "asteroid");
        }
        if (dimension == ceresDimensionId()
                || dimension == enceladusDimensionId()
                || dimension == proteusDimensionId()) {
            return getStaticBiome("galaxyspace.core.world.GSBiomeGenBase", "SPACE");
        }
        return null;
    }

    private WorldChunkManager getRoss128bManager(long seed) {
        String className = WorldChunkManager.class.getName() + ":ross128b";
        ManagerKey key = new ManagerKey(className, seed);
        WorldChunkManager manager = managers.get(key);
        if (manager == null) {
            manager = new WorldChunkManager(seed, WorldType.DEFAULT);
            managers.put(key, manager);
            AmidstGtnhWorkerLog.LOG.info(
                    "Prepared Ross 128b biome preview context for seed {}",
                    Long.valueOf(seed));
        }
        return manager;
    }

    private WorldChunkManager getManager(String className, long seed) {
        ManagerKey key = new ManagerKey(className, seed);
        WorldChunkManager manager = managers.get(key);
        if (manager == null) {
            try {
                Class<?> managerClass = Class.forName(className);
                Constructor<?> constructor = managerClass.getConstructor(Long.TYPE);
                manager = (WorldChunkManager) constructor.newInstance(Long.valueOf(seed));
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
