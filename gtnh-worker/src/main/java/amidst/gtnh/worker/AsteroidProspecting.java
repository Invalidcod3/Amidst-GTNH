package amidst.gtnh.worker;

import static amidst.gtnh.worker.ProspectingReflection.*;

import amidst.gtnh.prospecting.ProspectingData.Deposit;
import amidst.gtnh.prospecting.ProspectingData.FilterOption;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Adapts native asteroid configs and ore registries without invoking a world generator. */
final class AsteroidProspecting {
    private static final String DEFS = "galacticgreg.api.enums.DimensionDef";
    private static final String QUERY = "gregtech.common.worldgen.WorldgenQuery";
    private final Map<String, FilterOption> descriptions = new LinkedHashMap<>();

    static Object endAsteroids() throws ReflectiveOperationException {
        return field(field(type(DEFS), "EndAsteroids"), "modDimensionDef");
    }

    static boolean isEnd(Object definition) throws ReflectiveOperationException {
        return definition == field(field(type(DEFS), "TheEnd"), "modDimensionDef");
    }

    static Object effectiveDefinition(long seed, int dim, Object base, int cx, int cz)
            throws ReflectiveOperationException {
        if (base == null || !isEnd(base)) return base;
        WorldServer world = DimensionManager.getWorld(dim);
        // GT's HEE scanner consults the loaded End and its dragon/structure state.
        // Never reuse that global state for an arbitrary seed or an unloaded End.
        if (world == null || world.getSeed() != seed)
            throw new IllegalStateException(
                    "Load the matching End to distinguish island veins from void asteroids");
        return call(type(DEFS), "getEffectiveDefForChunk", world, cx, cz);
    }

    static boolean enabled(Object definition) throws ReflectiveOperationException {
        if (definition == null || !(Boolean) call(definition, "generatesAsteroids")) return false;
        Object config = config(definition);
        return config != null && (Boolean) field(config, "Enabled");
    }

    private static Object config(Object definition) throws ReflectiveOperationException {
        return call(
                type("galacticgreg.dynconfig.DynamicDimensionConfig"),
                "getAsteroidConfig",
                definition);
    }

    List<FilterOption> options(Object definition) throws ReflectiveOperationException {
        Map<String, FilterOption> result = new LinkedHashMap<>();
        if (!enabled(definition)) return new ArrayList<>();
        for (Object stone : (List<?>) call(definition, "getAsteroidMaterials")) {
            for (boolean small : new boolean[] {false, true}) {
                Object query = query(definition, stone, small);
                for (Object layer : (List<?>) field(query, "list")) {
                    if ((Integer) call(layer, "getWeight") > 0
                            && (Boolean) call(query, "matches", layer)) {
                        FilterOption option = describe(layer, small);
                        result.put(option.id, option);
                    }
                }
            }
        }
        return new ArrayList<>(result.values());
    }

    Deposit predict(long seed, int dim, int cx, int cz, final Object definition)
            throws ReflectiveOperationException {
        if (!enabled(definition)) return null;
        Object value = config(definition);
        AsteroidOrePredictor.Config config =
                new AsteroidOrePredictor.Config(
                        true,
                        (Integer) field(value, "Probability"),
                        (Integer) field(value, "AsteroidMinY"),
                        (Integer) field(value, "AsteroidMaxY"),
                        (Integer) field(value, "MinSize"),
                        (Integer) field(value, "MaxSize"));
        AsteroidOrePredictor.Candidate candidate =
                AsteroidOrePredictor.select(
                        seed,
                        dim,
                        cx,
                        cz,
                        config,
                        new AsteroidOrePredictor.Registry() {
                            public Object stone(Random random) throws ReflectiveOperationException {
                                return call(definition, "getRandomAsteroidMaterial", random);
                            }

                            public Object ore(Object stone, boolean small, Random random)
                                    throws ReflectiveOperationException {
                                return call(query(definition, stone, small), "findRandom", random);
                            }
                        });
        if (candidate == null) return null;
        FilterOption option = describe(candidate.layer, candidate.small);
        Deposit deposit = new Deposit();
        deposit.kind = candidate.small ? "ASTEROID_SMALL" : "ASTEROID_VEIN";
        deposit.id = option.id;
        deposit.name = option.name;
        deposit.materials = option.materials;
        deposit.x = candidate.x;
        deposit.z = candidate.z;
        deposit.y = candidate.y;
        deposit.size = candidate.radius * 2;
        // Conservative generator envelope, not a normal vein's configured height range.
        deposit.minY = Math.max(0, candidate.y - deposit.size - 1);
        deposit.maxY = Math.min(254, candidate.y + deposit.size);
        deposit.source = "PREDICTED";
        Object material = call(candidate.layer, "getOre", 0.5F);
        short[] rgba = material == null ? null : (short[]) call(material, "getRGBA");
        deposit.color =
                rgba == null
                        ? 0xaaaacc
                        : ((rgba[0] & 255) << 16 | (rgba[1] & 255) << 8 | rgba[2] & 255);
        return deposit;
    }

    private static Object query(Object definition, Object stone, boolean small)
            throws ReflectiveOperationException {
        Object query = call(type(QUERY), small ? "small" : "veins");
        call(query, "inDimension", definition);
        call(query, "inStone", call(stone, "getCategory"));
        return query;
    }

    private FilterOption describe(Object layer, boolean small) throws ReflectiveOperationException {
        String name = (String) call(layer, "getName");
        String id = (small ? "asteroid.small:" : "asteroid.vein:") + name;
        FilterOption previous = descriptions.get(id);
        if (previous != null) return previous;
        Map<String, String> materials = new LinkedHashMap<>();
        for (float control : new float[] {0F, 0.25F, 0.5F, 0.75F, 0.999F}) {
            Object material = call(layer, "getOre", control);
            if (material != null)
                materials.put(
                        (String) call(material, "getInternalName"),
                        ProspectingService.clean((String) call(material, "getLocalizedName")));
        }
        FilterOption option = new FilterOption();
        option.id = id;
        option.materials = String.join(", ", materials.values());
        option.name = option.materials.isEmpty() ? name : option.materials;
        option.kind = small ? "ASTEROID_SMALL" : "ASTEROID_VEIN";
        descriptions.put(id, option);
        return option;
    }
}
