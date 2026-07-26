package amidst.gtnh.structure;

import amidst.ResourceLoader;
import amidst.mojangapi.world.icon.WorldIconImage;

public enum GtnhMoonStructureType {
	MOON_DUNGEON(
			"MOON_DUNGEON",
			"Moon Dungeon",
			"galacticraft_moon_dungeon_brick.png"),
	MOON_VILLAGE(
			"MOON_VILLAGE",
			"Moon Village",
			"galacticraft_alien_villager.png");

	private final String wireName;
	private final String displayName;
	private final String menuIcon;
	private final WorldIconImage icon;

	GtnhMoonStructureType(
			String wireName,
			String displayName,
			String menuIcon) {
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
}
