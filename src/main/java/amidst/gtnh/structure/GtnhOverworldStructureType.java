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
	STRONGHOLD("STRONGHOLD", "GTNH Stronghold", "ender_eye.png"),
	VILLAGE("VILLAGE", "Possible GTNH Village", "village.png", DefaultWorldIconTypes.VILLAGE),
	MINESHAFT("MINESHAFT", "Possible GTNH Mineshaft", "mineshaft.png", DefaultWorldIconTypes.MINESHAFT),
	OCEAN_MONUMENT("OCEAN_MONUMENT", "Possible Ocean Monument", "ocean_monument.png"),
	RWG2_TWILIGHT_PORTAL("RWG2_TWILIGHT_PORTAL", "Natural Twilight Portal", "rwg2_twilight_poppy.png"),
	RWG2_NETHER_PORTAL("RWG2_NETHER_PORTAL", "Natural Nether Portal", "rwg2_nether_portal.png"),
	LOOTGAMES_DUNGEON(
			"LOOTGAMES_DUNGEON",
			"Possible LootGames Dungeon — Ordinary",
			"lootgames_dungeon.png"),
	RWG2_LAVA_CAVE_GAME(
			"LOOTGAMES_DUNGEON", "LootGames — RWG2 Lava Cave", "lootgames_dungeon.png", 0xE67532),
	RWG2_RIVER_CHAMBER_GAME(
			"LOOTGAMES_DUNGEON", "LootGames — RWG2 River Chamber", "lootgames_dungeon.png", 0x46BBD0),
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

	GtnhOverworldStructureType(String wireName, String displayName, String menuIcon, int borderColor) {
		this.wireName = wireName;
		this.displayName = displayName;
		this.menuIcon = menuIcon;
		var base = ResourceLoader.getImage("/amidst/gui/main/icon/" + menuIcon);
		var framed = new java.awt.image.BufferedImage(base.getWidth() + 6, base.getHeight() + 6,
				java.awt.image.BufferedImage.TYPE_INT_ARGB);
		var graphics = framed.createGraphics();
		try {
			graphics.setColor(new java.awt.Color(0x202028));
			graphics.fillRect(0, 0, framed.getWidth(), framed.getHeight());
			graphics.setColor(new java.awt.Color(borderColor));
			graphics.fillRect(1, 1, framed.getWidth() - 2, framed.getHeight() - 2);
			graphics.setColor(new java.awt.Color(0x202028));
			graphics.fillRect(3, 3, base.getWidth(), base.getHeight());
			graphics.drawImage(base, 3, 3, null);
		} finally {
			graphics.dispose();
		}
		this.icon = WorldIconImage.from(framed);
	}

	/** One Worker response supplies three disjoint LootGames layers without extra sampling. */
	public boolean matches(GtnhStructureDescriptor structure) {
		if (!wireName.equals(structure.kind())) return false;
		return switch (this) {
			case RWG2_LAVA_CAVE_GAME -> "RWG2_LAVA_CAVE".equals(structure.subtype());
			case RWG2_RIVER_CHAMBER_GAME -> "RWG2_RIVER_CHAMBER".equals(structure.subtype());
			case LOOTGAMES_DUNGEON -> !"RWG2_LAVA_CAVE".equals(structure.subtype())
					&& !"RWG2_RIVER_CHAMBER".equals(structure.subtype());
			default -> true;
		};
	}

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
