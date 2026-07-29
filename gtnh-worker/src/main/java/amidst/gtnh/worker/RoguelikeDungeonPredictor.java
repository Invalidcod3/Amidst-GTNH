package amidst.gtnh.worker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;

/**
 * Replays the seed-only part of Roguelike Dungeons' natural-spawn algorithm.
 *
 * The final validity test reads generated blocks around the entrance. The
 * worker deliberately does not generate chunks, so these records are possible
 * locations: each record is the first deterministic attempt whose final RWG
 * biome passes Roguelike Dungeons' biome rejection rules.
 */
final class RoguelikeDungeonPredictor {

    private static final int REGION_SALT = 10387312;
    private static final int MIN_DISTANCE = 40;
    private static final int MAX_DISTANCE = 100;
    private static final int ATTEMPTS = 50;
    private static final int MAX_REGIONS = 4096;

    private final Class<?> rogueConfigClass;
    private final Method getConfigBoolean;
    private final Method getConfigInt;
    private final Object naturalSpawnOption;
    private final Object spawnFrequencyOption;
    private final Object settingsResolver;
    private final Class<?> worldEditorClass;
    private final Constructor<?> coordConstructor;
    private final Method coordGetX;
    private final Method coordGetZ;
    private final Method getSettings;
    private final Method settingsGetTower;
    private final Method towerSettingsGetTower;

