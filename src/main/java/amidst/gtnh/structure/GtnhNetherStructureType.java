package amidst.gtnh.structure;

import amidst.ResourceLoader;
import amidst.mojangapi.world.icon.WorldIconImage;

public enum GtnhNetherStructureType {
	TINKERS_NETHER_SLIME_ISLAND(
			"TINKERS_NETHER_SLIME_ISLAND",
			"Possible Tinkers' Nether Slime Island (approximate center)",
			"tinkers_blue_slime_ball.png"),
	AUTOMAGY_NETHER_SPIRE(
			"AUTOMAGY_NETHER_SPIRE",
			"Possible Automagy Nether Spire",
			"thaumcraft_eldritch_altar.png");

	private final String wireName;
	private final String displayName;
	private final String menuIcon;
	private final WorldIconImage icon;

	GtnhNetherStructureType(
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
