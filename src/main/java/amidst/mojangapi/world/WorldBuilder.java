package amidst.mojangapi.world;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import amidst.documentation.Immutable;
import amidst.fragment.layer.LayerIds;
import amidst.gtnh.structure.GtnhRoguelikeDungeonProducers;
import amidst.gtnh.structure.GtnhEndStructureType;
import amidst.gtnh.structure.GtnhSpaceStructureType;
import amidst.gtnh.structure.GtnhTwilightForestFeatureType;
import amidst.gtnh.worker.GtnhBiomeCatalogProvider;
import amidst.mojangapi.file.ImmutablePlayerInformationProvider;
import amidst.mojangapi.file.PlayerInformationProvider;
import amidst.mojangapi.file.SaveGame;
import amidst.mojangapi.minecraftinterface.LoggingMinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.ThreadedWorldAccessor;
import amidst.mojangapi.world.icon.producer.MultiProducer;
import amidst.mojangapi.world.icon.producer.PlayerProducer;
import amidst.mojangapi.world.icon.producer.SpawnProducer;
import amidst.mojangapi.world.oracle.ImmutableWorldSpawnOracle;
import amidst.mojangapi.world.oracle.WorldSpawnOracle;
import amidst.mojangapi.world.player.MovablePlayerList;
import amidst.mojangapi.world.player.PlayerInformation;
import amidst.mojangapi.world.player.WorldPlayerType;
import amidst.mojangapi.world.versionfeatures.DefaultVersionFeatures;
import amidst.mojangapi.world.versionfeatures.FeatureKey;
import amidst.mojangapi.world.versionfeatures.VersionFeatures;

@Immutable
public class WorldBuilder {
	/**
	 * Create a new WorldBuilder that does not log any seeds and that provides
	 * the singleplayer player information for each requested player.
	 */
	public static WorldBuilder createSilentPlayerless() {
		return new WorldBuilder(
				new ImmutablePlayerInformationProvider(PlayerInformation.theSingleplayerPlayer()),
				SeedHistoryLogger.createDisabled());
	}

	private final PlayerInformationProvider playerInformationProvider;
	private final SeedHistoryLogger seedHistoryLogger;

	public WorldBuilder(PlayerInformationProvider playerInformationProvider, SeedHistoryLogger seedHistoryLogger) {
		this.playerInformationProvider = playerInformationProvider;
		this.seedHistoryLogger = seedHistoryLogger;
	}

	public World from(
			MinecraftInterface minecraftInterface,
			WorldOptions worldOptions) throws MinecraftInterfaceException {
		VersionFeatures versionFeatures = initInterfaceAndGetFeatures(worldOptions, minecraftInterface);
		return create(
				minecraftInterface,
				minecraftInterface.getRecognisedVersion(),
				MovablePlayerList.dummy(),
				versionFeatures,
				versionFeatures.get(FeatureKey.WORLD_SPAWN_ORACLE));
	}

	public World fromSaveGame(MinecraftInterface minecraftInterface, SaveGame saveGame)
			throws IOException,
			MinecraftInterfaceException {
		VersionFeatures versionFeatures = initInterfaceAndGetFeatures(WorldOptions.fromSaveGame(saveGame), minecraftInterface);
		return create(
				minecraftInterface,
				minecraftInterface.getRecognisedVersion(),
				new MovablePlayerList(
					playerInformationProvider,
					saveGame,
					true,
					WorldPlayerType.from(saveGame)),
				versionFeatures,
                minecraftInterface instanceof amidst.gtnh.worker.GtnhMinecraftInterface
                        ? versionFeatures.get(FeatureKey.WORLD_SPAWN_ORACLE)
                        : new ImmutableWorldSpawnOracle(saveGame.getWorldSpawn()));
	}

	private VersionFeatures initInterfaceAndGetFeatures(WorldOptions worldOptions, MinecraftInterface minecraftInterface)
		throws MinecraftInterfaceException {
		RecognisedVersion recognisedVersion = minecraftInterface.getRecognisedVersion();
		if(minecraftInterface instanceof LoggingMinecraftInterface) {
			((LoggingMinecraftInterface) minecraftInterface).logNextAccessor();
		}
		MinecraftInterface.WorldAccessor worldAccessor = new ThreadedWorldAccessor(v -> minecraftInterface.createWorldAccessor(worldOptions));
		seedHistoryLogger.log(recognisedVersion, worldOptions.getWorldSeed());
		VersionFeatures.Builder features = DefaultVersionFeatures.builder(worldOptions, worldAccessor);
		if (minecraftInterface instanceof GtnhBiomeCatalogProvider gtnh) {
			long seed = worldOptions.getWorldSeed().getLong();
			features
					.withValueReplacing(FeatureKey.BIOME_LIST, gtnh.getBiomeList())
					.withValueReplacing(
							FeatureKey.ENABLED_LAYERS,
							createGtnhEnabledLayers(
									LayerIds.ALPHA,
									LayerIds.BIOME_DATA,
									LayerIds.BACKGROUND,
									LayerIds.GRID,
									LayerIds.GTNH_ROGUELIKE_DESERT,
									LayerIds.GTNH_ROGUELIKE_FOREST,
									LayerIds.GTNH_ROGUELIKE_ICE,
									LayerIds.GTNH_ROGUELIKE_JUNGLE,
									LayerIds.GTNH_ROGUELIKE_MESA,
									LayerIds.GTNH_ROGUELIKE_MOUNTAIN,
									LayerIds.GTNH_ROGUELIKE_PLAINS,
									LayerIds.GTNH_ROGUELIKE_SWAMP,
									LayerIds.GTNH_STRONGHOLD,
									LayerIds.GTNH_VILLAGE,
									LayerIds.GTNH_MINESHAFT,
									LayerIds.GTNH_LOOTGAMES_DUNGEON,
									LayerIds.GTNH_TINKERS_SLIME_ISLAND,
									LayerIds.GTNH_VANILLA_SPAWNER_DUNGEON,
									LayerIds.GTNH_THAUMCRAFT_AURA_NODE,
									LayerIds.GTNH_THAUMCRAFT_ELDRITCH_ALTAR,
									LayerIds.GTNH_AE2_METEORITE,
									LayerIds.GTNH_WORLD_SPAWN,
									LayerIds.GTNH_NETHER_FORTRESS,
									LayerIds.GTNH_TINKERS_NETHER_SLIME_ISLAND,
									LayerIds.GTNH_AUTOMAGY_NETHER_SPIRE,
									LayerIds.GTNH_HEE_BIOME_ISLAND,
									LayerIds.GTNH_HEE_DUNGEON_TOWER,
									LayerIds.GTNH_DRACONIC_CHAOS_ISLAND,
									LayerIds.GTNH_MOON_DUNGEON,
									LayerIds.GTNH_MOON_VILLAGE));
			if (minecraftInterface instanceof amidst.gtnh.worker.GtnhMinecraftInterface gtnhInterface) {
				features.withValueReplacing(
						FeatureKey.WORLD_SPAWN_ORACLE,
                        new amidst.gtnh.worker.GtnhSpawnOracle(() -> gtnhInterface.getWorldSpawnPoint(seed)));
			}
		}
		return features.create(recognisedVersion);
	}

