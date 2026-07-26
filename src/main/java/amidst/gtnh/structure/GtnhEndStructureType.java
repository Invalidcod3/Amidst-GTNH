package amidst.gtnh.structure;

import amidst.ResourceLoader;
import amidst.mojangapi.world.icon.WorldIconImage;

public enum GtnhEndStructureType {
	HEE_BIOME_ISLAND(
			"HEE_BIOME_ISLAND",
			"HEE Biome Island Centers",
			"hee_infested_endstone_side.png"),
	HEE_DUNGEON_TOWER(
			"HEE_DUNGEON_TOWER",
			"Possible HEE Dungeon Towers",
			"ender_eye.png"),
	DRACONIC_CHAOS_ISLAND(
			"DRACONIC_CHAOS_ISLAND",
			"Draconic Evolution Chaos Islands",
			"draconic_chaotic_core.png");

	private final String wireName;
	private final String displayName;
	private final String menuIcon;
	private final WorldIconImage icon;

	GtnhEndStructureType(String wireName, String displayName, String menuIcon) {
		this.wireName = wireName;
		this.displayName = displayName;
		this.menuIcon = menuIcon;
		this.icon = WorldIconImage.from(
				ResourceLoader.getImage("/amidst/gui/main/icon/" + menuIcon));
	}

	public String getWireName() {
		return wireName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getMenuIcon() {
		return menuIcon;
	}

	public WorldIconImage getIcon() {
		return icon;
	}

	public WorldIconImage getIcon(String subtype) {
		if (this != HEE_BIOME_ISLAND || subtype == null) {
			return icon;
		}
		return switch (subtype) {
			case "INFESTED_FOREST" -> HeeBiomeIslandIcons.INFESTED;
			case "BURNING_MOUNTAINS" -> HeeBiomeIslandIcons.BURNED;
			case "ENCHANTED_ISLAND" -> HeeBiomeIslandIcons.ENCHANTED;
			default -> icon;
		};
	}

	private static WorldIconImage loadIcon(String name) {
		return WorldIconImage.from(
				ResourceLoader.getImage("/amidst/gui/main/icon/" + name));
	}

	private static final class HeeBiomeIslandIcons {
		private static final WorldIconImage INFESTED =
				loadIcon("hee_infested_endstone_side.png");
		private static final WorldIconImage BURNED =
				loadIcon("hee_burned_endstone_side.png");
		private static final WorldIconImage ENCHANTED =
				loadIcon("hee_enchanted_endstone_side.png");
	}
}
