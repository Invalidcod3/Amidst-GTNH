package amidst.gtnh.worker;

import java.util.List;

import amidst.gtnh.export.GtnhWaypoint;
import amidst.gtnh.structure.GtnhStructureDescriptor;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

public interface GtnhBiomeSource {

    default java.util.List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> prospectingCatalog()
            throws MinecraftInterfaceException { return java.util.List.of(); }

    default amidst.gtnh.prospecting.ProspectingData.Tile prospect(long seed, int dimension, int x, int z,
            int width, int height, String mode) throws MinecraftInterfaceException {
        throw new MinecraftInterfaceException("Prospecting is unavailable");
    }
    default amidst.gtnh.prospecting.ProspectingData.Tile prospectFiltered(long seed, int dimension, int x, int z,
            int width, int height, String mode, amidst.gtnh.prospecting.ProspectingData.QueryFilter filter) throws MinecraftInterfaceException {
        return prospect(seed, dimension, x, z, width, height, mode);
    }
	int PROTOCOL_VERSION = 21;

	GtnhWorkerInfo getWorkerInfo() throws MinecraftInterfaceException;

	default GtnhWorldState getWorldState(long sinceRevision)
			throws MinecraftInterfaceException {
		return new GtnhWorldState(
				Math.max(0L, sinceRevision),
				false,
				new int[0],
				new int[0]);
	}

	int[] sampleBiomes(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height,
			int step) throws MinecraftInterfaceException;

	default int[] sampleBiomes(
			long seed,
			int dimensionId,
			String dimensionKey,
			int blockX,
			int blockZ,
			int width,
			int height,
			int step) throws MinecraftInterfaceException {
		return sampleBiomes(seed, dimensionId, blockX, blockZ, width, height, step);
	}

	default CoordinatesInWorld sampleSpawn(long seed, int dimensionId)
			throws MinecraftInterfaceException {
		return CoordinatesInWorld.origin();
	}

	default List<GtnhStructureDescriptor> sampleStructures(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return List.of();
	}

	default List<GtnhStructureDescriptor> sampleStructures(
			long seed,
			int dimensionId,
			String dimensionKey,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return sampleStructures(seed, dimensionId, blockX, blockZ, width, height);
	}

    default List<GtnhStructureDescriptor> sampleStructureGroup(long seed, int dimensionId, String dimensionKey,
            int x, int z, int width, int height, String group) throws MinecraftInterfaceException {
        return sampleStructures(seed, dimensionId, dimensionKey, x, z, width, height);
    }

    default List<GtnhStructureDescriptor> sampleVanillaDungeons(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return List.of();
	}

	default int importJourneyMapWaypoints(
			int dimensionId,
			List<GtnhWaypoint> waypoints) throws MinecraftInterfaceException {
		throw new MinecraftInterfaceException(
				"this GTNH biome source cannot import JourneyMap waypoints");
	}
}
