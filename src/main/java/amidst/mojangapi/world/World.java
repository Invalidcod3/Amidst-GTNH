package amidst.mojangapi.world;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.structure.GtnhRoguelikeDungeonProducers;
import amidst.gtnh.structure.GtnhRoguelikeDungeonType;
import amidst.gtnh.structure.GtnhSpaceStructureType;
import amidst.gtnh.structure.GtnhTwilightForestFeatureType;
import amidst.gtnh.structure.GtnhOverworldStructureType;
import amidst.gtnh.structure.GtnhNetherStructureType;
import amidst.gtnh.structure.GtnhEndStructureType;
import amidst.gtnh.structure.GtnhMoonStructureType;
import amidst.gtnh.structure.GtnhNetherFortressProducer;
import amidst.gtnh.export.GtnhWaypoint;
import amidst.gtnh.worker.GtnhWorldState;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.CachedWorldIconProducer;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;
import amidst.mojangapi.world.oracle.BiomeDataOracle;
import amidst.mojangapi.world.oracle.EndIsland;
import amidst.mojangapi.world.oracle.EndIslandOracle;
import amidst.mojangapi.world.oracle.SlimeChunkOracle;
import amidst.mojangapi.world.player.MovablePlayerList;

@ThreadSafe
public class World {
	private final WorldOptions worldOptions;
	private final MovablePlayerList movablePlayerList;
	private final RecognisedVersion recognisedVersion;
	private final List<Integer> enabledLayers;
	private final BiomeList biomeList;

	private final BiomeDataOracle overworldBiomeDataOracle;
	private final Optional<BiomeDataOracle> netherBiomeDataOracle;
	private final Optional<BiomeDataOracle> endBiomeDataOracle;
	private final Optional<BiomeDataOracle> moonBiomeDataOracle;
	private final Optional<BiomeDataOracle> twilightForestBiomeDataOracle;
	private final Map<Dimension, Optional<BiomeDataOracle>> spaceBiomeDataOracles;
	private final EndIslandOracle endIslandOracle;
	private final SlimeChunkOracle slimeChunkOracle;
	private final CachedWorldIconProducer spawnProducer;
	private final CachedWorldIconProducer strongholdProducer;
	private final CachedWorldIconProducer playerProducer;
	private final WorldIconProducer<Void> villageProducer;
	private final WorldIconProducer<Void> templeProducer;
	private final WorldIconProducer<Void> mineshaftProducer;
	private final WorldIconProducer<Void> oceanMonumentProducer;
	private final WorldIconProducer<Void> woodlandMansionProducer;
	private final WorldIconProducer<Void> oceanFeaturesProducer;
	private final WorldIconProducer<Void> netherFortressProducer;
	private final WorldIconProducer<List<EndIsland>> endCityProducer;
	private final GtnhRoguelikeDungeonProducers gtnhRoguelikeDungeonProducers;
	private final WorldIconProducer<Void> gtnhNetherFortressProducer;

