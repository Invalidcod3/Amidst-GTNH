package amidst.gtnh.worker;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.WorldSeed;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

public class GtnhMinecraftInterfaceTest {

	@Test
	public void waypointImportUsesRuntimeIdsAndPreservesNativeCoordinates() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		source.catalog = java.util.Arrays.stream(Dimension.values()).map(d -> {
			var info = new amidst.gtnh.prospecting.ProspectingData.DimensionInfo();
			info.key = d.prospectingKey(); info.id = 5000 + d.ordinal();
			return info;
		}).toList();
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		var waypoint = new amidst.gtnh.export.GtnhWaypoint("Test", -137, 39, 259, 12, 34, 56);
		for (Dimension dimension : Dimension.values()) {
			assertEquals(1, minecraft.importJourneyMapWaypoints(dimension, List.of(waypoint)));
			assertEquals(5000 + dimension.ordinal(), source.lastDimension);
			assertEquals(List.of(waypoint), source.lastWaypoints);
		}
	}

	@Test
	public void waypointImportUsesHelloIdsAndRejectsUnresolvedPreferenceIds() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		var waypoints = List.of(new amidst.gtnh.export.GtnhWaypoint("Test", -137, 64, 259, 12, 34, 56));
		minecraft.importJourneyMapWaypoints(Dimension.NETHER, waypoints);
		assertEquals(-1, source.lastDimension);
		assertEquals(waypoints, source.lastWaypoints);
		minecraft.importJourneyMapWaypoints(Dimension.MOON, waypoints);
		assertEquals(-29, source.lastDimension);
		minecraft.importJourneyMapWaypoints(Dimension.BARNARDA_C, waypoints);
		assertEquals(-1222, source.lastDimension);
		assertThrows(MinecraftInterfaceException.class, () -> minecraft.importJourneyMapWaypoints(Dimension.VENUS, waypoints));
	}

	@Test
	public void quarterResolutionUsesFourBlockSampling() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		int[] result = accessor.getBiomeData(
				Dimension.OVERWORLD,
				-2,
				3,
				2,
				2,
				true,
				data -> data.clone());

		assertArrayEquals(new int[] { 40, 41, 42, 43 }, result);
		assertEquals(-8, source.lastX);
		assertEquals(12, source.lastZ);
		assertEquals(4, source.lastStep);
		assertEquals(0, source.lastDimension);
	}

	@Test
	public void fullResolutionUsesOneBlockSampling() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		accessor.getBiomeData(
				Dimension.OVERWORLD,
				-2,
				3,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(-2, source.lastX);
		assertEquals(3, source.lastZ);
		assertEquals(1, source.lastStep);
	}

	@Test
	public void acceptsAndForwardsSuccessiveArbitrarySeeds() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor firstAccessor = minecraft.createWorldAccessor(options(456L));

		firstAccessor.getBiomeData(
				Dimension.OVERWORLD,
				0,
				0,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(456L, source.lastSeed);

		MinecraftInterface.WorldAccessor secondAccessor = minecraft.createWorldAccessor(options(-789L));
		secondAccessor.getBiomeData(
				Dimension.OVERWORLD,
				0,
				0,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(-789L, source.lastSeed);
	}

	@Test
	public void exposesWorkerPredictedSpawnForArbitrarySeed() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);

		assertEquals(CoordinatesInWorld.from(37, -91), minecraft.getWorldSpawn(456L));
		assertEquals(456L, source.lastSeed);
		assertEquals(0, source.lastDimension);
	}

	@Test
	public void rejectsNonRwgWorldsInPhaseOne() {
		assertThrows(
				MinecraftInterfaceException.class,
				() -> new GtnhMinecraftInterface(new RecordingSource(123L, "default")));
	}

	@Test
	public void exposesAndForwardsTheNetherDimension() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		int biome = accessor.getBiomeData(
				Dimension.NETHER,
				-7,
				11,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(40, biome);
		assertEquals(-1, source.lastDimension);
		assertEquals(-7, source.lastX);
		assertEquals(11, source.lastZ);
		assertEquals(1, source.lastStep);
		assertEquals(
				java.util.Set.of(
						Dimension.OVERWORLD,
						Dimension.NETHER,
						Dimension.END,
						Dimension.MOON,
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
						Dimension.HORUS,
						Dimension.TWILIGHT_FOREST),
				accessor.supportedDimensions());
	}

	@Test
	public void exposesAndForwardsTheGtnhEndDimension() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		int biome = accessor.getBiomeData(
				Dimension.END,
				25,
				-31,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(40, biome);
		assertEquals(1, source.lastDimension);
		assertEquals(25, source.lastX);
		assertEquals(-31, source.lastZ);
	}

	@Test
	public void exposesAndForwardsTheTwilightForestDimension() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		int biome = accessor.getBiomeData(
				Dimension.TWILIGHT_FOREST,
				19,
				-23,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(40, biome);
		assertEquals(7, source.lastDimension);
		assertEquals(19, source.lastX);
		assertEquals(-23, source.lastZ);
	}

	@Test
	public void exposesAndForwardsTheConfiguredMoonDimension() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		int biome = accessor.getBiomeData(
				Dimension.MOON,
				41,
				-57,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(40, biome);
		assertEquals(-29, source.lastDimension);
		assertEquals(41, source.lastX);
		assertEquals(-57, source.lastZ);
	}

	@Test
	public void exposesAndForwardsConfiguredSpaceDimensions() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		MinecraftInterface.WorldAccessor accessor =
				new GtnhMinecraftInterface(source).createWorldAccessor(options(123L));

		Dimension[] dimensions = {
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
		};
		int[] runtimeIds = {
				-129,
				-130,
				-1107,
				-1113,
				-1116,
				-1119,
				-1108,
				-1125,
				-1164,
				-1222,
				1100,
				122,
				123
		};
		for (int index = 0; index < dimensions.length; index++) {
			accessor.getBiomeData(
					dimensions[index],
					index,
					-index,
					1,
					1,
					false,
					data -> data[0]);
			assertEquals(runtimeIds[index], source.lastDimension);
			assertEquals(dimensions[index].getName(), source.lastDimensionKey);
		}
	}

	@Test
	public void rejectsBiomeIdsMissingFromTheHandshakeRegistry() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		source.firstBiomeId = 99;
		GtnhMinecraftInterface minecraft = new GtnhMinecraftInterface(source);
		MinecraftInterface.WorldAccessor accessor = minecraft.createWorldAccessor(options(123L));

		MinecraftInterfaceException error = assertThrows(
				MinecraftInterfaceException.class,
				() -> accessor.getBiomeData(
						Dimension.OVERWORLD,
						0,
						0,
						1,
						1,
						false,
						data -> data[0]));

		assertEquals(
				"GTNH worker sampled unregistered biome id 99; reconnect after GTNH finishes mod initialization",
				error.getMessage());
	}

	@Test
	public void acceptsDistinctScopedBiomeIdsWhenSwitchingGalaxyDimensions() throws Exception {
		RecordingSource source = new RecordingSource(123L, "RWG");
		source.returnScopedGalaxyBiomeIds = true;
		MinecraftInterface.WorldAccessor accessor =
				new GtnhMinecraftInterface(source).createWorldAccessor(options(123L));

		int moonBiome = accessor.getBiomeData(
				Dimension.MOON,
				0,
				0,
				1,
				1,
				false,
				data -> data[0]);
		int barnardaCBiome = accessor.getBiomeData(
				Dimension.BARNARDA_C,
				0,
				0,
				1,
				1,
				false,
				data -> data[0]);
		int moonBiomeAgain = accessor.getBiomeData(
				Dimension.MOON,
				0,
				0,
				1,
				1,
				false,
				data -> data[0]);

		assertEquals(4136, moonBiome);
		assertEquals(5672, barnardaCBiome);
		assertEquals(4136, moonBiomeAgain);
	}

    @Test public void additionalBiomeCapabilitiesUseRuntimeIdsAndNativeCoordinates() throws Exception {
        RecordingSource source = new RecordingSource(123L, "RWG");
        source.catalog = java.util.Arrays.stream(Dimension.values()).filter(Dimension::isAdditional).map(d -> {
            var info = new amidst.gtnh.prospecting.ProspectingData.DimensionInfo();
            info.key = d.prospectingKey(); info.id = 9000 + d.ordinal(); info.biomes = true;
            return info;
        }).toList();
        var minecraft = new GtnhMinecraftInterface(source);
        var accessor = minecraft.createWorldAccessor(options(-785L));
        assertEquals(Dimension.values().length, accessor.supportedDimensions().size());
        for (Dimension dimension : Dimension.values()) {
            if (!dimension.isAdditional()) continue;
            accessor.getBiomeData(dimension, -33, 65, 1, 1, true, data -> data[0]);
            assertEquals(9000 + dimension.ordinal(), source.lastDimension);
            assertEquals(dimension.prospectingKey(), source.lastDimensionKey);
            assertEquals(-785L, source.lastSeed);
            assertEquals(-132, source.lastX);
            assertEquals(260, source.lastZ);
            assertEquals(4, source.lastStep);
        }
    }

    @Test public void resourceCatalogDoesNotClaimUnavailableBiomeSupport() throws Exception {
        RecordingSource source = new RecordingSource(123L, "RWG");
        var info = new amidst.gtnh.prospecting.ProspectingData.DimensionInfo();
        info.key = "Everglades"; info.id = 73; info.ores = true;
        source.catalog = List.of(info);
        var accessor = new GtnhMinecraftInterface(source).createWorldAccessor(options(123L));
        assertFalse(accessor.supportedDimensions().contains(Dimension.EVERGLADES));
        assertThrows(amidst.mojangapi.minecraftinterface.UnsupportedDimensionException.class,
                () -> accessor.getBiomeData(Dimension.EVERGLADES, 0, 0, 1, 1, false, data -> data[0]));
    }

	private static WorldOptions options(long seed) {
		return new WorldOptions(WorldSeed.fromUserInput(Long.toString(seed)), WorldType.DEFAULT);
	}

	private static final class RecordingSource implements GtnhBiomeSource {
		private List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> catalog = List.of();
		private List<amidst.gtnh.export.GtnhWaypoint> lastWaypoints;
		@Override public List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> prospectingCatalog() { return catalog; }
		@Override public int importJourneyMapWaypoints(int dimensionId, List<amidst.gtnh.export.GtnhWaypoint> waypoints) {
			lastDimension = dimensionId; lastWaypoints = waypoints; return waypoints.size();
		}
		private final GtnhWorkerInfo info;
		private int lastDimension;
		private String lastDimensionKey;
		private long lastSeed;
		private int lastX;
		private int lastZ;
		private int lastStep;
		private int firstBiomeId = 40;
		private boolean returnScopedGalaxyBiomeIds;

		private RecordingSource(long seed, String worldType) {
			info = new GtnhWorkerInfo(
					PROTOCOL_VERSION,
					seed,
					worldType,
					"net.minecraft.world.WorldProviderSurface",
					"rwg.world.ChunkManagerRealistic",
					7,
					-29,
					-129,
					-130,
					-1107,
					-1113,
					-1116,
					-1119,
					-1108,
					-1125,
					-1164,
					-1222,
					1100,
					122,
					123,
					List.of(
							new GtnhBiomeDescriptor(40, "Alps Forest", 0x729A6A, 0.3f, 0.8f, 0.1f, 0.2f),
							new GtnhBiomeDescriptor(41, "Alps", 0xB4C5D5, 0.2f, 0.5f, 1.2f, 0.8f),
							new GtnhBiomeDescriptor(42, "Meadow", 0x88AA66, 0.6f, 0.7f, 0.1f, 0.2f),
							new GtnhBiomeDescriptor(43, "Wetland", 0x557755, 0.7f, 0.9f, -0.1f, 0.1f),
							new GtnhBiomeDescriptor(4136, "Moon", 0x6F6F75, 0.0f, 0.0f, 0.1f, 0.2f),
							new GtnhBiomeDescriptor(
									5672,
									"Barnarda C Shores",
									0x4B1259,
									0.0f,
									0.0f,
									0.1f,
									0.2f)));
		}

		@Override
		public GtnhWorkerInfo getWorkerInfo() {
			return info;
		}

		@Override
		public CoordinatesInWorld sampleSpawn(long seed, int dimensionId) {
			lastSeed = seed;
			lastDimension = dimensionId;
			return CoordinatesInWorld.from(37, -91);
		}

		@Override
		public int[] sampleBiomes(
				long seed,
				int dimensionId,
				int blockX,
				int blockZ,
				int width,
				int height,
				int step) {
			lastSeed = seed;
			lastDimension = dimensionId;
			lastX = blockX;
			lastZ = blockZ;
			lastStep = step;
			int[] result = new int[width * height];
			for (int i = 0; i < result.length; i++) {
				result[i] = firstBiomeId + i;
			}
			return result;
		}

		@Override
		public int[] sampleBiomes(
				long seed,
				int dimensionId,
				String dimensionKey,
				int blockX,
				int blockZ,
				int width,
				int height,
				int step) {
			lastDimensionKey = dimensionKey;
			if (returnScopedGalaxyBiomeIds) {
				if (Dimension.MOON.getName().equals(dimensionKey)) {
					firstBiomeId = 4136;
				} else if (Dimension.BARNARDA_C.getName().equals(dimensionKey)) {
					firstBiomeId = 5672;
				}
			}
			return sampleBiomes(seed, dimensionId, blockX, blockZ, width, height, step);
		}
	}
}
