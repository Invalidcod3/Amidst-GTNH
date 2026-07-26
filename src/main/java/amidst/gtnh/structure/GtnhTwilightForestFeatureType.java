package amidst.gtnh.structure;

import amidst.ResourceLoader;
import amidst.fragment.layer.LayerIds;
import amidst.mojangapi.world.icon.WorldIconImage;

/**
 * Twilight Forest landmarks and their original magic-map marker sprites.
 */
public enum GtnhTwilightForestFeatureType {
	SMALL_HOLLOW_HILL("SMALL_HOLLOW_HILL", "Small Hollow Hill", 1),
	MEDIUM_HOLLOW_HILL("MEDIUM_HOLLOW_HILL", "Medium Hollow Hill", 2),
	LARGE_HOLLOW_HILL("LARGE_HOLLOW_HILL", "Large Hollow Hill", 3),
	HEDGE_MAZE("HEDGE_MAZE", "Hedge Maze", 4),
	NAGA_COURTYARD("NAGA_COURTYARD", "Naga Courtyard", 5),
	LICH_TOWER("LICH_TOWER", "Lich Tower", 6),
	ICE_TOWER("ICE_TOWER", "Ice Tower", 7),
	QUEST_ISLAND("QUEST_ISLAND", "Quest Island", 8),
	QUEST_GROVE("QUEST_GROVE", "Quest Grove", 9),
	DRUID_GROVE("DRUID_GROVE", "Druid Grove", 10),
	FLOATING_RUINS("FLOATING_RUINS", "Floating Ruins", 11),
	HYDRA_LAIR("HYDRA_LAIR", "Hydra Lair", 12),
	LABYRINTH("LABYRINTH", "Labyrinth", 13),
	DARK_TOWER("DARK_TOWER", "Dark Tower", 14),
	KNIGHT_STRONGHOLD("KNIGHT_STRONGHOLD", "Knight Stronghold", 15),
	WORLD_TREE("WORLD_TREE", "World Tree", 16),
	YETI_LAIR("YETI_LAIR", "Yeti Lair", 17),
	TROLL_LAIR("TROLL_LAIR", "Troll Lair", 18),
	FINAL_CASTLE("FINAL_CASTLE", "Final Castle", 19),
	MUSHROOM_TOWER("MUSHROOM_TOWER", "Mushroom Tower", 20);

	private final String wireName;
	private final String displayName;
	private final String iconFile;
	private final WorldIconImage icon;

	GtnhTwilightForestFeatureType(String wireName, String displayName, int featureId) {
		this.wireName = wireName;
		this.displayName = displayName;
		this.iconFile = String.format("twilight_feature_%02d.png", featureId);
		this.icon = WorldIconImage.from(
				ResourceLoader.getImage("/amidst/gui/main/icon/" + iconFile));
	}

	public String getWireName() {
		return wireName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getIconFile() {
		return iconFile;
	}

	public WorldIconImage getIcon() {
		return icon;
	}

	public int getLayerId() {
		return LayerIds.GTNH_TWILIGHT_FOREST_FEATURE_FIRST + ordinal();
	}

	public String getPreferenceKey() {
		return "gtnhTwilightForestFeature" + String.format("%02d", ordinal() + 1);
	}

	public static GtnhTwilightForestFeatureType fromWireName(String wireName) {
		if (wireName == null) {
			return null;
		}
		for (GtnhTwilightForestFeatureType type : values()) {
			if (type.wireName.equals(wireName)) {
				return type;
			}
		}
		return null;
	}
}
