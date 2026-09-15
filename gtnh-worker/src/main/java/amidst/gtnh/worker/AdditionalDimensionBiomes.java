package amidst.gtnh.worker;

import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraftforge.common.DimensionManager;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.function.IntFunction;
import java.util.function.LongFunction;
import java.util.function.Supplier;

/** Biomes for the additional prospecting worlds, without creating worlds or chunks. */
final class AdditionalDimensionBiomes implements AutoCloseable {
    // Stable, dimension-local display IDs, separate from the original space scopes.
    private static final int DISPLAY_BASE = 8192;
    private static final String SPACE = "galaxyspace.core.world.GSBiomeGenBase";
    private static final String EVERGLADES_MANAGER =
            "toxiceverglades.world.WorldChunkManagerCustom";
    private static final String[] KEYS = {
        "Everglades",
        "Ross128ba",
        "Triton",
        "Oberon",
        "Titan",
        "Callisto",
        "Ganymede",
        "Deimos",
        "Europa",
        "Phobos",
        "Venus",
        "Mercury",
        "MakeMake",
        "Haumea",
        "CentauriBb",
        "VegaB",
        "BarnardE",
        "BarnardF",
        "TcetiE",
        "Miranda",
        "KuiperBelt",
        "Neper",
        "Maahes",
        "Seth"
    };
    private static Map<String, BiomeGenBase[]> sources;
    private final Supplier<Map<String, BiomeGenBase[]>> biomeSources;
    private final LongFunction<WorldChunkManager> managerFactory;
    private final IntFunction<WorldServer> worldLookup;
    private final Map<Long, WorldChunkManager> managers =
            new LinkedHashMap<Long, WorldChunkManager>(8, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, WorldChunkManager> entry) {
                    if (size() <= 8) return false;
                    caches.remove(entry.getValue());
                    entry.getValue().cleanupCache();
                    return true;
                }
            };
    private final Map<WorldChunkManager, ManagerBiomeCache> caches = new IdentityHashMap<>();

    AdditionalDimensionBiomes() {
        this(
                AdditionalDimensionBiomes::sources,
                seed -> {
                    try {
                        return createEvergladesManager(seed);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(
                                "Cannot create Everglades biome manager", e);
                    }
                },
                DimensionManager::getWorld);
    }

    AdditionalDimensionBiomes(
            Supplier<Map<String, BiomeGenBase[]>> biomeSources,
            LongFunction<WorldChunkManager> managerFactory,
            IntFunction<WorldServer> worldLookup) {
        this.biomeSources = biomeSources;
        this.managerFactory = managerFactory;
        this.worldLookup = worldLookup;
    }

    static String[] keys() {
        return KEYS.clone();
    }

    static int displayId(String key, int biomeId) {
        if (biomeId < 0 || biomeId > 255)
            throw new IllegalArgumentException("Invalid biome ID " + biomeId);
        for (int i = 0; i < KEYS.length; i++)
            if (KEYS[i].equals(key)) return DISPLAY_BASE + i * 256 + biomeId;
        throw new IllegalArgumentException("Unknown additional biome dimension " + key);
    }

    static synchronized Map<String, BiomeGenBase[]> sources() {
        if (sources != null) return sources;
        Map<String, BiomeGenBase[]> result = new LinkedHashMap<>();
        for (String key : KEYS) {
            try {
                result.put(key, resolveBiomes(key));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                AmidstGtnhWorkerLog.LOG.warn("Biome preview is unavailable for " + key, e);
            }
        }
        sources = Collections.unmodifiableMap(result);
        return sources;
    }

    static boolean supports(String key) {
        return Arrays.asList(KEYS).contains(key) && sources().containsKey(key);
    }

    private static BiomeGenBase[] resolveBiomes(String key) throws ReflectiveOperationException {
        if ("Everglades".equals(key)) {
            // The provider explicitly uses LARGE_BIOMES, independently of the Overworld type.
            WorldChunkManager manager = createEvergladesManager(0);
            manager.cleanupCache();
            return Arrays.stream(BiomeGenBase.getBiomeGenArray())
                    .filter(Objects::nonNull)
                    .toArray(BiomeGenBase[]::new);
        }
        String owner = SPACE, field = "SPACE";
        switch (key) {
            case "Ross128ba":
                owner = "micdoodle8.mods.galacticraft.core.world.gen.BiomeGenBaseMoon";
                field = "moonFlat";
                break;
            case "Titan":
                owner = "galaxyspace.SolarSystem.moons.titan.world.BiomeGenTitan";
                field = "INSTANCE";
                break;
            case "Venus":
                owner = "galaxyspace.SolarSystem.planets.venus.world.BiomeGenVenus";
                field = "INSTANCE";
                break;
            case "KuiperBelt":
                owner = "galaxyspace.SolarSystem.planets.kuiperbelt.world.BiomeGenBaseKuiper";
                field = "INSTANCE";
                break;
            case "Neper":
            case "Maahes":
            case "Seth":
                owner = "micdoodle8.mods.galacticraft.core.world.gen.BiomeGenBaseOrbit";
                field = "space";
                break;
            default:
                break;
        }
        BiomeGenBase biome = (BiomeGenBase) Class.forName(owner).getField(field).get(null);
        if (biome == null) throw new IllegalStateException("Biome is not registered: " + key);
        return new BiomeGenBase[] {biome};
    }

    private static WorldChunkManager createEvergladesManager(long seed)
            throws ReflectiveOperationException {
        Constructor<?> constructor =
                Class.forName(EVERGLADES_MANAGER).getConstructor(long.class, WorldType.class);
        return (WorldChunkManager) constructor.newInstance(seed, WorldType.LARGE_BIOMES);
    }

    int[] sample(
            long seed, int dimensionId, String key, int x, int z, int width, int height, int step) {
        return sample(seed, dimensionId, key, x, z, width, height, step, true);
    }

    int[] sample(
            long seed,
            int dimensionId,
            String key,
            int x,
            int z,
            int width,
            int height,
            int step,
            boolean useRecorded) {
        BiomeGenBase[] biomes = biomeSources.get().get(key);
        if (biomes == null)
            throw new IllegalArgumentException("Biome preview is unavailable for " + key);
        WorldServer world = worldLookup.apply(dimensionId);
        boolean live = useRecorded && world != null && world.getWorldInfo().getSeed() == seed;
        ManagerBiomeCache cache = null;
        if ("Everglades".equals(key)) {
            WorldChunkManager manager = managers.get(seed);
            if (manager == null) {
                manager = managerFactory.apply(seed);
                managers.put(seed, manager);
            }
            cache = caches.get(manager);
            if (cache == null) {
                cache = new ManagerBiomeCache(manager);
                caches.put(manager, cache);
            }
        }
        int[] result = new int[Math.multiplyExact(width, height)];
        for (int row = 0; row < height; row++)
            for (int col = 0; col < width; col++) {
                int sx = x + col * step, sz = z + row * step;
                BiomeGenBase biome = cache == null ? biomes[0] : cache.getBiomeAt(sx, sz);
                if (live && world.blockExists(sx, 0, sz))
                    biome = world.getBiomeGenForCoords(sx, sz);
                if (biome == null) throw new IllegalStateException("No biome at " + sx + "," + sz);
                result[row * width + col] = sampledDisplayId(key, biomes, biome.biomeID);
            }
        return result;
    }

    static int sampledDisplayId(String key, BiomeGenBase[] localBiomes, int rawId) {
        for (BiomeGenBase local : localBiomes)
            if (local.biomeID == rawId) return displayId(key, rawId);
        // A different biome recorded in a loaded chunk already has a global registry descriptor.
        return rawId;
    }

    @Override
    public void close() {
        for (WorldChunkManager manager : managers.values()) manager.cleanupCache();
        managers.clear();
        caches.clear();
    }
}
