package amidst.gtnh.worker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/**
 * Seed-only Twilight Forest biome sampler.
 *
 * <p>The worker deliberately has no compile-time dependency on Twilight
 * Forest. GTNH supplies the mod at runtime, while reflection keeps this small
 * companion mod buildable in isolation.</p>
 */
final class TwilightForestBiomeSampler {

    private static final String MOD_CLASS = "twilightforest.TwilightForestMod";
    private static final String MANAGER_CLASS = "twilightforest.world.TFWorldChunkManager";
    private static final String BIOMES_CLASS = "twilightforest.biomes.TFBiomeBase";

    private final WorldChunkManager manager;
    private final Map<BiomeGenBase, String> featureBiomeKeys;
    private final boolean oldMapGen;

    TwilightForestBiomeSampler(long seed) {
        try {
            Class<?> managerClass = Class.forName(MANAGER_CLASS);
            Constructor<?> constructor = managerClass.getConstructor(long.class, WorldType.class);
            manager = (WorldChunkManager) constructor.newInstance(Long.valueOf(seed), WorldType.DEFAULT);
            Class<?> biomesClass = Class.forName(BIOMES_CLASS);
            featureBiomeKeys = createFeatureBiomeKeys(biomesClass);
            oldMapGen = Class.forName(MOD_CLASS).getField("oldMapGen").getBoolean(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            throw new IllegalStateException(
                    "Twilight Forest biome generator is unavailable; install the GTNH Twilight Forest mod",
                    e);
        }
    }

    static int configuredDimensionId() {
        try {
            return Class.forName(MOD_CLASS).getField("dimensionID").getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Twilight Forest dimension id is unavailable; install the GTNH Twilight Forest mod",
                    e);
        }
    }

    BiomeGenBase getBiomeAt(int blockX, int blockZ) {
        return manager.getBiomeGenAt(blockX, blockZ);
    }

    int[] getQuarterResolutionBiomeIds(
            int quarterX,
            int quarterZ,
            int width,
            int height) {
        /*
         * AMIDST's biome raster is the raw 1:4 GenLayer map, not an averaged
         * block-resolution image. This virtual call dispatches to
         * TFWorldChunkManager#getBiomesForGeneration and therefore reads
         * Twilight Forest's unzoomedBiomes layer exactly like AMIDST does for
         * vanilla dimensions.
         */
        BiomeGenBase[] biomes =
                manager.getBiomesForGeneration(null, quarterX, quarterZ, width, height);
        int[] ids = new int[width * height];
        for (int index = 0; index < ids.length; index++) {
            BiomeGenBase biome = biomes[index];
            if (biome == null) {
                throw new IllegalStateException(
                        "Twilight Forest quarter-resolution sampler returned no biome at index "
                                + index);
            }
            ids[index] = biome.biomeID;
        }
        return ids;
    }

    String getFeatureBiomeKey(int blockX, int blockZ) {
        return featureBiomeKeys.get(requireBiome(blockX, blockZ));
    }

    boolean isOldMapGen() {
        return oldMapGen;
    }

    private BiomeGenBase requireBiome(int blockX, int blockZ) {
        BiomeGenBase biome = getBiomeAt(blockX, blockZ);
        if (biome == null) {
            throw new IllegalStateException(
                    "Twilight Forest sampler returned no biome at " + blockX + "," + blockZ);
        }
        return biome;
    }

    private static Map<BiomeGenBase, String> createFeatureBiomeKeys(Class<?> biomesClass)
            throws ReflectiveOperationException {
        Map<BiomeGenBase, String> result = new IdentityHashMap<BiomeGenBase, String>();
        putBiome(result, biomesClass, "glacier", "GLACIER");
        putBiome(result, biomesClass, "tfSnow", "SNOW");
        putBiome(result, biomesClass, "tfLake", "LAKE");
        putBiome(result, biomesClass, "enchantedForest", "ENCHANTED_FOREST");
        putBiome(result, biomesClass, "fireSwamp", "FIRE_SWAMP");
        putBiome(result, biomesClass, "tfSwamp", "SWAMP");
        putBiome(result, biomesClass, "clearing", "CLEARING");
        putBiome(result, biomesClass, "oakSavanna", "OAK_SAVANNA");
        putBiome(result, biomesClass, "darkForest", "DARK_FOREST");
        putBiome(result, biomesClass, "darkForestCenter", "DARK_FOREST_CENTER");
        putBiome(result, biomesClass, "highlandsCenter", "HIGHLANDS_CENTER");
        putBiome(result, biomesClass, "highlands", "HIGHLANDS");
        putBiome(result, biomesClass, "deepMushrooms", "DEEP_MUSHROOMS");
        return result;
    }

    private static void putBiome(
            Map<BiomeGenBase, String> target,
            Class<?> biomesClass,
            String fieldName,
            String key) throws ReflectiveOperationException {
        target.put(biomeField(biomesClass, fieldName), key);
    }

    private static BiomeGenBase biomeField(Class<?> biomesClass, String name)
            throws ReflectiveOperationException {
        Field field = biomesClass.getField(name);
        return (BiomeGenBase) field.get(null);
    }
}
