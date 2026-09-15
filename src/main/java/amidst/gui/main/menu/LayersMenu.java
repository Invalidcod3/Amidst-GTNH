package amidst.gui.main.menu;

import java.util.LinkedList;
import java.util.List;

import javax.swing.ButtonGroup;
import javax.swing.ImageIcon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuItem;

import amidst.AmidstSettings;
import amidst.ResourceLoader;
import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.layer.LayerIds;
import amidst.gtnh.structure.GtnhRoguelikeDungeonType;
import amidst.gtnh.structure.GtnhOverworldStructureType;
import amidst.gtnh.structure.GtnhNetherStructureType;
import amidst.gtnh.structure.GtnhEndStructureType;
import amidst.gtnh.structure.GtnhMoonStructureType;
import amidst.gtnh.structure.GtnhSpaceStructureType;
import amidst.gtnh.structure.GtnhTwilightForestFeatureType;
import amidst.gui.main.viewer.ViewerFacade;
import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;

@NotThreadSafe
public class LayersMenu {
	private final JMenu menu;
	private final AmidstSettings settings;
	private final Setting<Dimension> dimensionSetting;
	private final List<JMenuItem> overworldMenuItems = new LinkedList<>();
	private final List<JMenuItem> endMenuItems = new LinkedList<>();
	private volatile ViewerFacade viewerFacade;