	public World(
			WorldOptions worldOptions,
			MovablePlayerList movablePlayerList,
			RecognisedVersion recognisedVersion,
			BiomeList biomeList,
			List<Integer> enabledLayers,
			BiomeDataOracle overworldBiomeDataOracle,
			Optional<BiomeDataOracle> netherBiomeDataOracle,
			Optional<BiomeDataOracle> endBiomeDataOracle,
			Optional<BiomeDataOracle> moonBiomeDataOracle,
			Optional<BiomeDataOracle> twilightForestBiomeDataOracle,
			Map<Dimension, Optional<BiomeDataOracle>> spaceBiomeDataOracles,
			EndIslandOracle endIslandOracle,
			SlimeChunkOracle slimeChunkOracle,
			CachedWorldIconProducer spawnProducer,
			CachedWorldIconProducer strongholdProducer,
			CachedWorldIconProducer playerProducer,
			WorldIconProducer<Void> villageProducer,
			WorldIconProducer<Void> templeProducer,
			WorldIconProducer<Void> mineshaftProducer,
			WorldIconProducer<Void> oceanMonumentProducer,
			WorldIconProducer<Void> woodlandMansionProducer,
			WorldIconProducer<Void> oceanFeaturesProducer,
			WorldIconProducer<Void> netherFortressProducer,
			WorldIconProducer<List<EndIsland>> endCityProducer,
			GtnhRoguelikeDungeonProducers gtnhRoguelikeDungeonProducers) {
		this.worldOptions = worldOptions;
		this.movablePlayerList = movablePlayerList;
		this.recognisedVersion = recognisedVersion;
		this.biomeList = biomeList;
		this.enabledLayers = enabledLayers;
		this.overworldBiomeDataOracle = overworldBiomeDataOracle;
		this.netherBiomeDataOracle = netherBiomeDataOracle;
		this.endBiomeDataOracle = endBiomeDataOracle;
		this.moonBiomeDataOracle = moonBiomeDataOracle;
		this.twilightForestBiomeDataOracle = twilightForestBiomeDataOracle;
		this.spaceBiomeDataOracles = Map.copyOf(spaceBiomeDataOracles);
		this.endIslandOracle = endIslandOracle;
		this.slimeChunkOracle = slimeChunkOracle;
		this.spawnProducer = spawnProducer;
		this.strongholdProducer = strongholdProducer;
		this.playerProducer = playerProducer;
		this.villageProducer = villageProducer;
		this.templeProducer = templeProducer;
		this.mineshaftProducer = mineshaftProducer;
		this.oceanMonumentProducer = oceanMonumentProducer;
		this.woodlandMansionProducer = woodlandMansionProducer;
		this.oceanFeaturesProducer = oceanFeaturesProducer;
		this.netherFortressProducer = netherFortressProducer;
		this.endCityProducer = endCityProducer;
		this.gtnhRoguelikeDungeonProducers = gtnhRoguelikeDungeonProducers;
		this.gtnhNetherFortressProducer =
				new GtnhNetherFortressProducer(worldOptions.getWorldSeed().getLong());
	}

	public WorldOptions getWorldOptions() {
		return worldOptions;
	}

	public MovablePlayerList getMovablePlayerList() {
		return movablePlayerList;
	}

	public RecognisedVersion getRecognisedVersion() {
		return recognisedVersion;
	}

	public BiomeList getBiomeList() {
		return biomeList;
	}

	public List<Integer> getEnabledLayers() {
		return enabledLayers;
	}

	public BiomeDataOracle getOverworldBiomeDataOracle() {
		return overworldBiomeDataOracle;
	}

	public Optional<BiomeDataOracle> getNetherBiomeDataOracle() {
		return netherBiomeDataOracle;
	}

	public Optional<BiomeDataOracle> getEndBiomeDataOracle() {
		return endBiomeDataOracle;
	}

	public Optional<BiomeDataOracle> getMoonBiomeDataOracle() {
		return moonBiomeDataOracle;
	}

	public Optional<BiomeDataOracle> getTwilightForestBiomeDataOracle() {
		return twilightForestBiomeDataOracle;
	}

	public Optional<BiomeDataOracle> getBiomeDataOracle(Dimension dimension) {
		return switch (dimension) {
			case OVERWORLD -> Optional.of(overworldBiomeDataOracle);
			case NETHER -> netherBiomeDataOracle;
			case END -> endBiomeDataOracle;
			case MOON -> moonBiomeDataOracle;
			case TWILIGHT_FOREST -> twilightForestBiomeDataOracle;
			default -> spaceBiomeDataOracles.getOrDefault(dimension, Optional.empty());
		};
	}

	public Map<Dimension, Optional<BiomeDataOracle>> getSpaceBiomeDataOracles() {
		return spaceBiomeDataOracles;
	}

	public EndIslandOracle getEndIslandOracle() {
		return endIslandOracle;
	}

	public SlimeChunkOracle getSlimeChunkOracle() {
		return slimeChunkOracle;
	}

	public WorldIconProducer<Void> getSpawnProducer() {
		return spawnProducer;
	}

