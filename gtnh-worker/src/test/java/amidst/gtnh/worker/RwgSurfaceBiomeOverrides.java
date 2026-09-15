package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.biome.BiomeGenBase;

/**
 * Biome-array writes in RWG alpha 1.5.2's surface painters, AFTER river assignment.
 * This is not a block generator. See docs/archive/gtnh-biome-accuracy.md for the upstream
 * call chain and limits (generation events and later biome edits are not replayed).
 */
final class RwgSurfaceBiomeOverrides {
    interface HeightSource {
        float sample() throws ReflectiveOperationException;
    }

    private final Object perlin;
    private final Method noise2;
    private final Map<Object, List<DuneRule>> rules = new IdentityHashMap<>();

    RwgSurfaceBiomeOverrides(Object perlin, Method noise2) {
        this.perlin = perlin;
        this.noise2 = noise2;
    }

    BiomeGenBase apply(Object realisticBiome, BiomeGenBase biome, int x, int z, HeightSource height)
            throws ReflectiveOperationException {
        List<DuneRule> biomeRules = rules.get(realisticBiome);
        if (biomeRules == null) {
            biomeRules = discover(realisticBiome);
            rules.put(realisticBiome, biomeRules);
        }
        if (biomeRules.isEmpty()) {
            return biome; // Ordinary biomes retain the fast biome-only path.
        }
        // generateTerrain truncates the blended height to int, caps its block
        // column at 255, and produces no stone at all if that height is negative.
        int top = Math.min(255, (int) height.sample());
        for (DuneRule rule : biomeRules) {
            if (top >= 0 && isDesert(top, noise(x, z, rule.valley),
                    noise(x, z, 12.0F), noise(x, z, 24.0F), rule.mix)) {
                biome = rule.desert;
            }
        }
        return biome;
    }

    // Keep float operations and <= / >= boundaries in the same order as
    // SurfaceDuneValley.paintTerrain; integer height alone isn't enough.
    static boolean isDesert(int top, float valleyNoise, float mixNoise, float detailNoise, boolean mix) {
        float valley = (valleyNoise + 0.25F) * 65.0F;
        valley = valley < 1.0F ? 1.0F : valley;
        return !(top <= 90.0F + detailNoise * 10.0F - valley && (mixNoise >= -0.28F || !mix));
    }

    private float noise(int x, int z, float scale) throws IllegalAccessException, InvocationTargetException {
        return (Float) noise2.invoke(perlin, x / scale, z / scale);
    }

    private static List<DuneRule> discover(Object biome) throws ReflectiveOperationException {
        List<DuneRule> result = new ArrayList<>();
        Field single = optionalField(biome.getClass(), "surface");
        if (single != null) {
            addSurface(result, single.get(biome));
        }
        Field multiple = optionalField(biome.getClass(), "surfaces");
        if (multiple != null) {
            Object[] surfaces = (Object[]) multiple.get(biome);
            if (surfaces != null) {
                for (Object surface : surfaces) {
                    addSurface(result, surface);
                }
            }
        }
        return result;
    }

    private static void addSurface(List<DuneRule> rules, Object surface) throws ReflectiveOperationException {
        if (surface == null || !hasType(surface.getClass(), "rwg.surface.SurfaceDuneValley")) {
            return;
        }
        Field valley = requiredField(surface.getClass(), "valley");
        Field mix = requiredField(surface.getClass(), "mix");
        Class<?> registry = Class.forName("rwg.api.RWGBiomes", true, surface.getClass().getClassLoader());
        BiomeGenBase desert = (BiomeGenBase) registry.getField("baseHotDesert").get(null);
        if (desert == null) {
            throw new IllegalStateException("RWG SurfaceDuneValley has no registered baseHotDesert");
        }
        // Read actual registered painter parameters, including BOP's support
        // biomes. Do not guess from a biome's display name, temperature or id.
        rules.add(new DuneRule(valley.getFloat(surface), mix.getBoolean(surface), desert));
    }

    private static boolean hasType(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (name.equals(current.getName())) {
                return true;
            }
        }
        return false;
    }

    static Field requiredField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = optionalField(type, name);
        if (field == null) {
            throw new NoSuchFieldException(type.getName() + "." + name);
        }
        return field;
    }

    private static Field optionalField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException absent) {
                // Not every realistic biome has a surface painter.
            }
        }
        return null;
    }

    private static final class DuneRule {
        private final float valley;
        private final boolean mix;
        private final BiomeGenBase desert;

        private DuneRule(float valley, boolean mix, BiomeGenBase desert) {
            this.valley = valley;
            this.mix = mix;
            this.desert = desert;
        }
    }
}
