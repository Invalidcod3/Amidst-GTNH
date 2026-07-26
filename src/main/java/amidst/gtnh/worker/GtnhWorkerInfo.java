package amidst.gtnh.worker;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import amidst.documentation.Immutable;
import amidst.logging.AmidstLogger;

@Immutable
public record GtnhWorkerInfo(
		int protocol,
		long seed,
		String worldType,
		String providerClass,
		String chunkManagerClass,
		int twilightForestDimensionId,
		int moonDimensionId,
		int marsDimensionId,
		int asteroidsDimensionId,
		int ceresDimensionId,
		int ioDimensionId,
		int enceladusDimensionId,
		int proteusDimensionId,
		int plutoDimensionId,
		int mehenBeltDimensionId,
		int ross128bDimensionId,
		List<GtnhBiomeDescriptor> biomes) {

	public GtnhWorkerInfo(
			int protocol,
			long seed,
			String worldType,
			String providerClass,
			String chunkManagerClass,
			int twilightForestDimensionId,
			int moonDimensionId,
			List<GtnhBiomeDescriptor> biomes) {
		this(
				protocol,
				seed,
				worldType,
				providerClass,
				chunkManagerClass,
				twilightForestDimensionId,
				moonDimensionId,
				-29,
				-30,
				-1007,
				-1013,
				-1016,
				-1019,
				-1008,
				25,
				64,
				biomes);
	}

	public GtnhWorkerInfo(
			int protocol,
			long seed,
			String worldType,
			String providerClass,
			String chunkManagerClass,
			int twilightForestDimensionId,
			List<GtnhBiomeDescriptor> biomes) {
		this(
				protocol,
				seed,
				worldType,
				providerClass,
				chunkManagerClass,
				twilightForestDimensionId,
				-28,
				-29,
				-30,
				-1007,
				-1013,
				-1016,
				-1019,
				-1008,
				25,
				64,
				biomes);
	}

	public GtnhWorkerInfo(
			int protocol,
			long seed,
			String worldType,
			String providerClass,
			String chunkManagerClass,
			List<GtnhBiomeDescriptor> biomes) {
		this(
				protocol,
				seed,
				worldType,
				providerClass,
				chunkManagerClass,
				7,
				-28,
				-29,
				-30,
				-1007,
				-1013,
				-1016,
				-1019,
				-1008,
				25,
				64,
				biomes);
	}

	public GtnhWorkerInfo {
		if (protocol != GtnhBiomeSource.PROTOCOL_VERSION) {
			throw new IllegalArgumentException("unsupported GTNH worker protocol: " + protocol);
		}
		if (worldType == null || worldType.isBlank()) {
			throw new IllegalArgumentException("world type must not be blank");
		}
		if (providerClass == null || providerClass.isBlank()) {
			throw new IllegalArgumentException("provider class must not be blank");
		}
		if (chunkManagerClass == null || chunkManagerClass.isBlank()) {
			throw new IllegalArgumentException("chunk manager class must not be blank");
		}
		biomes = List.copyOf(biomes);
		if (biomes.isEmpty()) {
			throw new IllegalArgumentException("worker returned an empty biome registry");
		}
		Map<Integer, GtnhBiomeDescriptor> byId = new LinkedHashMap<>();
		for (GtnhBiomeDescriptor biome : biomes) {
			GtnhBiomeDescriptor previous = byId.putIfAbsent(biome.id(), biome);
			if (previous != null && !previous.equals(biome)) {
				AmidstLogger.warn(
						"GTNH worker returned duplicate biome id {} ('{}' and '{}'); using '{}'",
						biome.id(),
						previous.name(),
						biome.name(),
						previous.name());
			}
		}
		biomes = List.copyOf(byId.values());
	}
}