	public WorldIconProducer<Void> getStrongholdProducer() {
		return strongholdProducer;
	}

	public WorldIconProducer<Void> getPlayerProducer() {
		return playerProducer;
	}

	public WorldIconProducer<Void> getVillageProducer() {
		return villageProducer;
	}

	public WorldIconProducer<Void> getTempleProducer() {
		return templeProducer;
	}

	public WorldIconProducer<Void> getMineshaftProducer() {
		return mineshaftProducer;
	}

	public WorldIconProducer<Void> getOceanMonumentProducer() {
		return oceanMonumentProducer;
	}

	public WorldIconProducer<Void> getNetherFortressProducer() {
		return netherFortressProducer;
	}

	public WorldIconProducer<List<EndIsland>> getEndCityProducer() {
		return endCityProducer;
	}

	public WorldIconProducer<Void> getWoodlandMansionProducer() {
		return woodlandMansionProducer;
	}

	public WorldIconProducer<Void> getOceanFeaturesProducer() {
		return oceanFeaturesProducer;
	}

	public WorldIconProducer<Void> getGtnhRoguelikeDungeonProducer(GtnhRoguelikeDungeonType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhOverworldStructureProducer(GtnhOverworldStructureType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhNetherStructureProducer(GtnhNetherStructureType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhEndStructureProducer(GtnhEndStructureType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhMoonStructureProducer(GtnhMoonStructureType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhSpaceStructureProducer(GtnhSpaceStructureType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhTwilightForestFeatureProducer(
			GtnhTwilightForestFeatureType type) {
		return gtnhRoguelikeDungeonProducers.get(type);
	}

	public WorldIconProducer<Void> getGtnhNetherFortressProducer() {
		return gtnhNetherFortressProducer;
	}

	public boolean supportsJourneyMapImport() {
		return gtnhRoguelikeDungeonProducers.supportsJourneyMapImport();
	}

    public java.util.List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> prospectingCatalog() {
        return gtnhRoguelikeDungeonProducers.prospectingCatalog();
    }
    public amidst.gtnh.prospecting.ProspectingData.Tile prospectFiltered(Dimension dimension, int x, int z,
            int width, int height, String mode, amidst.gtnh.prospecting.ProspectingData.QueryFilter filter) throws amidst.mojangapi.minecraftinterface.MinecraftInterfaceException {
        return gtnhRoguelikeDungeonProducers.prospectFiltered(dimension, x, z, width, height, mode, filter);
    }
    public amidst.gtnh.prospecting.ProspectingData.Tile prospect(Dimension dimension, int x, int z,
            int width, int height, String mode) throws MinecraftInterfaceException {
        return gtnhRoguelikeDungeonProducers.prospect(dimension, x, z, width, height, mode);
    }

	public boolean supportsGtnhWorldStateUpdates() {
		return gtnhRoguelikeDungeonProducers.supportsWorldStateUpdates();
	}

	public GtnhWorldState getGtnhWorldState(long sinceRevision)
			throws MinecraftInterfaceException {
		return gtnhRoguelikeDungeonProducers.getWorldState(sinceRevision);
	}

	public int getGtnhDimensionId(Dimension dimension) {
		return gtnhRoguelikeDungeonProducers.getDimensionId(dimension);
	}

	public int importJourneyMapWaypoints(
			Dimension dimension,
			List<GtnhWaypoint> waypoints) throws MinecraftInterfaceException {
		return gtnhRoguelikeDungeonProducers.importJourneyMapWaypoints(
				dimension,
				waypoints);
	}

	public WorldIcon getSpawnWorldIcon() {
		return spawnProducer.getFirstWorldIcon();
	}

	public List<WorldIcon> getStrongholdWorldIcons() {
		return strongholdProducer.getWorldIcons();
	}

	public List<WorldIcon> getPlayerWorldIcons() {
		return playerProducer.getWorldIcons();
	}

	public void reloadPlayerWorldIcons() {
		playerProducer.resetCache();
	}
}
