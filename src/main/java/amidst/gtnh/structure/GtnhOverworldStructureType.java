package amidst.gtnh.structure;

import amidst.ResourceLoader;
import amidst.mojangapi.world.icon.WorldIconImage;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;

public enum GtnhOverworldStructureType {
	WORLD_SPAWN(
			"WORLD_SPAWN",
			"GTNH World Spawn",
			"spawn.png",
			DefaultWorldIconTypes.SPAWN),
	STRONGHOLD("STRONGHOLD", "GTNH Stronghold", "stronghold.png", DefaultWorldIconTypes.STRONGHOLD),
	VILLAGE("VILLAGE", "Possible GTNH Village", "village.png", DefaultWorldIconTypes.VILLAGE),
	MINESHAFT("MINESHAFT", "Possible GTNH Mineshaft", "mineshaft.png", DefaultWorldIconTypes.MINESHAFT),
	LOOTGAMES_DUNGEON(
			"LOOTGAMES_DUNGEON",
			"Possible LootGames Game Dungeon",
			"lootgames_dungeon.png"),
	TINKERS_SLIME_ISLAND(
			"TINKERS_SLIME_ISLAND",
			"Tinkers' Blue Slime Island (approximate center)",
			"tinkers_blue_slime_ball.png"),
	VANILLA_SPAWNER_DUNGEON(
			"VANILLA_SPAWNER_DUNGEON",
			"Possible Vanilla Spawner Dungeon",
			"vanilla_spawner_dungeon.png"),
	THAUMCRAFT_AURA_NODE(
			"THAUMCRAFT_AURA_NODE",
			"Possible Thaumcraft Aura Node",
			"thaumcraft_aura_node.png"),
	THAUMCRAFT_ELDRITCH_ALTAR(
			"THAUMCRAFT_ELDRITCH_ALTAR",
			"Possible Thaumcraft Eldritch Altar",
			"thaumcraft_eldritch_altar.png"),
	AE2_METEORITE(
			"AE2_METEORITE",
			"Possible AE2 Meteorite",
			"ae2_certus_quartz.png");

	private final String wireName;
	private final String displayName;
	private final String menuIcon;
	private final WorldIconImage icon;

	GtnhOverworldStructureType(
			String wireName,
			String displayName,
			String menuIcon,
			DefaultWorldIconTypes iconType) {
		this.wireName = wireName;
		this.displayName = displayName;
		this.menuIcon = menuIcon;
		this.icon = iconType.getImage();
	}

	GtnhOverworldStructureType(
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
