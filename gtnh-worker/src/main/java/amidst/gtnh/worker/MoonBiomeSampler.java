package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.util.Arrays;

import net.minecraft.world.biome.BiomeGenBase;

/**
 * Runtime access to Galacticraft's configurable Moon dimension and its single
 * biome. Reflection keeps the worker build independent of Galacticraft while
 * still following the exact configuration of the running GTNH instance.
 */
final class MoonBiomeSampler {

    private static final String CONFIG_CLASS =
            "micdoodle8.mods.galacticraft.core.util.ConfigManagerCore";
    private static final String BIOME_CLASS =
            "micdoodle8.mods.galacticraft.core.world.gen.BiomeGenBaseMoon";

    private MoonBiomeSampler() {}

    static int configuredDimensionId() {
        return getStaticInt(CONFIG_CLASS, "idDimensionMoon");
    }

    static boolean isVillageGenerationEnabled() {
        return !getStaticBoolean(CONFIG_CLASS, "disableMoonVillageGen");
    }

    static BiomeGenBase biome() {
        try {
            Class<?> biomeClass = Class.forName(BIOME_CLASS);
            Field moonFlat = biomeClass.getField("moonFlat");
            return (BiomeGenBase) moonFlat.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Galacticraft Moon biome API is unavailable",
                    e);
        }
    }

    static int[] sample(int width, int height) {
        int[] result = new int[Math.multiplyExact(width, height)];
        Arrays.fill(result, biome().biomeID);
        return result;
    }

    private static int getStaticInt(String className, String fieldName) {
        try {
            return Class.forName(className).getField(fieldName).getInt(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Galacticraft Moon dimension configuration is unavailable",
                    e);
        }
    }

    private static boolean getStaticBoolean(String className, String fieldName) {
        try {
            return Class.forName(className).getField(fieldName).getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Galacticraft Moon village configuration is unavailable",
                    e);
        }
    }
}
