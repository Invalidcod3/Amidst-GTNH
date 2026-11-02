package amidst.gtnh.structure;

import amidst.fragment.layer.LayerDeclaration;
import amidst.fragment.layer.LayerIds;

import java.util.HashSet;
import java.util.Set;

/** A map-load scope only: exports and independent queries keep their explicit/full selection. */
public final class StructureQuerySelection implements AutoCloseable {
    private static final ThreadLocal<Set<String>> CURRENT = new ThreadLocal<>();
    private final Set<String> previous;

    private StructureQuerySelection(Set<String> types) {
        previous = CURRENT.get();
        CURRENT.set(Set.copyOf(types));
    }

    public static StructureQuerySelection open(Set<String> types) {
        return new StructureQuerySelection(types);
    }

    public static Set<String> current() {
        return CURRENT.get();
    }

    public static Set<String> visibleTypes(Iterable<LayerDeclaration> layers) {
        Set<String> types = new HashSet<>();
        for (LayerDeclaration layer : layers) {
            if (!layer.isVisible()) continue;
            String type =
                    switch (layer.getLayerId()) {
                        case LayerIds.GTNH_ROGUELIKE_DESERT,
                                LayerIds.GTNH_ROGUELIKE_FOREST,
                                LayerIds.GTNH_ROGUELIKE_ICE,
                                LayerIds.GTNH_ROGUELIKE_JUNGLE,
                                LayerIds.GTNH_ROGUELIKE_MESA,
                                LayerIds.GTNH_ROGUELIKE_MOUNTAIN,
                                LayerIds.GTNH_ROGUELIKE_PLAINS,
                                LayerIds.GTNH_ROGUELIKE_SWAMP ->
                                "ROGUELIKE_DUNGEON";
                        case LayerIds.GTNH_STRONGHOLD -> "STRONGHOLD";
                        case LayerIds.GTNH_VILLAGE -> "VILLAGE";
                        case LayerIds.GTNH_MINESHAFT -> "MINESHAFT";
                        case LayerIds.GTNH_OCEAN_MONUMENT -> "OCEAN_MONUMENT";
                        case LayerIds.GTNH_RWG2_TWILIGHT_PORTAL -> "RWG2_TWILIGHT_PORTAL";
                        case LayerIds.GTNH_RWG2_NETHER_PORTAL -> "RWG2_NETHER_PORTAL";
                        case LayerIds.GTNH_LOOTGAMES_DUNGEON -> "LOOTGAMES_DUNGEON";
                        case LayerIds.GTNH_RWG2_LAVA_CAVE_GAME -> "RWG2_LAVA_CAVE_GAME";
                        case LayerIds.GTNH_RWG2_RIVER_CHAMBER_GAME -> "RWG2_RIVER_CHAMBER_GAME";
                        case LayerIds.GTNH_TINKERS_SLIME_ISLAND -> "TINKERS_SLIME_ISLAND";
                        case LayerIds.GTNH_AE2_METEORITE -> "AE2_METEORITE";
                        case LayerIds.GTNH_THAUMCRAFT_AURA_NODE -> "THAUMCRAFT_AURA_NODE";
                        case LayerIds.GTNH_THAUMCRAFT_ELDRITCH_ALTAR -> "THAUMCRAFT_ELDRITCH_ALTAR";
                        default ->
                                null; // Spawn and vanilla spawner dungeons have separate requests.
                    };
            if (type != null) types.add(type);
        }
        return Set.copyOf(types);
    }

    @Override
    public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
