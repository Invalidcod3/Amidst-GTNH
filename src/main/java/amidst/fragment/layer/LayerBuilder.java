package amidst.fragment.layer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import amidst.AmidstSettings;
import amidst.documentation.Immutable;
import amidst.fragment.Fragment;
import amidst.fragment.colorprovider.BackgroundColorProvider;
import amidst.fragment.colorprovider.BiomeColorProvider;
import amidst.fragment.colorprovider.SlimeColorProvider;
import amidst.fragment.colorprovider.TheEndColorProvider;
import amidst.fragment.constructor.BiomeDataConstructor;
import amidst.fragment.constructor.EndIslandsConstructor;
import amidst.fragment.constructor.FragmentConstructor;
import amidst.fragment.constructor.ImageConstructor;
import amidst.fragment.drawer.AlphaUpdater;
import amidst.fragment.drawer.FragmentDrawer;
import amidst.fragment.drawer.GridDrawer;
import amidst.fragment.drawer.ImageDrawer;
import amidst.fragment.drawer.WorldIconDrawer;
import amidst.fragment.loader.AlphaInitializer;
import amidst.fragment.loader.BiomeDataLoader;
import amidst.fragment.loader.EndIslandsLoader;
import amidst.fragment.loader.FragmentLoader;
import amidst.fragment.loader.ImageLoader;
import amidst.fragment.loader.WorldIconLoader;
import amidst.gui.main.viewer.BiomeSelection;
import amidst.gui.main.viewer.Graphics2DAccelerationCounter;
import amidst.gui.main.viewer.WorldIconSelection;
import amidst.gui.main.viewer.Zoom;
import amidst.gtnh.structure.GtnhEndStructureType;
import amidst.gtnh.structure.GtnhMoonStructureType;
import amidst.gtnh.structure.GtnhNetherStructureType;
import amidst.gtnh.structure.GtnhOverworldStructureType;
import amidst.gtnh.structure.GtnhRoguelikeDungeonType;
import amidst.gtnh.structure.GtnhSpaceStructureType;
import amidst.gtnh.structure.GtnhTwilightForestFeatureType;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.settings.Setting;

@Immutable
public class LayerBuilder {
	private final Iterable<FragmentConstructor> constructors;

	public LayerBuilder() {
		this.constructors = createConstructors();
	}

	/**
	 * This also defines the construction order.
	 */
	private Iterable<FragmentConstructor> createConstructors() {
		return Collections.unmodifiableList(
				Arrays.asList(
						new BiomeDataConstructor(Resolution.QUARTER),
						new EndIslandsConstructor(),
						new ImageConstructor(Resolution.QUARTER, LayerIds.BACKGROUND),
						new ImageConstructor(Resolution.CHUNK, LayerIds.SLIME)));
	}

	public Iterable<FragmentConstructor> getConstructors() {
		return constructors;
	}

	public int getNumberOfLayers() {
		return LayerIds.NUMBER_OF_LAYERS;
	}

	public LayerManager create(
			AmidstSettings settings,
			World world,
			BiomeSelection biomeSelection,
			WorldIconSelection worldIconSelection,
			Zoom zoom,
			Graphics2DAccelerationCounter accelerationCounter) {
		List<LayerDeclaration> declarations = createDeclarations(settings, world.getEnabledLayers());
		return new LayerManager(
				declarations,
				new LayerLoader(
						createLoaders(declarations, world, biomeSelection, settings),
						LayerIds.NUMBER_OF_LAYERS),
				createDrawers(declarations, zoom, worldIconSelection, accelerationCounter,
						settings)
			);
	}

