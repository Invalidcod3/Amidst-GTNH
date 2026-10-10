package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Snapshots the selected generator's parameters without invoking structure generation. */
final class VillageGenerationRules {
    static final String VILLAGE_NAMES_GENERATOR = "astrotibs.villagenames.village.MapGenVillageVN";
    private static final String VANILLA_GENERATOR =
            "net.minecraft.world.gen.structure.MapGenVillage";
    private static final String VILLAGE_NAMES_CONFIG =
            "astrotibs.villagenames.config.village.VillageGeneratorConfigHandler";

    final int distance;
    final int separation;
    private final int originRadius;
    private final Set<String> biomeNames;

    private VillageGenerationRules(
            int distance, int separation, int originRadius, Set<String> biomeNames) {
        if (separation < 1 || distance <= separation || originRadius < 0) {
            throw new IllegalStateException(
                    "Unsupported village spacing/radius: "
                            + distance
                            + "/"
                            + separation
                            + "/"
                            + originRadius);
        }
        this.distance = distance;
        this.separation = separation;
        this.originRadius = originRadius;
        this.biomeNames =
                biomeNames == null
                        ? null
                        : Collections.unmodifiableSet(new HashSet<String>(biomeNames));
    }

    static VillageGenerationRules rwg(int distance, int separation) {
        return new VillageGenerationRules(distance, separation, 0, null);
    }

    static VillageGenerationRules villageNames(
            int distance, int separation, int radius, Set<String> names) {
        return new VillageGenerationRules(distance, separation, radius, names);
    }

    static VillageGenerationRules read(Object generator) {
        if (generator == null) throw new IllegalStateException("Missing RWG village generator");
        Class<?> type = generator.getClass();
        try {
            // An unknown subclass can override selection. Inheriting parameters alone does not
            // prove it follows either known algorithm family.
            if (VANILLA_GENERATOR.equals(type.getName())) {
                return rwg(
                        integer(generator, "field_82665_g"), integer(generator, "field_82666_h"));
            }
            if (VILLAGE_NAMES_GENERATOR.equals(type.getName())) {
                Class<?> config = Class.forName(VILLAGE_NAMES_CONFIG, false, type.getClassLoader());
                return readVillageNames(generator, config);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read village generator " + type.getName(), e);
        }
        throw new IllegalStateException("Unsupported village generator " + type.getName());
    }

    static VillageGenerationRules readVillageNames(Object generator, Class<?> config)
            throws ReflectiveOperationException {
        int radius = config.getField("noVillagesRadius").getInt(null);
        String[] entries = (String[]) config.getField("spawnBiomesNames").get(null);
        Set<String> names = new HashSet<String>();
        if (entries != null) {
            // Let the running version parse its own biome/type/material syntax. This API only
            // unpacks configuration; it does not register biomes or create saved name data.
            Method unpack = config.getMethod("unpackBiomes", String[].class);
            Object parsed = unpack.invoke(null, (Object) entries.clone());
            Object values = parsed instanceof Map ? ((Map<?, ?>) parsed).get("BiomeNames") : null;
            if (!(values instanceof Collection)) {
                throw new IllegalStateException(
                        "Unsupported Village Names biome configuration API");
            }
            for (Object value : (Collection<?>) values) {
                if (!(value instanceof String)) {
                    throw new IllegalStateException("Invalid Village Names biome name");
                }
                names.add((String) value);
            }
        }
        // VN shadows these vanilla fields. Read the fields declared on the final generator,
        // rather than re-deriving spacing from defaults or from a different config version.
        return villageNames(
                integer(generator, "field_82665_g"),
                integer(generator, "field_82666_h"),
                radius,
                names);
    }

    private static int integer(Object generator, String name) throws ReflectiveOperationException {
        Field field = generator.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(generator);
    }

    boolean accepts(int chunkX, int chunkZ, SurfaceBiomeSampler sampler, boolean independent) {
        int x = chunkX * 16 + 8, z = chunkZ * 16 + 8;
        if (biomeNames == null) return sampler.isVillageLocationViable(x, z);
        // Preserve the installed VN family's int arithmetic, including its distant-coordinate
        // overflow behavior. A version that changes this needs a separately checked adapter.
        if (chunkX * chunkX + chunkZ * chunkZ < originRadius * originRadius) return false;
        if (biomeNames.isEmpty()) return false;
        return biomeNames.contains(
                (independent ? sampler.getPredictedBiomeAt(x, z) : sampler.getBiomeAt(x, z))
                        .biomeName);
    }
}
