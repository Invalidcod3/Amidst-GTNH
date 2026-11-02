package amidst.gtnh.worker;

import java.util.*;

/** Optional protocol-31 selection. Absence retains the legacy full query. */
final class StructureSelection {
    static final StructureSelection ALL = new StructureSelection(null);
    private static final Set<String> KNOWN =
            new HashSet<>(
                    Arrays.asList(
                            "STRONGHOLD",
                            "VILLAGE",
                            "MINESHAFT",
                            "ROGUELIKE_DUNGEON",
                            "OCEAN_MONUMENT",
                            "RWG2_TWILIGHT_PORTAL",
                            "RWG2_NETHER_PORTAL",
                            "LOOTGAMES_DUNGEON",
                            "RWG2_LAVA_CAVE_GAME",
                            "RWG2_RIVER_CHAMBER_GAME",
                            "TINKERS_SLIME_ISLAND",
                            "AE2_METEORITE",
                            "THAUMCRAFT_AURA_NODE",
                            "THAUMCRAFT_ELDRITCH_ALTAR"));
    private final Set<String> types;

    private StructureSelection(Set<String> types) {
        this.types = types;
    }

    static StructureSelection of(List<String> types) {
        if (types == null) return ALL;
        Set<String> copy = new HashSet<>(types);
        if (!KNOWN.containsAll(copy)) throw new IllegalArgumentException("Unknown structure type");
        return new StructureSelection(Collections.unmodifiableSet(copy));
    }

    boolean wants(String type) {
        return types == null || types.contains(type);
    }

    boolean rwg() {
        return wants("VILLAGE") || wants("MINESHAFT") || wants("STRONGHOLD");
    }

    boolean mods() {
        return wants("LOOTGAMES_DUNGEON")
                || wants("TINKERS_SLIME_ISLAND")
                || wants("AE2_METEORITE");
    }

    boolean lava() {
        return wants("RWG2_NETHER_PORTAL") || wants("RWG2_LAVA_CAVE_GAME");
    }

    boolean river() {
        return wants("RWG2_TWILIGHT_PORTAL") || wants("RWG2_RIVER_CHAMBER_GAME");
    }

    boolean landmarks() {
        return lava() || river() || wants("OCEAN_MONUMENT");
    }

    boolean thaumcraft() {
        return wants("THAUMCRAFT_AURA_NODE") || wants("THAUMCRAFT_ELDRITCH_ALTAR");
    }

    boolean accepts(RoguelikeDungeonPredictor.StructureDescriptor structure) {
        String type = structure.kind;
        if ("LOOTGAMES_DUNGEON".equals(type)) {
            if ("RWG2_LAVA_CAVE".equals(structure.subtype)) type = "RWG2_LAVA_CAVE_GAME";
            else if ("RWG2_RIVER_CHAMBER".equals(structure.subtype))
                type = "RWG2_RIVER_CHAMBER_GAME";
        }
        return wants(type);
    }
}