	private static List<Integer> createGtnhEnabledLayers(Integer... baseLayers) {
		List<Integer> result = new ArrayList<>(Arrays.asList(baseLayers));
		for (GtnhTwilightForestFeatureType type :
				GtnhTwilightForestFeatureType.values()) {
			result.add(type.getLayerId());
		}
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			result.add(type.getLayerId());
		}
		for (GtnhEndStructureType type : GtnhEndStructureType.values()) {
			if (!result.contains(type.getLayerId())) {
				result.add(type.getLayerId());
			}
		}
		return List.copyOf(result);
	}

	private World create(
			MinecraftInterface minecraftInterface,
			RecognisedVersion recognisedVersion,
			MovablePlayerList movablePlayerList,
			VersionFeatures versionFeatures,
			WorldSpawnOracle worldSpawnOracle) throws MinecraftInterfaceException {

		GtnhRoguelikeDungeonProducers gtnhRoguelikeDungeonProducers =
				minecraftInterface instanceof amidst.gtnh.worker.GtnhMinecraftInterface gtnh
						? new GtnhRoguelikeDungeonProducers(
								gtnh,
								versionFeatures.get(FeatureKey.WORLD_OPTIONS).getWorldSeed().getLong(),
                                worldSpawnOracle)
						: GtnhRoguelikeDungeonProducers.empty();

		return new World(
				versionFeatures.get(FeatureKey.WORLD_OPTIONS),
				movablePlayerList,
				recognisedVersion,
				versionFeatures.get(FeatureKey.BIOME_LIST),
				versionFeatures.get(FeatureKey.ENABLED_LAYERS),
				versionFeatures.get(FeatureKey.OVERWORLD_BIOME_DATA_ORACLE),
				versionFeatures.get(FeatureKey.NETHER_BIOME_DATA_ORACLE),
				versionFeatures.get(FeatureKey.END_BIOME_DATA_ORACLE),
				versionFeatures.get(FeatureKey.MOON_BIOME_DATA_ORACLE),
				versionFeatures.get(FeatureKey.TWILIGHT_FOREST_BIOME_DATA_ORACLE),
				versionFeatures.get(FeatureKey.SPACE_BIOME_DATA_ORACLES),
				versionFeatures.get(FeatureKey.END_ISLAND_ORACLE),
				versionFeatures.get(FeatureKey.SLIME_CHUNK_ORACLE),
				new SpawnProducer(worldSpawnOracle),
				versionFeatures.get(FeatureKey.STRONGHOLD_PRODUCER),
				new PlayerProducer(movablePlayerList),
				new MultiProducer<>(
						versionFeatures.get(FeatureKey.VILLAGE_PRODUCER),
						versionFeatures.get(FeatureKey.PILLAGER_OUTPOST_PRODUCER)
				),
				new MultiProducer<>(
						versionFeatures.get(FeatureKey.DESERT_TEMPLE_PRODUCER),
						versionFeatures.get(FeatureKey.IGLOO_PRODUCER),
						versionFeatures.get(FeatureKey.JUNGLE_TEMPLE_PRODUCER),
						versionFeatures.get(FeatureKey.WITCH_HUT_PRODUCER)
				),
				versionFeatures.get(FeatureKey.MINESHAFT_PRODUCER),
				versionFeatures.get(FeatureKey.OCEAN_MONUMENT_PRODUCER),
				versionFeatures.get(FeatureKey.WOODLAND_MANSION_PRODUCER),
				new MultiProducer<>(
						versionFeatures.get(FeatureKey.OCEAN_RUINS_PRODUCER),
						versionFeatures.get(FeatureKey.SHIPWRECK_PRODUCER),
						versionFeatures.get(FeatureKey.BURIED_TREASURE_PRODUCER)
				),
				new MultiProducer<>(
						versionFeatures.get(FeatureKey.NETHER_FORTRESS_PRODUCER),
						versionFeatures.get(FeatureKey.BASTION_REMNANT_PRODUCER)
				),
				versionFeatures.get(FeatureKey.END_CITY_PRODUCER),
				gtnhRoguelikeDungeonProducers);
	}
}
