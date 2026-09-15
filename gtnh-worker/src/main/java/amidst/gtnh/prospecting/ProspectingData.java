package amidst.gtnh.prospecting;

import java.util.List;

/** Wire data shared with the independently built Forge worker. */
public final class ProspectingData {
    private ProspectingData() {}
    public static final class DimensionInfo {
        public int id;
        public String key, name, icon;
        public boolean ores, fluids, biomes;
        public List<FilterOption> oreOptions, fluidOptions;
    }
    public static final class FilterOption {
        public String id, name, materials;
        public String kind;
    }
    public static final class Deposit {
        public int x, z, size, color, minY, maxY;
        public String id, name, materials, icon, source;
        /** ASTEROID_VEIN / ASTEROID_SMALL, or null for ordinary veins and fluid. */
        public String kind;
        /** Seeded asteroid center height; null when an exact navigation height is unavailable. */
        public Integer y;
        public boolean depleted;
        /** Fluid production in L/operation, x-major within an 8 by 8 chunk field. */
        public int[] amounts;
        public boolean[] currentAmounts;
    }
    public static final class Tile {
        public List<Deposit> deposits;
        public String message;
    }
    public static final class QueryFilter {
        public String id = "";
        public int minimumFluid, minY = 0, maxY = 255;
        public boolean recordedOnly, includeDepleted;
        public boolean accepts(Deposit d) {
            if (id != null && !id.isEmpty() && !id.equals(d.id)) return false;
            if (d.amounts != null) {
                for (int amount : d.amounts) if (amount >= minimumFluid) return true;
                return false;
            }
            return (!recordedOnly || "RECORDED".equals(d.source)) && (includeDepleted || !d.depleted)
                    && d.maxY >= minY && d.minY <= maxY;
        }
    }
}