	private List<LayerDeclaration> createDeclarations(AmidstSettings settings, List<Integer> enabledLayers) {
		LayerDeclaration[] declarations = new LayerDeclaration[LayerIds.NUMBER_OF_LAYERS];
		// @formatter:off
		declare(settings, declarations, enabledLayers, LayerIds.ALPHA,           null,                false, Setting.createImmutable(true));
		declare(settings, declarations, enabledLayers, LayerIds.BIOME_DATA,      null,                false, Setting.createImmutable(true));
		declare(settings, declarations, enabledLayers, LayerIds.END_ISLANDS,     Dimension.END,       false, Setting.createImmutable(true));
		declare(settings, declarations, enabledLayers, LayerIds.BACKGROUND,      null,                false, Setting.createImmutable(true));
		declare(settings, declarations, enabledLayers, LayerIds.SLIME,           Dimension.OVERWORLD, false, settings.showSlimeChunks);
		declare(settings, declarations, enabledLayers, LayerIds.GRID,            null,                true,  settings.showGrid);
		declare(settings, declarations, enabledLayers, LayerIds.SPAWN,           Dimension.OVERWORLD, false, settings.showSpawn);
		declare(settings, declarations, enabledLayers, LayerIds.STRONGHOLD,      Dimension.OVERWORLD, false, settings.showStrongholds);
		declare(settings, declarations, enabledLayers, LayerIds.PLAYER,          null,                false, settings.showPlayers);
		declare(settings, declarations, enabledLayers, LayerIds.VILLAGE,         Dimension.OVERWORLD, false, settings.showVillages);
		declare(settings, declarations, enabledLayers, LayerIds.TEMPLE,          Dimension.OVERWORLD, false, settings.showTemples);
		declare(settings, declarations, enabledLayers, LayerIds.MINESHAFT,       Dimension.OVERWORLD, false, settings.showMineshafts);
		declare(settings, declarations, enabledLayers, LayerIds.OCEAN_MONUMENT,  Dimension.OVERWORLD, false, settings.showOceanMonuments);
		declare(settings, declarations, enabledLayers, LayerIds.WOODLAND_MANSION,Dimension.OVERWORLD, false, settings.showWoodlandMansions);
		declare(settings, declarations, enabledLayers, LayerIds.OCEAN_FEATURES,  Dimension.OVERWORLD, false, settings.showOceanFeatures);
		declare(settings, declarations, enabledLayers, LayerIds.NETHER_FEATURES, Dimension.OVERWORLD, false, settings.showNetherFortresses);
		declare(settings, declarations, enabledLayers, LayerIds.END_CITY,        Dimension.END,       false, settings.showEndCities);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_DESERT,  Dimension.OVERWORLD, false, settings.showGtnhRoguelikeDesert);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_FOREST,  Dimension.OVERWORLD, false, settings.showGtnhRoguelikeForest);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_ICE,     Dimension.OVERWORLD, false, settings.showGtnhRoguelikeIce);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_JUNGLE,  Dimension.OVERWORLD, false, settings.showGtnhRoguelikeJungle);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_MESA,    Dimension.OVERWORLD, false, settings.showGtnhRoguelikeMesa);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_MOUNTAIN,Dimension.OVERWORLD, false, settings.showGtnhRoguelikeMountain);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_PLAINS,  Dimension.OVERWORLD, false, settings.showGtnhRoguelikePlains);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_ROGUELIKE_SWAMP,   Dimension.OVERWORLD, false, settings.showGtnhRoguelikeSwamp);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_STRONGHOLD,         Dimension.OVERWORLD, false, settings.showGtnhStrongholds);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_VILLAGE,            Dimension.OVERWORLD, false, settings.showGtnhVillages);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_MINESHAFT,          Dimension.OVERWORLD, false, settings.showGtnhMineshafts);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_LOOTGAMES_DUNGEON,  Dimension.OVERWORLD, false, settings.showGtnhLootGamesDungeons);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_TINKERS_SLIME_ISLAND, Dimension.OVERWORLD, false, settings.showGtnhTinkersSlimeIslands);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_VANILLA_SPAWNER_DUNGEON, Dimension.OVERWORLD, false, settings.showGtnhVanillaSpawnerDungeons);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_THAUMCRAFT_AURA_NODE, Dimension.OVERWORLD, false, settings.showGtnhThaumcraftAuraNodes);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_THAUMCRAFT_ELDRITCH_ALTAR, Dimension.OVERWORLD, false, settings.showGtnhThaumcraftEldritchAltars);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_AE2_METEORITE, Dimension.OVERWORLD, false, settings.showGtnhAe2Meteorites);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_WORLD_SPAWN, Dimension.OVERWORLD, false, settings.showGtnhWorldSpawn);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_NETHER_FORTRESS, Dimension.NETHER, false, settings.showNetherFortresses);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_TINKERS_NETHER_SLIME_ISLAND, Dimension.NETHER, false, settings.showGtnhTinkersNetherSlimeIslands);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_AUTOMAGY_NETHER_SPIRE, Dimension.NETHER, false, settings.showGtnhAutomagyNetherSpires);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_HEE_BIOME_ISLAND, Dimension.END, false, settings.showGtnhHeeBiomeIslands);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_HEE_DUNGEON_TOWER, Dimension.END, false, settings.showGtnhHeeDungeonTowers);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_DRACONIC_CHAOS_ISLAND, Dimension.END, false, settings.showGtnhDraconicChaosIslands);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_MOON_DUNGEON, Dimension.MOON, false, settings.showGtnhMoonDungeons);
		declare(settings, declarations, enabledLayers, LayerIds.GTNH_MOON_VILLAGE, Dimension.MOON, false, settings.showGtnhMoonVillages);
		for (GtnhTwilightForestFeatureType type :
				GtnhTwilightForestFeatureType.values()) {
			declare(
					settings,
					declarations,
					enabledLayers,
					type.getLayerId(),
					Dimension.TWILIGHT_FOREST,
					false,
					settings.getShowGtnhTwilightForestFeature(type));
		}
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			declare(
					settings,
					declarations,
					enabledLayers,
					type.getLayerId(),
					type.getDimension(),
					false,
					settings.getShowGtnhSpaceStructure(type));
		}
		// @formatter:on
		return Collections.unmodifiableList(Arrays.asList(declarations));
	}

	private void declare(
			AmidstSettings settings,
			LayerDeclaration[] declarations,
			List<Integer> enabledLayers,
			int layerId,
			Dimension dimension,
			boolean drawUnloaded,
			Setting<Boolean> isVisibleSetting) {
		declarations[layerId] = new LayerDeclaration(
				layerId,
				dimension,
				drawUnloaded,
				enabledLayers.contains(layerId),
				isVisibleSetting);
	}

	/**
	 * This also defines the loading and reloading order.
	 */
	private Iterable<FragmentLoader> createLoaders(
			List<LayerDeclaration> declarations,
			World world,
			BiomeSelection biomeSelection,
			AmidstSettings settings) {
		// @formatter:off
		List<FragmentLoader> result = new ArrayList<>(Arrays.asList(
				new AlphaInitializer( declarations.get(LayerIds.ALPHA),           settings.fragmentFading),
				new BiomeDataLoader(  declarations.get(LayerIds.BIOME_DATA),      world.getOverworldBiomeDataOracle(), world.getNetherBiomeDataOracle(), world.getEndBiomeDataOracle(), world.getMoonBiomeDataOracle(), world.getTwilightForestBiomeDataOracle(), world.getSpaceBiomeDataOracles()),
				new EndIslandsLoader( declarations.get(LayerIds.END_ISLANDS),     world.getEndIslandOracle()),
				new ImageLoader(	  declarations.get(LayerIds.BACKGROUND),      Resolution.QUARTER, new BackgroundColorProvider(new BiomeColorProvider(biomeSelection, settings.biomeProfileSelection), new TheEndColorProvider(), world.getEndBiomeDataOracle().isPresent())),
				new ImageLoader(      declarations.get(LayerIds.SLIME),           Resolution.CHUNK,   new SlimeColorProvider(world.getSlimeChunkOracle())),
				new WorldIconLoader<>(declarations.get(LayerIds.SPAWN),           world.getSpawnProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.STRONGHOLD),      world.getStrongholdProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.PLAYER),          world.getPlayerProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.VILLAGE),         world.getVillageProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.TEMPLE),          world.getTempleProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.MINESHAFT),       world.getMineshaftProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.OCEAN_MONUMENT),  world.getOceanMonumentProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.WOODLAND_MANSION),world.getWoodlandMansionProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.OCEAN_FEATURES),  world.getOceanFeaturesProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.NETHER_FEATURES), world.getNetherFortressProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.END_CITY),        world.getEndCityProducer(), Fragment::getEndIslands),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_DESERT),  world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.DESERT)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_FOREST),  world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.FOREST)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_ICE),     world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.ICE)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_JUNGLE),  world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.JUNGLE)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_MESA),    world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.MESA)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_MOUNTAIN),world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.MOUNTAIN)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_PLAINS),  world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.PLAINS)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_ROGUELIKE_SWAMP),   world.getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType.SWAMP)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_STRONGHOLD),         world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.STRONGHOLD)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_VILLAGE),            world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.VILLAGE)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_MINESHAFT),          world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.MINESHAFT)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_LOOTGAMES_DUNGEON),  world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.LOOTGAMES_DUNGEON)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_TINKERS_SLIME_ISLAND), world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.TINKERS_SLIME_ISLAND)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_VANILLA_SPAWNER_DUNGEON), world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.VANILLA_SPAWNER_DUNGEON)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_THAUMCRAFT_AURA_NODE), world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.THAUMCRAFT_AURA_NODE)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_THAUMCRAFT_ELDRITCH_ALTAR), world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.THAUMCRAFT_ELDRITCH_ALTAR)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_AE2_METEORITE), world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.AE2_METEORITE)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_WORLD_SPAWN), world.getGtnhOverworldStructureProducer(GtnhOverworldStructureType.WORLD_SPAWN)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_NETHER_FORTRESS), world.getGtnhNetherFortressProducer()),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_TINKERS_NETHER_SLIME_ISLAND), world.getGtnhNetherStructureProducer(GtnhNetherStructureType.TINKERS_NETHER_SLIME_ISLAND)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_AUTOMAGY_NETHER_SPIRE), world.getGtnhNetherStructureProducer(GtnhNetherStructureType.AUTOMAGY_NETHER_SPIRE)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_HEE_BIOME_ISLAND), world.getGtnhEndStructureProducer(GtnhEndStructureType.HEE_BIOME_ISLAND)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_HEE_DUNGEON_TOWER), world.getGtnhEndStructureProducer(GtnhEndStructureType.HEE_DUNGEON_TOWER)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_DRACONIC_CHAOS_ISLAND), world.getGtnhEndStructureProducer(GtnhEndStructureType.DRACONIC_CHAOS_ISLAND)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_MOON_DUNGEON), world.getGtnhMoonStructureProducer(GtnhMoonStructureType.MOON_DUNGEON)),
				new WorldIconLoader<>(declarations.get(LayerIds.GTNH_MOON_VILLAGE), world.getGtnhMoonStructureProducer(GtnhMoonStructureType.MOON_VILLAGE))
		));
		for (GtnhTwilightForestFeatureType type :
				GtnhTwilightForestFeatureType.values()) {
			result.add(new WorldIconLoader<>(
					declarations.get(type.getLayerId()),
					world.getGtnhTwilightForestFeatureProducer(type)));
		}
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			result.add(new WorldIconLoader<>(
					declarations.get(type.getLayerId()),
					world.getGtnhSpaceStructureProducer(type)));
		}
		return Collections.unmodifiableList(result);
		// @formatter:on
	}

	/**
	 * This also defines the rendering order.
	 */
	private Iterable<FragmentDrawer> createDrawers(
			List<LayerDeclaration> declarations,
			Zoom zoom,
			WorldIconSelection worldIconSelection,
			Graphics2DAccelerationCounter accelerationCounter,
			AmidstSettings settings) {
		// @formatter:off
		List<FragmentDrawer> result = new ArrayList<>(Arrays.asList(
				new AlphaUpdater(   declarations.get(LayerIds.ALPHA)),
				new ImageDrawer(    declarations.get(LayerIds.BACKGROUND),      Resolution.QUARTER, accelerationCounter, settings.useHybridScaling),
				new ImageDrawer(    declarations.get(LayerIds.SLIME),           Resolution.CHUNK,   accelerationCounter, settings.useHybridScaling),
				new GridDrawer(     declarations.get(LayerIds.GRID),            zoom),
				new WorldIconDrawer(declarations.get(LayerIds.SPAWN),           zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.STRONGHOLD),      zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.PLAYER),          zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.VILLAGE),         zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.TEMPLE),          zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.MINESHAFT),       zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.OCEAN_MONUMENT),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.WOODLAND_MANSION),zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.OCEAN_FEATURES),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.NETHER_FEATURES), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.END_CITY),        zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_DESERT),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_FOREST),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_ICE),     zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_JUNGLE),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_MESA),    zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_MOUNTAIN),zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_PLAINS),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_ROGUELIKE_SWAMP),   zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_STRONGHOLD),         zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_VILLAGE),            zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_MINESHAFT),          zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_LOOTGAMES_DUNGEON),  zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_TINKERS_SLIME_ISLAND), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_VANILLA_SPAWNER_DUNGEON), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_THAUMCRAFT_AURA_NODE), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_THAUMCRAFT_ELDRITCH_ALTAR), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_AE2_METEORITE), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_WORLD_SPAWN), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_NETHER_FORTRESS), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_TINKERS_NETHER_SLIME_ISLAND), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_AUTOMAGY_NETHER_SPIRE), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_HEE_BIOME_ISLAND), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_HEE_DUNGEON_TOWER), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_DRACONIC_CHAOS_ISLAND), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_MOON_DUNGEON), zoom, worldIconSelection, settings.useHybridScaling),
				new WorldIconDrawer(declarations.get(LayerIds.GTNH_MOON_VILLAGE), zoom, worldIconSelection, settings.useHybridScaling)
		));
		for (GtnhTwilightForestFeatureType type :
				GtnhTwilightForestFeatureType.values()) {
			result.add(new WorldIconDrawer(
					declarations.get(type.getLayerId()),
					zoom,
					worldIconSelection,
					settings.useHybridScaling));
		}
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			result.add(new WorldIconDrawer(
					declarations.get(type.getLayerId()),
					zoom,
					worldIconSelection,
					settings.useHybridScaling));
		}
		return Collections.unmodifiableList(result);
		// @formatter:on
	}
}
