package amidst.gtnh.worker;

import static amidst.gtnh.worker.ProspectingReflection.*;
import java.util.*;
import net.minecraft.world.WorldProvider;
import net.minecraftforge.common.DimensionManager;

/** Discovers configured worlds without loading them or registering new game dimensions. */
final class ProspectingDimensions {
    static final class Candidate {
        final int id;
        final String name, providerClass;
        Candidate(int id, String name, String providerClass) {
            this.id = id; this.name = name; this.providerClass = providerClass;
        }
    }

    static Map<Integer, Candidate> discover() throws ReflectiveOperationException {
        Map<Integer, Candidate> result = new TreeMap<>();
        // Celestial bodies are available at the main menu, before server-side planet registration.
        Class<?> galaxy = type("micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry");
        for (String method : new String[] {"getRegisteredPlanets", "getRegisteredMoons"}) {
            addBodies(result, ((Map<?, ?>) call(galaxy, method)).values());
        }
        for (int id : DimensionManager.getStaticDimensionIDs()) {
            try {
                WorldProvider provider = DimensionManager.createProviderFor(id);
                String name;
                try { name = (String) call(call(provider, "getCelestialBody"), "getName"); }
                catch (NoSuchMethodException e) { name = provider.getDimensionName(); }
                if (id == SpaceDimensionSampler.deepDarkDimensionId()) name = "Underdark";
                result.put(id, new Candidate(id, name, provider.getClass().getName()));
            } catch (ReflectiveOperationException | RuntimeException e) {
                AmidstGtnhWorkerLog.LOG.warn("Cannot inspect world provider " + id, e);
            }
        }
        return result;
    }

    static void addBodies(Map<Integer, Candidate> target, Collection<?> bodies) throws ReflectiveOperationException {
        for (Object body : bodies) {
            Class<?> provider = (Class<?>) call(body, "getWorldProvider");
            if (provider == null) continue; // Decorative stars/gas giants have no world to prospect.
            int id = (Integer) call(body, "getDimensionID");
            String name = (String) call(body, "getName");
            target.putIfAbsent(id, new Candidate(id, name, provider.getName()));
        }
    }

    static Object fluidDimension(Object oil, Candidate dimension) throws ReflectiveOperationException {
        return resolveFluid((Map<?, ?>) call(oil, "getDimensionList"), (int[]) field(oil, "blackList"),
                dimension.id, dimension.providerClass);
    }

    /** Same precedence and case-sensitive class matching as GTUODimensionList.GetDimension.
     * Its original implementation dereferences DimensionManager.getProvider(id), which is null
     * for an unloaded world; the registered provider class gives the same match without a world. */
    static Object resolveFluid(Map<?, ?> dimensions, int[] blacklist, int id, String providerClass)
            throws ReflectiveOperationException {
        if (blacklist != null) for (int blocked : blacklist) if (blocked == id) return null;
        Object exact = dimensions.get(Integer.toString(id));
        if (exact != null) return exact;
        for (Object config : dimensions.values()) {
            if (providerClass.contains((String) field(config, "Dimension"))) return config;
        }
        return dimensions.get("Default");
    }
}
