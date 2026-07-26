package amidst.gtnh.worker;

import java.util.List;

import amidst.gtnh.structure.GtnhStructureDescriptor;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

public interface GtnhBiomeSource {
	int PROTOCOL_VERSION = 13;

	GtnhWorkerInfo getWorkerInfo() throws MinecraftInterfaceException;

	int[] sampleBiomes(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height,
			int step) throws MinecraftInterfaceException;

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

	default List<GtnhStructureDescriptor> sampleVanillaDungeons(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return List.of();
	}
}