	@CalledOnlyBy(AmidstThread.EDT)
	public LayersMenu(JMenu menu, AmidstSettings settings) {
		this.menu = menu;
		this.settings = settings;
		this.dimensionSetting = settings.dimension
				.withListener((oldValue, newValue) -> {
                    settings.prospectingFilter.set(""); this.createMenu(newValue);
                });
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void init(ViewerFacade viewerFacade) {
		this.viewerFacade = viewerFacade;
		if (viewerFacade != null) {
			createMenu(dimensionSetting.get());
		} else {
			disable();
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createMenu(Dimension selectedDimension) {
        var prospecting = viewerFacade.prospectingCatalog().stream()
                .filter(d -> selectedDimension.prospectingKey().equals(d.key)).findFirst().orElse(null);
        if (prospecting != null && (selectedDimension.isAdditional() && !viewerFacade.hasBiomeLayer(selectedDimension)
                && settings.markerMode.get() == amidst.gtnh.prospecting.MarkerMode.STRUCTURE
                || settings.markerMode.get() == amidst.gtnh.prospecting.MarkerMode.ORES && !prospecting.ores
                || settings.markerMode.get() == amidst.gtnh.prospecting.MarkerMode.FLUID && !prospecting.fluids))
            settings.markerMode.set(prospecting.ores ? amidst.gtnh.prospecting.MarkerMode.ORES : amidst.gtnh.prospecting.MarkerMode.FLUID);
		menu.removeAll();
		overworldMenuItems.clear();
		endMenuItems.clear();
		createDimensionLayers(selectedDimension);
        amidst.i18n.I18n.localize(menu);
		menu.setEnabled(true);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createDimensionLayers(Dimension dimension) {
		boolean hasNether = viewerFacade.hasNetherBiomeLayer();
		boolean hasEnd = viewerFacade.hasEndBiomeLayer()
				|| viewerFacade.hasLayer(LayerIds.END_ISLANDS);
		boolean hasMoon = viewerFacade.hasMoonBiomeLayer();
		boolean hasTwilightForest = viewerFacade.hasTwilightForestBiomeLayer();
		boolean supportedDimension = dimension == Dimension.OVERWORLD
				|| (dimension == Dimension.NETHER && hasNether)
				|| (dimension == Dimension.END && hasEnd)
				|| (dimension == Dimension.MOON && hasMoon)
				|| (dimension == Dimension.TWILIGHT_FOREST && hasTwilightForest)
				|| viewerFacade.hasBiomeLayer(dimension) || viewerFacade.hasProspecting(dimension);
		if (!supportedDimension) {
			dimensionSetting.set(Dimension.OVERWORLD);
			return;
		}
		boolean hasAlternativeDimension =
				hasNether || hasEnd || hasMoon || hasTwilightForest;
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			hasAlternativeDimension |= viewerFacade.hasBiomeLayer(type.getDimension());
		}
		if (hasAlternativeDimension) {
			createDimensionMenu();
            createMarkerMenu();
			menu.addSeparator();
			createAllDimensions();
			createSelectedDimensionLayers(dimension);
		} else if (!dimension.equals(Dimension.OVERWORLD)) {
			dimensionSetting.set(Dimension.OVERWORLD);
		} else {
			createAllDimensions();
			menu.addSeparator();
			createOverworldLayers(dimension);
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createDimensionMenu() {
		JMenu dimensionMenu = new JMenu("Dimension");
        if (!viewerFacade.prospectingCatalog().isEmpty()) {
            ButtonGroup group = new ButtonGroup();
            java.util.List<Dimension> all = new java.util.ArrayList<>(java.util.Arrays.asList(Dimension.values()));
            all.sort(java.util.Comparator.comparing((Dimension d) -> d.isAdditional()).thenComparing(Dimension::getDisplayName));
            JMenu more = new JMenu("More dimensions");
            for (Dimension d : all) {
                var info = viewerFacade.prospectingCatalog().stream().filter(i -> i.key.equals(d.prospectingKey())).findFirst().orElse(null);
                if (!amidst.gtnh.export.GtnhCoordinateType.hasContent(d, viewerFacade.prospectingCatalog())) continue;
                var item = Menus.radio(d.isAdditional() ? more : dimensionMenu, dimensionSetting, group, d,
                        amidst.gtnh.prospecting.ProspectingOverlay.menuIcon(info));
                if (info != null) item.setToolTipText((info.ores ? "Ores" : "")
                        + (info.ores && info.fluids ? " / " : "") + (info.fluids ? "Fluid" : ""));
            }
            if (more.getItemCount() > 0) dimensionMenu.add(more);
            menu.add(dimensionMenu);
            return;
        }
		// @formatter:off
		ButtonGroup group = new ButtonGroup();
		Menus.radio(dimensionMenu, dimensionSetting, group, Dimension.OVERWORLD, MenuShortcuts.DISPLAY_DIMENSION_OVERWORLD);
		if (viewerFacade.hasNetherBiomeLayer()) {
			Menus.radio(dimensionMenu, dimensionSetting, group, Dimension.NETHER, MenuShortcuts.DISPLAY_DIMENSION_NETHER);
		}
		if (viewerFacade.hasEndBiomeLayer() || viewerFacade.hasLayer(LayerIds.END_ISLANDS)) {
			Menus.radio(dimensionMenu, dimensionSetting, group, Dimension.END, MenuShortcuts.DISPLAY_DIMENSION_END);
		}
		if (viewerFacade.hasMoonBiomeLayer()) {
			Menus.radio(
					dimensionMenu,
					dimensionSetting,
					group,
					Dimension.MOON,
					MenuShortcuts.DISPLAY_DIMENSION_MOON);
		}
		if (viewerFacade.hasTwilightForestBiomeLayer()) {
			Menus.radio(
					dimensionMenu,
					dimensionSetting,
					group,
					Dimension.TWILIGHT_FOREST,
					MenuShortcuts.DISPLAY_DIMENSION_TWILIGHT_FOREST);
		}
		for (Dimension dimension : new Dimension[] {
				Dimension.MARS,
				Dimension.ASTEROIDS,
				Dimension.CERES,
				Dimension.IO,
				Dimension.ENCELADUS,
				Dimension.PROTEUS,
				Dimension.PLUTO,
				Dimension.MEHEN_BELT,
				Dimension.ROSS_128B,
				Dimension.BARNARDA_C,
				Dimension.DEEP_DARK,
				Dimension.ANUBIS,
				Dimension.HORUS
		}) {
			if (viewerFacade.hasBiomeLayer(dimension)) {
				Menus.radio(dimensionMenu, dimensionSetting, group, dimension);
			}
		}
		// @formatter:on
		menu.add(dimensionMenu);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createSelectedDimensionLayers(Dimension dimension) {
        if (settings.markerMode.get() != amidst.gtnh.prospecting.MarkerMode.STRUCTURE) {
            JMenuItem filter = new JMenuItem("Filter " + settings.markerMode.get() + " ...");
            filter.addActionListener(e -> {
                var info = viewerFacade.prospectingCatalog().stream()
                        .filter(d -> dimension.prospectingKey().equals(d.key)).findFirst().orElse(null);
                amidst.gtnh.prospecting.ProspectingFilterPanel.show(menu, info, settings.markerMode.get(), settings);
            });
            menu.add(filter);
            JMenuItem refresh = new JMenuItem("Refresh prospecting");
            refresh.addActionListener(e -> viewerFacade.refreshProspecting());
            menu.add(refresh);
            return;
        }
		if (dimension == Dimension.OVERWORLD) {
			menu.addSeparator();
			createOverworldLayers(dimension);
		} else if (dimension == Dimension.NETHER) {
			menu.addSeparator();
			createNetherLayers(dimension);
			createGtnhSpaceStructureLayers(dimension);
		} else if (dimension == Dimension.END) {
			menu.addSeparator();
			endLayer(
					settings.showEndCities,
					"End City Icons",
					getIcon("end_city.png"),
					MenuShortcuts.SHOW_END_CITIES,
					dimension,
					LayerIds.END_CITY);
			createGtnhEndLayers();
		} else if (dimension == Dimension.MOON) {
			menu.addSeparator();
			addGtnhMoonStructureLayer(
					settings.showGtnhMoonDungeons,
					GtnhMoonStructureType.MOON_DUNGEON,
					LayerIds.GTNH_MOON_DUNGEON);
			addGtnhMoonStructureLayer(
					settings.showGtnhMoonVillages,
					GtnhMoonStructureType.MOON_VILLAGE,
					LayerIds.GTNH_MOON_VILLAGE);
		} else if (dimension == Dimension.TWILIGHT_FOREST) {
			menu.addSeparator();
			JMenu landmarksMenu = new JMenu("Magic Map Landmarks");
			landmarksMenu.setIcon(getIcon("twilight_magic_map.png"));
			for (GtnhTwilightForestFeatureType type :
					GtnhTwilightForestFeatureType.values()) {
				if (viewerFacade.hasLayer(type.getLayerId())) {
					Menus.checkbox(
							landmarksMenu,
							settings.getShowGtnhTwilightForestFeature(type),
							type.getDisplayName(),
							getIcon(type.getIconFile()));
				}
			}
			if (landmarksMenu.getItemCount() > 0) {
				menu.add(landmarksMenu);
			}
		} else {
			menu.addSeparator();
			createGtnhSpaceStructureLayers(dimension);
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createGtnhSpaceStructureLayers(Dimension dimension) {
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			if (type.getDimension() == dimension
					&& viewerFacade.hasLayer(type.getLayerId())) {
				Menus.checkbox(
						menu,
						settings.getShowGtnhSpaceStructure(type),
						type.getDisplayName(),
						getIcon(type.getMenuIcon()));
			}
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void addGtnhMoonStructureLayer(
			Setting<Boolean> setting,
			GtnhMoonStructureType type,
			int layerId) {
		if (viewerFacade.hasLayer(layerId)) {
			Menus.checkbox(
					menu,
					setting,
					type.getDisplayName(),
					getIcon(type.getMenuIcon()));
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createGtnhEndLayers() {
		for (GtnhEndStructureType type : GtnhEndStructureType.values()) {
			addGtnhEndStructureLayer(
					settings.getShowGtnhEndStructure(type),
					type,
					type.getLayerId());
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void addGtnhEndStructureLayer(
			Setting<Boolean> setting,
			GtnhEndStructureType type,
			int layerId) {
		if (viewerFacade.hasLayer(layerId)) {
			endMenuItems.add(Menus.checkbox(
					menu,
					setting,
					type.getDisplayName(),
					getIcon(type.getMenuIcon())));
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createOverworldLayers(Dimension dimension) {
		// @formatter:off
		overworldLayer(settings.showSlimeChunks,          "Slime Chunks",           getIcon("slime.png"),           MenuShortcuts.SHOW_SLIME_CHUNKS,      dimension, LayerIds.SLIME);
		overworldLayer(settings.showSpawn,                "Spawn Location Icon",    getIcon("spawn.png"),           MenuShortcuts.SHOW_WORLD_SPAWN,       dimension, LayerIds.SPAWN);
		overworldLayer(settings.showStrongholds,          "Stronghold Icons",       getIcon("stronghold.png"),      MenuShortcuts.SHOW_STRONGHOLDS,       dimension, LayerIds.STRONGHOLD);
		overworldLayer(settings.showVillages,             "Village/Outpost Icons",  getIcon("village.png"),         MenuShortcuts.SHOW_VILLAGES,          dimension, LayerIds.VILLAGE);
		overworldLayer(settings.showTemples,              "Temple/Witch Hut Icons", getIcon("desert.png"),          MenuShortcuts.SHOW_TEMPLES,           dimension, LayerIds.TEMPLE);
		overworldLayer(settings.showMineshafts,           "Mineshaft Icons",        getIcon("mineshaft.png"),       MenuShortcuts.SHOW_MINESHAFTS,        dimension, LayerIds.MINESHAFT);
		overworldLayer(settings.showOceanMonuments,       "Ocean Monument Icons",   getIcon("ocean_monument.png"),  MenuShortcuts.SHOW_OCEAN_MONUMENTS,   dimension, LayerIds.OCEAN_MONUMENT);
		overworldLayer(settings.showWoodlandMansions,     "Woodland Mansion Icons", getIcon("woodland_mansion.png"),MenuShortcuts.SHOW_WOODLAND_MANSIONS, dimension, LayerIds.WOODLAND_MANSION);
		overworldLayer(settings.showOceanFeatures,        "Ocean Features Icons",   getIcon("shipwreck.png"),       MenuShortcuts.SHOW_OCEAN_FEATURES,    dimension, LayerIds.OCEAN_FEATURES);
		overworldLayer(settings.showNetherFortresses,     "Nether Features Icons",  getIcon("nether_fortress.png"), MenuShortcuts.SHOW_NETHER_FEATURES,   dimension, LayerIds.NETHER_FEATURES);
		// @formatter:on
		createGtnhOverworldStructureLayers();
		createGtnhRoguelikeLayers();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createNetherLayers(Dimension dimension) {
		overworldLayer(
				settings.showNetherFortresses,
				"Nether Fortress Icons",
				getIcon("nether_fortress.png"),
				MenuShortcuts.SHOW_NETHER_FEATURES,
				dimension,
				LayerIds.GTNH_NETHER_FORTRESS);
		addGtnhNetherStructureLayer(
				settings.showGtnhTinkersNetherSlimeIslands,
				GtnhNetherStructureType.TINKERS_NETHER_SLIME_ISLAND,
				LayerIds.GTNH_TINKERS_NETHER_SLIME_ISLAND);
		addGtnhNetherStructureLayer(
				settings.showGtnhAutomagyNetherSpires,
				GtnhNetherStructureType.AUTOMAGY_NETHER_SPIRE,
				LayerIds.GTNH_AUTOMAGY_NETHER_SPIRE);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void addGtnhNetherStructureLayer(
			Setting<Boolean> setting,
			GtnhNetherStructureType type,
			int layerId) {
		if (viewerFacade.hasLayer(layerId)) {
			overworldMenuItems.add(Menus.checkbox(
					menu,
					setting,
					type.getDisplayName(),
					getIcon(type.getMenuIcon())));
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createGtnhOverworldStructureLayers() {
		addGtnhStructureLayer(
				settings.showGtnhWorldSpawn,
				GtnhOverworldStructureType.WORLD_SPAWN,
				LayerIds.GTNH_WORLD_SPAWN);
		addGtnhStructureLayer(
				settings.showGtnhStrongholds,
				GtnhOverworldStructureType.STRONGHOLD,
				LayerIds.GTNH_STRONGHOLD);
		addGtnhStructureLayer(
				settings.showGtnhVillages,
				GtnhOverworldStructureType.VILLAGE,
				LayerIds.GTNH_VILLAGE);
		addGtnhStructureLayer(
				settings.showGtnhMineshafts,
				GtnhOverworldStructureType.MINESHAFT,
				LayerIds.GTNH_MINESHAFT);
		addGtnhStructureLayer(
				settings.showGtnhLootGamesDungeons,
				GtnhOverworldStructureType.LOOTGAMES_DUNGEON,
				LayerIds.GTNH_LOOTGAMES_DUNGEON);
		addGtnhStructureLayer(
				settings.showGtnhTinkersSlimeIslands,
				GtnhOverworldStructureType.TINKERS_SLIME_ISLAND,
				LayerIds.GTNH_TINKERS_SLIME_ISLAND);
		addGtnhStructureLayer(
				settings.showGtnhVanillaSpawnerDungeons,
				GtnhOverworldStructureType.VANILLA_SPAWNER_DUNGEON,
				LayerIds.GTNH_VANILLA_SPAWNER_DUNGEON);
		addGtnhStructureLayer(
				settings.showGtnhThaumcraftAuraNodes,
				GtnhOverworldStructureType.THAUMCRAFT_AURA_NODE,
				LayerIds.GTNH_THAUMCRAFT_AURA_NODE);
		addGtnhStructureLayer(
				settings.showGtnhThaumcraftEldritchAltars,
				GtnhOverworldStructureType.THAUMCRAFT_ELDRITCH_ALTAR,
				LayerIds.GTNH_THAUMCRAFT_ELDRITCH_ALTAR);
		addGtnhStructureLayer(
				settings.showGtnhAe2Meteorites,
				GtnhOverworldStructureType.AE2_METEORITE,
				LayerIds.GTNH_AE2_METEORITE);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void addGtnhStructureLayer(
			Setting<Boolean> setting,
			GtnhOverworldStructureType type,
			int layerId) {
		if (viewerFacade.hasLayer(layerId)) {
			overworldMenuItems.add(Menus.checkbox(
					menu,
					setting,
					type.getDisplayName(),
					getIcon(type.getMenuIcon())));
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createGtnhRoguelikeLayers() {
		int[] layerIds = {
				LayerIds.GTNH_ROGUELIKE_DESERT,
				LayerIds.GTNH_ROGUELIKE_FOREST,
				LayerIds.GTNH_ROGUELIKE_ICE,
				LayerIds.GTNH_ROGUELIKE_JUNGLE,
				LayerIds.GTNH_ROGUELIKE_MESA,
				LayerIds.GTNH_ROGUELIKE_MOUNTAIN,
				LayerIds.GTNH_ROGUELIKE_PLAINS,
				LayerIds.GTNH_ROGUELIKE_SWAMP
		};
		boolean supported = false;
		for (int layerId : layerIds) {
			supported |= viewerFacade.hasLayer(layerId);
		}
		if (!supported) {
			return;
		}

		JMenu roguelikeMenu = new JMenu("Possible Roguelike Dungeons");
		roguelikeMenu.setIcon(getIcon("mineshaft.png"));
		roguelikeMenu.setToolTipText(
				"Seed-predicted entrances; final generation still depends on terrain blocks");
		GtnhRoguelikeDungeonType[] types = GtnhRoguelikeDungeonType.values();
		for (int i = 0; i < types.length; i++) {
			GtnhRoguelikeDungeonType type = types[i];
			if (viewerFacade.hasLayer(layerIds[i])) {
				Menus.checkbox(
						roguelikeMenu,
						settings.getShowGtnhRoguelike(type),
						type.getDisplayName() + " — " + type.getEntranceName(),
						getIcon(type.getMenuIcon()));
			}
		}
		menu.add(roguelikeMenu);
		overworldMenuItems.add(roguelikeMenu);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void createAllDimensions() {
		// @formatter:off
		Menus.checkbox(menu, settings.showGrid,           "Grid",                   getIcon("grid.png"),            MenuShortcuts.SHOW_GRID);
		Menus.checkbox(menu, settings.showPlayers,        "Player Icons",           getIcon("player.png"),          MenuShortcuts.SHOW_PLAYERS);
		// @formatter:on
	}

    private void createMarkerMenu() {
        if (viewerFacade.prospectingCatalog().isEmpty()) return;
        JMenu markers = new JMenu("Markers");
        ButtonGroup group = new ButtonGroup();
        Setting<amidst.gtnh.prospecting.MarkerMode> modeSetting = settings.markerMode.withListener((a, b) -> {
            settings.prospectingFilter.set(""); createMenu(dimensionSetting.get());
        });
        for (var mode : amidst.gtnh.prospecting.MarkerMode.values()) {
            String icon = mode == amidst.gtnh.prospecting.MarkerMode.ORES ? "prospecting_ores.png"
                    : mode == amidst.gtnh.prospecting.MarkerMode.FLUID ? "prospecting_fluid.png" : "village.png";
            var item = Menus.radio(markers, modeSetting, group, mode, getIcon(icon));
            var info = viewerFacade.prospectingCatalog().stream()
                    .filter(d -> dimensionSetting.get().prospectingKey().equals(d.key)).findFirst().orElse(null);
            item.setEnabled(mode == amidst.gtnh.prospecting.MarkerMode.STRUCTURE ? (!dimensionSetting.get().isAdditional() || viewerFacade.hasBiomeLayer(dimensionSetting.get()))
                    : info != null && (mode == amidst.gtnh.prospecting.MarkerMode.ORES ? info.ores : info.fluids));
        }
        menu.add(markers);
    }

	@CalledOnlyBy(AmidstThread.EDT)
	public void overworldLayer(
			Setting<Boolean> setting,
			String text,
			ImageIcon icon,
			MenuShortcut menuShortcut,
			Dimension dimension,
			int layerId) {
		if (viewerFacade.hasLayer(layerId)) {
			overworldMenuItems.add(createLayer(setting, text, icon, menuShortcut, dimension, layerId));
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void endLayer(
			Setting<Boolean> setting,
			String text,
			ImageIcon icon,
			MenuShortcut menuShortcut,
			Dimension dimension,
			int layerId) {
		if (viewerFacade.hasLayer(layerId)) {
			endMenuItems.add(createLayer(setting, text, icon, menuShortcut, dimension, layerId));
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private JCheckBoxMenuItem createLayer(
			Setting<Boolean> setting,
			String text,
			ImageIcon icon,
			MenuShortcut menuShortcut,
			Dimension dimension,
			int layerId) {
		return  Menus.checkbox(menu, setting, text, icon, menuShortcut);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void disable() {
		this.viewerFacade = null;
		menu.setEnabled(false);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private ImageIcon getIcon(String icon) {
		return new ImageIcon(ResourceLoader.getImage("/amidst/gui/main/icon/" + icon));
	}
}
