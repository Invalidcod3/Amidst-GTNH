package amidst.gtnh.worker;

import static amidst.gtnh.worker.ProspectingReflection.*;

import java.util.*;
import java.lang.reflect.*;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fluids.Fluid;
import amidst.gtnh.prospecting.ProspectingData.*;

/** Runtime GT/VP registry and read-only prospecting; never requests terrain chunks. */
final class ProspectingService {
    private static final String VP = "com.sinthoras.visualprospecting.";
    private final Map<Integer, Object> definitions = new HashMap<>();
    private final Map<Integer, Object> fluidDefinitions = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    private final Map<String, Object> queries = new HashMap<>();
    private final AsteroidProspecting asteroids = new AsteroidProspecting();
    private final Map<String, String> icons = new HashMap<>();
    private List<DimensionInfo> dimensions;

    List<DimensionInfo> catalog() {
        if (dimensions != null) return dimensions;
        try {
            List<DimensionInfo> result = new ArrayList<>();
            Class<?> defs = type("galacticgreg.api.enums.DimensionDef");
            Object oil = oilConfig();
            Map<?, ?> abbreviations = (Map<?, ?>) field(type("gtneioreplugin.util.DimensionHelper"), "INTERNAL_TO_ABBR");
            for (ProspectingDimensions.Candidate candidate : ProspectingDimensions.discover().values()) {
                int id = candidate.id;
                try {
                    String name = candidate.name;
                    Object def = call(defs, "getDefByName", name);
                    Object fluidDim = ProspectingDimensions.fluidDimension(oil, candidate);
                    if (def == null && fluidDim == null) continue;
                    DimensionInfo d = new DimensionInfo();
                    d.id = id;
                    d.name = name;
                    d.key = name;
                    for (Object value : defs.getEnumConstants()) {
                        if (def != null && field(value, "modDimensionDef") == def) d.key = ((Enum<?>) value).name();
                    }
                    d.oreOptions = new ArrayList<>();
                    if (def != null) {
                        if ((Boolean) call(def, "generatesOre")) d.oreOptions.addAll(oreOptions(name));
                        if ((Boolean) call(def, "generatesAsteroids")) {
                            d.oreOptions.addAll(asteroids.options(AsteroidProspecting.isEnd(def)
                                    ? AsteroidProspecting.endAsteroids() : def));
                        }
                    }
                    d.fluidOptions = fluidOptions(fluidDim);
                    d.ores = def != null && !d.oreOptions.isEmpty();
                    d.biomes = AdditionalDimensionBiomes.supports(d.key);
                    d.fluids = !d.fluidOptions.isEmpty();
                    if (!d.ores && !d.fluids) continue;
                    // An unavailable texture must not remove an otherwise valid world from the menu.
                    try { d.icon = ProspectingTextures.dimensionIcon((String) abbreviations.get(name)); }
                    catch (RuntimeException e) { AmidstGtnhWorkerLog.LOG.warn("Cannot read dimension icon " + name, e); }
                    names.put(id, name);
                    if (def != null) definitions.put(id, def);
                    if (fluidDim != null) fluidDefinitions.put(id, fluidDim);
                    result.add(d);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    AmidstGtnhWorkerLog.LOG.warn("Cannot inspect prospecting dimension " + id, e);
                }
            }
            dimensions = result;
            return result;
        } catch (ReflectiveOperationException e) { throw failure(e); }
    }

    private List<FilterOption> oreOptions(String dimension) throws ReflectiveOperationException {
        List<FilterOption> result = new ArrayList<>();
        for (Object vein : (Collection<?>) call(type(VP + "database.veintypes.VeinTypeCaching"), "getVeinTypes")) {
            if (!((Collection<?>) call(vein, "getAllowedDimensions")).contains(dimension)) continue;
            FilterOption option = new FilterOption();
            option.id = (String) field(vein, "name");
            option.name = clean((String) call(vein, "getVeinName"));
            option.materials = clean(String.valueOf(call(vein, "getOreMaterialNames")));
            result.add(option);
        }
        return result;
    }