    RoguelikeDungeonPredictor() {
        try {
            rogueConfigClass = Class.forName("greymerk.roguelike.config.RogueConfig");
            getConfigBoolean = rogueConfigClass.getMethod("getBoolean", rogueConfigClass);
            getConfigInt = rogueConfigClass.getMethod("getInt", rogueConfigClass);
            naturalSpawnOption = enumConstant(rogueConfigClass, "DONATURALSPAWN");
            spawnFrequencyOption = enumConstant(rogueConfigClass, "SPAWNFREQUENCY");

            Class<?> dungeonClass = Class.forName("greymerk.roguelike.dungeon.Dungeon");
            Field resolverField = dungeonClass.getField("settingsResolver");
            settingsResolver = resolverField.get(null);
            if (settingsResolver == null) {
                throw new IllegalStateException("Roguelike Dungeons settings resolver is not initialized");
            }

            worldEditorClass = Class.forName("greymerk.roguelike.worldgen.IWorldEditor");
            Class<?> coordClass = Class.forName("greymerk.roguelike.worldgen.Coord");
            coordConstructor = coordClass.getConstructor(
                    Integer.TYPE,
                    Integer.TYPE,
                    Integer.TYPE);
            coordGetX = coordClass.getMethod("getX");
            coordGetZ = coordClass.getMethod("getZ");

            getSettings = settingsResolver.getClass().getMethod(
                    "getSettings",
                    worldEditorClass,
                    Random.class,
                    coordClass);
            Class<?> settingsClass = Class.forName("greymerk.roguelike.dungeon.settings.ISettings");
            settingsGetTower = settingsClass.getMethod("getTower");
            towerSettingsGetTower = settingsGetTower.getReturnType().getMethod("getTower");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Roguelike Dungeons 1.6.6-GTNH prediction API is unavailable",
                    e);
        }
    }

    List<StructureDescriptor> predict(
            long seed,
            int dimension,
            int minX,
            int minZ,
            int width,
            int height,
            SurfaceBiomeSampler sampler) {
        if (dimension != 0) {
            throw new IllegalArgumentException("Roguelike prediction supports only overworld dimension 0");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("structure query width and height must be positive");
        }
        if (!getBoolean(naturalSpawnOption)) {
            return new ArrayList<StructureDescriptor>();
        }

        long maxXExclusive = (long) minX + width;
        long maxZExclusive = (long) minZ + height;
        if (maxXExclusive > (long) Integer.MAX_VALUE + 1L
                || maxZExclusive > (long) Integer.MAX_VALUE + 1L) {
            throw new IllegalArgumentException("structure query coordinates overflow the Minecraft integer range");
        }

        int frequency = getInt(spawnFrequencyOption);
        int min = Math.max(2, 8 * frequency / 10);
        int max = Math.max(8, 32 * frequency / 10);
        if (max <= min) {
            throw new IllegalStateException("Roguelike Dungeons spawn frequency produced an invalid region size");
        }

        int minCandidateChunkX = floorDiv((long) minX - (MAX_DISTANCE - 1) - 4L, 16);
        int maxCandidateChunkX = floorDiv(maxXExclusive - 1L + (MAX_DISTANCE - 1) - 4L, 16);
        int minCandidateChunkZ = floorDiv((long) minZ - (MAX_DISTANCE - 1) - 4L, 16);
        int maxCandidateChunkZ = floorDiv(maxZExclusive - 1L + (MAX_DISTANCE - 1) - 4L, 16);

        int minRegionX = Math.floorDiv(minCandidateChunkX, max);
        int maxRegionX = Math.floorDiv(maxCandidateChunkX, max);
        int minRegionZ = Math.floorDiv(minCandidateChunkZ, max);
        int maxRegionZ = Math.floorDiv(maxCandidateChunkZ, max);
        long regionCount = (long) (maxRegionX - minRegionX + 1) * (maxRegionZ - minRegionZ + 1);
        if (regionCount > MAX_REGIONS) {
            throw new IllegalArgumentException("structure query covers too many Roguelike spawn regions");
        }

        Object editor = createWorldEditor(seed, dimension, sampler);
        List<StructureDescriptor> result = new ArrayList<StructureDescriptor>();
        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                int chunkX;
                int chunkZ;
                Random regionRandom = seededRandom(seed, regionX, regionZ, REGION_SALT);
                chunkX = regionX * max + regionRandom.nextInt(max - min);
                chunkZ = regionZ * max + regionRandom.nextInt(max - min);

                PredictedDungeon dungeon = predictFromChunk(
                        seed,
                        dimension,
                        chunkX,
                        chunkZ,
                        sampler,
                        editor);
                if (dungeon != null
                        && dungeon.x >= minX
                        && dungeon.x < maxXExclusive
                        && dungeon.z >= minZ
                        && dungeon.z < maxZExclusive) {
                    result.add(new StructureDescriptor(
                            "ROGUELIKE_DUNGEON",
                            dungeon.type,
                            dungeon.x,
                            dungeon.z,
                            "POSSIBLE"));
                }
            }
        }
        return result;
    }

    private PredictedDungeon predictFromChunk(
            long seed,
            int dimension,
            int chunkX,
            int chunkZ,
            SurfaceBiomeSampler sampler,
            Object editor) {
        Random random = forgeChunkRandom(seed, chunkX, chunkZ);
        int originX = chunkX * 16 + 4;
        int originZ = chunkZ * 16 + 4;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int distance = MIN_DISTANCE + random.nextInt(MAX_DISTANCE - MIN_DISTANCE);
            double angle = random.nextDouble() * 2.0D * Math.PI;
            int x = originX + (int) (Math.cos(angle) * distance);
            int z = originZ + (int) (Math.sin(angle) * distance);
            BiomeGenBase biome = sampler.getBiomeAt(x, z);
            if (biome == null || isRejectedBiome(biome)) {
                continue;
            }
            String type = resolveType(editor, random, x, z);
            return type == null ? null : new PredictedDungeon(x, z, type);
        }
        return null;
    }

    private String resolveType(Object editor, Random random, int x, int z) {
        try {
            Object coord = coordConstructor.newInstance(
                    Integer.valueOf(x),
                    Integer.valueOf(0),
                    Integer.valueOf(z));
            Object settings = getSettings.invoke(settingsResolver, editor, random, coord);
            if (settings == null) {
                return null;
            }
            Object towerSettings = settingsGetTower.invoke(settings);
            Object tower = towerSettingsGetTower.invoke(towerSettings);
            return normalizeTowerType(String.valueOf(tower));
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read Roguelike dungeon type", e);
        } catch (InstantiationException e) {
            throw new IllegalStateException("cannot create Roguelike dungeon coordinate", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Roguelike dungeon type resolution failed", cause);
        }
    }

    private Object createWorldEditor(
            final long seed,
            final int dimension,
            final SurfaceBiomeSampler sampler) {
        InvocationHandler handler = new InvocationHandler() {

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                String name = method.getName();
                if ("getBiome".equals(name)) {
                    Object coord = args[0];
                    int x = ((Integer) coordGetX.invoke(coord)).intValue();
                    int z = ((Integer) coordGetZ.invoke(coord)).intValue();
                    return sampler.getBiomeAt(x, z);
                }
                if ("getDimension".equals(name)) {
                    return Integer.valueOf(dimension);
                }
                if ("getSeed".equals(name)) {
                    return Long.valueOf(seed);
                }
                if ("getSeededRandom".equals(name)) {
                    return seededRandom(
                            seed,
                            ((Integer) args[0]).intValue(),
                            ((Integer) args[1]).intValue(),
                            ((Integer) args[2]).intValue());
                }
                if ("toString".equals(name)) {
                    return "Amidst seed-only Roguelike world editor";
                }
                if ("hashCode".equals(name)) {
                    return Integer.valueOf(System.identityHashCode(proxy));
                }
                if ("equals".equals(name)) {
                    return Boolean.valueOf(proxy == args[0]);
                }
                throw new UnsupportedOperationException(
                        "seed-only Roguelike prediction cannot call IWorldEditor." + name);
            }
        };
        return Proxy.newProxyInstance(
                worldEditorClass.getClassLoader(),
                new Class<?>[] { worldEditorClass },
                handler);
    }

    private boolean getBoolean(Object option) {
        try {
            return ((Boolean) getConfigBoolean.invoke(null, option)).booleanValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read Roguelike Dungeons boolean configuration", e);
        }
    }

    private int getInt(Object option) {
        try {
            return ((Integer) getConfigInt.invoke(null, option)).intValue();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read Roguelike Dungeons integer configuration", e);
        }
    }

    private static boolean isRejectedBiome(BiomeGenBase biome) {
        return BiomeDictionary.isBiomeOfType(biome, BiomeDictionary.Type.RIVER)
                || BiomeDictionary.isBiomeOfType(biome, BiomeDictionary.Type.BEACH)
                || BiomeDictionary.isBiomeOfType(biome, BiomeDictionary.Type.MUSHROOM)
                || BiomeDictionary.isBiomeOfType(biome, BiomeDictionary.Type.OCEAN);
    }

    private static Random seededRandom(long seed, int x, int z, int salt) {
        return new Random(
                (long) x * 341873128712L
                        + (long) z * 132897987541L
                        + seed
                        + (long) salt);
    }

    static Random forgeChunkRandom(long seed, int chunkX, int chunkZ) {
        Random random = new Random(seed);
        long xSeed = random.nextLong() >> 3;
        long zSeed = random.nextLong() >> 3;
        random.setSeed((xSeed * (long) chunkX + zSeed * (long) chunkZ) ^ seed);
        return random;
    }

    private static int floorDiv(long value, int divisor) {
        long result = Math.floorDiv(value, (long) divisor);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("structure query coordinate is outside supported range");
        }
        return (int) result;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Object enumConstant(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass, name);
    }

    private static String normalizeTowerType(String tower) {
        String normalized = tower == null ? "" : tower.toUpperCase(Locale.ROOT);
        if ("PYRAMID".equals(normalized)) return "DESERT";
        if ("HOUSE".equals(normalized)) return "FOREST";
        if ("BUNKER".equals(normalized)) return "ICE";
        if ("JUNGLE".equals(normalized)) return "JUNGLE";
        if ("ETHO".equals(normalized)) return "MESA";
        if ("ENIKO".equals(normalized)) return "MOUNTAIN";
        if ("ROGUE".equals(normalized)) return "PLAINS";
        if ("WITCH".equals(normalized)) return "SWAMP";
        return normalized.isEmpty() ? "UNKNOWN" : normalized;
    }

    static final class StructureDescriptor {

        final String kind;
        final String subtype;
        final int x;
        final int z;
        final String certainty;

        StructureDescriptor(String kind, String subtype, int x, int z, String certainty) {
            this.kind = kind;
            this.subtype = subtype;
            this.x = x;
            this.z = z;
            this.certainty = certainty;
        }
    }

    private static final class PredictedDungeon {

        private final int x;
        private final int z;
        private final String type;

        private PredictedDungeon(int x, int z, String type) {
            this.x = x;
            this.z = z;
            this.type = type;
        }
    }
}
