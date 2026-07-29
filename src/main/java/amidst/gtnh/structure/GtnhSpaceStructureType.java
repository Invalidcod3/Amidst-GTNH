package amidst.gtnh.structure;

import amidst.ResourceLoader;
import amidst.fragment.layer.LayerIds;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.icon.WorldIconImage;

public enum GtnhSpaceStructureType {
	MARS_CAVERN(
			"MARS_CAVERN",
			"Mars Cavern",
			"galacticraft_mars_cavern_vine.png",
			Dimension.MARS,
			LayerIds.GTNH_MARS_CAVERN),
	MARS_DUNGEON(
			"MARS_DUNGEON",
			"Mars Dungeon",
			"galacticraft_mars_dungeon_brick.png",
			Dimension.MARS,
			LayerIds.GTNH_MARS_DUNGEON),
	HOLLOW_ASTEROID(
			"HOLLOW_ASTEROID",
			"Hollow Asteroid",
			"minecraft_grass_side.png",
			Dimension.ASTEROIDS,
			LayerIds.GTNH_HOLLOW_ASTEROID),
	ASTEROID_BASE_HUMAN(
			"ASTEROID_BASE_HUMAN",
			"Abandoned Base (Human)",
			"galacticraft_decorative_tin.png",
			Dimension.ASTEROIDS,
			LayerIds.GTNH_ASTEROID_BASE_HUMAN),
	ASTEROID_BASE_MECHANICAL(
			"ASTEROID_BASE_MECHANICAL",
			"Abandoned Base (Mechanical)",
			"minecraft_iron_block.png",
			Dimension.ASTEROIDS,
			LayerIds.GTNH_ASTEROID_BASE_MECHANICAL),
	ASTEROID_BASE_BIRD(
			"ASTEROID_BASE_BIRD",
			"Abandoned Base (Bird)",
			"galacticraft_moon_rock.png",
			Dimension.ASTEROIDS,
			LayerIds.GTNH_ASTEROID_BASE_BIRD),
	CERES_DUNGEON(
			"CERES_DUNGEON",
			"Ceres Dungeon",
			"galaxyspace_ceres_dungeon_brick.png",
			Dimension.CERES,
			LayerIds.GTNH_CERES_DUNGEON),
	IO_DUNGEON(
			"IO_DUNGEON",
			"Io Dungeon",
			"galaxyspace_io_dungeon_brick.png",
			Dimension.IO,
			LayerIds.GTNH_IO_DUNGEON),
	ENCELADUS_DUNGEON(
			"ENCELADUS_DUNGEON",
			"Enceladus Dungeon",
			"galaxyspace_enceladus_dungeon_brick.png",
			Dimension.ENCELADUS,
			LayerIds.GTNH_ENCELADUS_DUNGEON),
	PROTEUS_DUNGEON(
			"PROTEUS_DUNGEON",
			"Proteus Dungeon (海卫八)",
			"galaxyspace_proteus_dungeon_brick.png",
			Dimension.PROTEUS,
			LayerIds.GTNH_PROTEUS_DUNGEON),
	PLUTO_DUNGEON(
			"PLUTO_DUNGEON",
			"Pluto Dungeon",
			"galaxyspace_pluto_dungeon_brick.png",
			Dimension.PLUTO,
			LayerIds.GTNH_PLUTO_DUNGEON),
	NETHER_MOLYBDENUM_VEIN(
			"NETHER_MOLYBDENUM_VEIN",
			"Possible Molybdenum Ore Vein",
			"gregtech_molybdenum_dust.png",
			Dimension.NETHER,
			LayerIds.GTNH_NETHER_MOLYBDENUM_VEIN),
	MEHEN_DARK_MATTER_ASTEROID(
			"MEHEN_DARK_MATTER_ASTEROID",
			"Dark Matter Asteroid",
			"avaritia_stellar_fuel.png",
			Dimension.MEHEN_BELT,
			LayerIds.GTNH_MEHEN_DARK_MATTER_ASTEROID),
	ROSS_128B_RUIN(
			"ROSS_128B_RUIN",
			"Possible Ross 128b Ruin",
			"minecraft_bricks.png",
			Dimension.ROSS_128B,
			LayerIds.GTNH_ROSS_128B_RUIN),
	ROSS_128B_ARSENOPYRITE_VEIN(
			"ROSS_128B_ARSENOPYRITE_VEIN",
			"Possible Arsenopyrite Ore Vein",
			"gregtech_indium_dust.png",
			Dimension.ROSS_128B,
			LayerIds.GTNH_ROSS_128B_ARSENOPYRITE_VEIN),
	DEEP_DARK_DUNGEON(
			"DEEP_DARK_DUNGEON",
			"Deep Dark Dungeon",
			"minecraft_stonebrick_mossy.png",
			Dimension.DEEP_DARK,
			LayerIds.GTNH_DEEP_DARK_DUNGEON),
	ANUBIS_ROBOT_VILLAGE(
			"ANUBIS_ROBOT_VILLAGE",
			"Robot Village",
			"galacticraft_solar_module_full.png",
			Dimension.ANUBIS,
			LayerIds.GTNH_ANUBIS_ROBOT_VILLAGE),
	HORUS_OBSIDIAN_PYRAMID(
			"HORUS_OBSIDIAN_PYRAMID",
			"Obsidian Pyramid",
			"amunra_obsidian_brick.png",
			Dimension.HORUS,
			LayerIds.GTNH_HORUS_OBSIDIAN_PYRAMID);

	private final String wireName;
	private final String displayName;
	private final String menuIcon;
	private final Dimension dimension;
	private final int layerId;
	private final WorldIconImage icon;

	GtnhSpaceStructureType(
			String wireName,
			String displayName,
			String menuIcon,
			Dimension dimension,
			int layerId) {
		this.wireName = wireName;
		this.displayName = displayName;
		this.menuIcon = menuIcon;
		this.dimension = dimension;
		this.layerId = layerId;
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

	public Dimension getDimension() {
		return dimension;
	}

	public int getLayerId() {
		return layerId;
	}

	public String getPreferenceKey() {
		return "gtnhSpace" + name();
	}

	public WorldIconImage getIcon() {
		return icon;
	}
}