    private List<FilterOption> fluidOptions(Object dimension) throws ReflectiveOperationException {
        Map<String, FilterOption> result = new TreeMap<>();
        if (dimension != null) for (Object config : ((Map<?, ?>) call(dimension, "getFluids")).values()) {
            Fluid fluid = (Fluid) call(config, "getFluid");
            if (fluid == null || (Integer) field(config, "Chance") <= 0) continue;
            FilterOption option = new FilterOption();
            option.id = fluid.getName();
            option.name = clean(fluid.getLocalizedName());
            result.put(option.id, option);
        }
        return new ArrayList<>(result.values());
    }

    Job start(long seed, int dimension, int x, int z, int width, int height, String mode) {
        return start(seed, dimension, x, z, width, height, mode, null);
    }
    Job start(long seed, int dimension, int x, int z, int width, int height, String mode, QueryFilter filter) {
        catalog();
        if (!names.containsKey(dimension)) throw new IllegalArgumentException("No prospecting registry for dimension " + dimension);
        if (!"ORES".equals(mode) && !"FLUID".equals(mode)) throw new IllegalArgumentException("Unknown marker mode");
        if (width < 1 || height < 1 || width > 512 || height > 512
                || (long)x + width > Integer.MAX_VALUE || (long)z + height > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Prospecting area must be within 512 by 512 blocks");
        if (filter != null && (filter.minimumFluid < 0 || filter.minY < 0 || filter.maxY > 255 || filter.minY > filter.maxY))
            throw new IllegalArgumentException("Invalid prospecting export conditions");
        return new Job(seed, dimension, x, z, width, height, "FLUID".equals(mode), filter);
    }

    final class Job {
        final long seed;
        final int dimension, minX, minZ, endX, endZ, stride;
        final boolean fluids;
        final QueryFilter filter;
        final WorldServer context;
        final WorldServer dimensionContext;
        final Tile result = new Tile();
        int x, z;
        Job(long seed, int dimension, int bx, int bz, int width, int height, boolean fluids, QueryFilter filter) {
            this.filter = filter;
            this.seed = seed; this.dimension = dimension; this.fluids = fluids;
            stride = fluids ? 8 : 1;
            minX = Math.floorDiv(bx, 16 * stride) * stride;
            minZ = Math.floorDiv(bz, 16 * stride) * stride;
            endX = Math.floorDiv(bx + width - 1, 16 * stride) * stride;
            endZ = Math.floorDiv(bz + height - 1, 16 * stride) * stride;
            x = minX; z = minZ;
            context = DimensionManager.getWorld(0);
            dimensionContext = DimensionManager.getWorld(dimension);
            result.deposits = new ArrayList<>();
            result.message = fluids ? "Initial production; recorded chunks show current production (L/Op)"
                    : "Seed candidates; Visual Prospecting records override predictions";
        }
        boolean advance(long deadline) {
            if (DimensionManager.getWorld(0) != context) throw new IllegalStateException("World changed during prospecting");
            if (DimensionManager.getWorld(dimension) != dimensionContext)
                throw new IllegalStateException("World changed during prospecting");
            try {
                do {
                    Deposit d = fluids ? fluid(seed, dimension, x, z, filter) : ore(seed, dimension, x, z, filter);
                    if (d != null && (filter == null || filter.accepts(d))) result.deposits.add(d);
                    x += stride;
                    if (x > endX) { x = minX; z += stride; }
                } while (z <= endZ && System.nanoTime() < deadline);
                return z > endZ;
            } catch (ReflectiveOperationException e) { throw failure(e); }
        }
    }

    private Deposit ore(long seed, int dim, int cx, int cz, QueryFilter filter) throws ReflectiveOperationException {
        return ore(seed,dim,cx,cz,filter,true);
    }
    private Deposit ore(long seed, int dim, int cx, int cz, QueryFilter filter, boolean useRecords) throws ReflectiveOperationException {
        Object base = definitions.get(dim);
        Object definition = AsteroidProspecting.effectiveDefinition(seed, dim, base, cx, cz);
        if (definition == null) return null;
        if (!(Boolean) call(definition, "generatesOre")) {
            if (base != definition && !(Boolean) call(base, "generatesAsteroids")) return null;
            // Asteroids have their own per-chunk RNG and no ordinary VP vein record.
            // Do not gate them on isOreChunk or assign the chunk-center/vein height.
            if (filter != null && filter.recordedOnly) return null;
            return asteroids.predict(seed, dim, cx, cz, definition);
        }
        if (!(Boolean) call(type("gregtech.common.GTWorldgenerator"), "isOreChunk", cx, cz)) return null;
        Object position = null;
        WorldServer world = DimensionManager.getWorld(0);
        if (useRecords && world != null && world.getWorldInfo().getSeed() == seed) {
            Object cache = field(type(VP + "database.ServerCache"), "instance");
            position = call(cache, "getOreVein", dim, cx, cz);
            if (position == field(type(VP + "database.OreVeinPosition"), "EMPTY_VEIN")) position = null;
        }
        Object vein;
        boolean actual = position != null;
        if (filter != null && filter.recordedOnly && !actual) return null;
        if (actual) {
            vein = field(position, "veinType");
            if (vein == field(type(VP + "database.veintypes.VeinType"), "NO_VEIN")) return null;
        } else {
            long oreSeed = seed << 16 ^ ((dim & 255L) << 56 | (cx & 0xfffffffL) << 28 | cz & 0xfffffffL);
            if (new GtnhXstrRandom(oreSeed).nextInt(100) >= (Integer) call(definition, "getOreVeinChance")) return null;
            String dimensionName = (String) call(definition, "getDimensionName");
            Object query = queries.get(dimensionName);
            if (query == null) {
                query = call(call(type("gregtech.common.worldgen.WorldgenQuery"), "veins"), "inDimension", dimensionName);
                queries.put(dimensionName, query);
            }
            Object layer = call(query, "findRandom", new GtnhXstrRandom(selectionSeed(oreSeed)));
            if (layer == null) return null;
            vein = call(type(VP + "database.veintypes.VeinTypeCaching"), "getVeinType", field(layer, "mWorldGenName"));
            if (vein == null || field(vein, "representativeOre") == null) return null;
        }
        Deposit d = new Deposit();
        d.x = cx * 16 + 8; d.z = cz * 16 + 8; d.size = 16;
        d.id = (String) field(vein, "name");
        d.name = clean((String) call(vein, "getVeinName"));
        d.materials = clean(String.valueOf(call(vein, "getOreMaterialNames")));
        d.minY = (Integer) field(vein, "minBlockY"); d.maxY = (Integer) field(vein, "maxBlockY");
        d.source = actual ? "RECORDED" : "PREDICTED";
        d.depleted = actual && (Boolean) call(position, "isDepleted");
        if (filter != null) return filter.accepts(d) ? d : null; // Export needs coordinates, not rendered icons.
        if (position == null) {
            Class<?> posType = type(VP + "database.OreVeinPosition");
            Class<?> sourceType = type(VP + "database.VeinSource");
            position = posType.getConstructors()[0].newInstance(dim, cx, cz, vein, false, sourceType.getEnumConstants()[0]);
        }
        Class<?> locType = type(VP + "integration.model.locations.OreVeinLocation");
        Object location = locType.getConstructors()[0].newInstance(position);
        d.color = (Integer) call(location, "getColor");
        String key = dim + ":" + d.id + ":" + d.depleted;
        d.icon = icons.get(key);
        if (d.icon == null) {
            d.icon = ProspectingTextures.oreIcon(location);
            icons.put(key, d.icon);
        }
        return d;
    }

    void verifyOre(long seed,int dim,int cx,int cz,amidst.gtnh.validation.AccuracyReport report) {
        catalog();
        try {
            Object definition = AsteroidProspecting.effectiveDefinition(seed, dim, definitions.get(dim), cx, cz);
            if (definition != null && !(Boolean) call(definition, "generatesOre")) {
                Deposit predicted = ore(seed, dim, cx, cz, null, false);
                if (predicted != null) report.add("ASTEROID", predicted.x, predicted.z, predicted.id, "", "UNVERIFIED",
                        "Asteroid candidate; ordinary Visual Prospecting vein records cannot verify asteroid geometry or small ores");
                return;
            }
            if (!(Boolean) call(type("gregtech.common.GTWorldgenerator"), "isOreChunk",cx,cz))return;
            if(!definitions.containsKey(dim)) {
                report.add("ORE",cx*16+8,cz*16+8,"","","UNVERIFIED","No ore prediction registry for this dimension");return;
            }
            Deposit prediction=ore(seed,dim,cx,cz,new QueryFilter(),false);
            String predicted=prediction==null?"NO_VEIN":prediction.id;
            Object cache=field(type(VP+"database.ServerCache"),"instance");
            Object position=call(cache,"getOreVein",dim,cx,cz);
            if(position==null || position==field(type(VP+"database.OreVeinPosition"),"EMPTY_VEIN")) {
                report.add("ORE",cx*16+8,cz*16+8,predicted,"","UNVERIFIED","No Visual Prospecting generation record");return;
            }
            Object vein=field(position,"veinType");
            String actual=vein==field(type(VP+"database.veintypes.VeinType"),"NO_VEIN")?"NO_VEIN":(String)field(vein,"name");
            report.add("ORE",cx*16+8,cz*16+8,predicted,actual,predicted.equals(actual)?"MATCH":"MISMATCH",
                    "Independent seed prediction vs Visual Prospecting record; compares vein type and center, not individual ore blocks");
        } catch(ReflectiveOperationException e) { throw failure(e); }
    }

    static long selectionSeed(long oreSeed) {
        long hash = 0xCBF29CE484222325L;
        for (int shift = 56; shift >= 0; shift -= 8) hash = (hash ^ (byte)(oreSeed >> shift)) * 0x100000001B3L;
        for (int i = 0; i < 4; i++) hash *= 0x100000001B3L;
        return hash;
    }

    private Deposit fluid(long seed, int dim, int cx, int cz, QueryFilter filter) throws ReflectiveOperationException {
        Object config = fluidDefinitions.get(dim);
        if (config == null) return null;
        // Exactly UndergroundOil.getPristineAmount, but one RNG walk per field instead of 64 resets.
        GtnhXstrRandom random = new GtnhXstrRandom(seed + dim * 2L + (cx >> 3) + 8267L * (cz >> 3));
        Object uo = call(config, "getRandomFluid", random);
        if (uo == null) return null;
        Fluid fluid = (Fluid) call(uo, "getFluid");
        if (fluid == null) return null;
        if (filter != null && filter.id != null && !filter.id.isEmpty() && !filter.id.equals(fluid.getName())) return null;
        int average = (Integer) call(uo, "getRandomAmount", random);
        int[] initialAmounts = fieldAmounts(average, random);
        Deposit d = new Deposit();
        d.x = cx * 16; d.z = cz * 16; d.size = 128;
        d.id = fluid.getName(); d.name = clean(fluid.getLocalizedName());
        d.color = (Integer) call(type(VP + "integration.model.locations.UndergroundFluidLocation"), "resolveColor", fluid);
        d.amounts = new int[64];
        d.currentAmounts = new boolean[64];
        d.source = "INITIAL";
        Object storage = null;
        Method created = null, get = null;
        WorldServer world = DimensionManager.getWorld(dim);
        if (world != null && world.getWorldInfo().getSeed() == seed) {
            Field f = type("gregtech.common.UndergroundOil").getDeclaredField("STORAGE");
            f.setAccessible(true); storage = f.get(null);
            Class<?> base = storage.getClass().getSuperclass();
            created = base.getDeclaredMethod("isCreated", int.class, int.class, int.class); created.setAccessible(true);
            get = base.getMethod("get", net.minecraft.world.World.class, int.class, int.class);
        }
        for (int i = 0; i < 64; i++) {
            int amount = initialAmounts[i];
            // Only inspect existing in-memory records. Avoid UndergroundOil's "read" method: it can deplete a vein.
            if (storage != null && (Boolean) created.invoke(storage, dim, cx + i / 8, cz + i % 8)) {
                Object data = get.invoke(storage, world, cx + i / 8, cz + i % 8);
                Method amountMethod = data.getClass().getMethod("getAmount"); amountMethod.setAccessible(true);
                amount = (Integer) amountMethod.invoke(data);
                d.currentAmounts[i] = true;
                d.source = "CURRENT/INITIAL";
            }
            d.amounts[i] = Math.max(0, amount / 5000);
        }
        return d;
    }
    static int[] fieldAmounts(int average, Random random) {
        int[] amounts = new int[64];
        for (int i = 0; i < amounts.length; i++) amounts[i] = (int)((float)average * (0.75f + random.nextFloat() / 2f));
        return amounts;
    }
    private static Object oilConfig() throws ReflectiveOperationException {
        return field(field(type("gregtech.GTMod"), "proxy"), "mUndergroundOil");
    }
    static String clean(String text) { return text.replaceAll("\u00a7.", ""); }
    private static IllegalStateException failure(Exception e) {
        return new IllegalStateException("GTNH prospecting API is unavailable: " + e, e);
    }
}
