package amidst.gtnh.worker;

import net.minecraft.world.WorldServer;

/** Wire names are world-type identifiers, not translated UI labels. */
final class OverworldGenerator {
    static final String ALPHA = "RWG";
    static final String SMOOTH = "RWG2_CONTINENTS";

    static String normalize(String name) {
        if (name == null || ALPHA.equals(name)) return ALPHA;
        if (SMOOTH.equals(name)) return SMOOTH;
        throw new IllegalArgumentException("Unsupported overworld generator: " + name);
    }

    static boolean isSmooth(String name) {
        return SMOOTH.equals(normalize(name));
    }

    static boolean matches(WorldServer world, long seed, String generator) {
        return world != null
                && world.getSeed() == seed
                && normalize(generator)
                        .equalsIgnoreCase(world.getWorldInfo().getTerrainType().getWorldTypeName());
    }

    static boolean hasSmooth() {
        try {
            Class.forName(Rwg2Runtime.MANAGER, false, OverworldGenerator.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static boolean simplex() {
        try {
            return Rwg2Runtime.openSimplex();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read RWG2 noise selection", e);
        }
    }

    static String identity(String name) {
        return isSmooth(name) ? SMOOTH + (simplex() ? ":OPENSIMPLEX" : ":PERLIN") : ALPHA;
    }
}
