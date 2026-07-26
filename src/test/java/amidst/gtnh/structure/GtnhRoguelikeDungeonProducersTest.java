package amidst.gtnh.structure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import amidst.fragment.Fragment;
import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.gtnh.worker.GtnhBiomeSource;
import amidst.gtnh.worker.GtnhMinecraftInterface;
import amidst.gtnh.worker.GtnhWorkerInfo;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.icon.WorldIcon;

public class GtnhRoguelikeDungeonProducersTest {

	@Test
	public void filtersTypesLabelsIconsAndSharesFragmentQuery() throws Exception {
		RecordingSource source = new RecordingSource();
		GtnhMinecraftInterface minecraftInterface = new GtnhMinecraftInterface(source);
		GtnhRoguelikeDungeonProducers producers =
				new GtnhRoguelikeDungeonProducers(minecraftInterface, 987654321L);
		CoordinatesInWorld corner = CoordinatesInWorld.from(-512, 1024);

		List<WorldIcon> desert = producers.get(GtnhRoguelikeDungeonType.DESERT).getAt(corner, null);
		List<WorldIcon> swamp = producers.get(GtnhRoguelikeDungeonType.SWAMP).getAt(corner, null);
		List<WorldIcon> villages = producers.get(GtnhOverworldStructureType.VILLAGE).getAt(corner, null);
		List<WorldIcon> lootGames =
				producers.get(GtnhOverworldStructureType.LOOTGAMES_DUNGEON).getAt(corner, null);
		List<WorldIcon> slimeIslands =
				producers.get(GtnhOverworldStructureType.TINKERS_SLIME_ISLAND).getAt(corner, null);
		List<WorldIcon> auraNodes =
				producers.get(GtnhOverworldStructureType.THAUMCRAFT_AURA_NODE).getAt(corner, null);
		List<WorldIcon> altars =
				producers.get(GtnhOverworldStructureType.THAUMCRAFT_ELDRITCH_ALTAR).getAt(corner, null);
		List<WorldIcon> meteorites =
				producers.get(GtnhOverworldStructureType.AE2_METEORITE).getAt(corner, null);

		assertEquals(1, source.queryCount);
		assertEquals(987654321L, source.seed);
		assertEquals(-512, source.x);
		assertEquals(1024, source.z);
		assertEquals(Fragment.SIZE, source.width);
		assertEquals(Fragment.SIZE, source.height);

		assertEquals(1, desert.size());
		assertEquals(
				"Possible Roguelike Dungeon — Desert (Pyramid entrance)",
				desert.get(0).getName());
		assertEquals(CoordinatesInWorld.from(-450, 1090), desert.get(0).getCoordinates());
		assertTrue(desert.get(0).getImage() != null);

		assertEquals(1, swamp.size());
		assertEquals(
				"Possible Roguelike Dungeon — Swamp (Witch-house entrance)",
				swamp.get(0).getName());

		assertEquals(1, villages.size());
		assertEquals("Possible GTNH Village", villages.get(0).getName());

		assertEquals(1, lootGames.size());
		assertEquals("Possible LootGames Game Dungeon", lootGames.get(0).getName());
		assertTrue(lootGames.get(0).getImage() != null);

		assertEquals(1, slimeIslands.size());
		assertEquals(
				"Tinkers' Blue Slime Island (approximate center)",
				slimeIslands.get(0).getName());
		assertTrue(slimeIslands.get(0).getImage() != null);

		assertEquals(1, auraNodes.size());
		assertEquals("Possible Thaumcraft Aura Node", auraNodes.get(0).getName());
		assertEquals(1, altars.size());
		assertEquals("Possible Thaumcraft Eldritch Altar", altars.get(0).getName());
		assertEquals(1, meteorites.size());
		assertEquals("Possible AE2 Meteorite", meteorites.get(0).getName());
		assertEquals(CoordinatesInWorld.from(-180, 1280), meteorites.get(0).getCoordinates());
		assertTrue(meteorites.get(0).getImage() != null);

		List<WorldIcon> vanillaDungeons =
				producers.get(GtnhOverworldStructureType.VANILLA_SPAWNER_DUNGEON).getAt(corner, null);
		assertEquals(1, source.dungeonQueryCount);
		assertEquals(1, vanillaDungeons.size());
		assertEquals(
				"Possible Vanilla Spawner Dungeon (Y 34)",
				vanillaDungeons.get(0).getName());
		assertTrue(vanillaDungeons.get(0).getImage() != null);
	}

	@Test
	public void queriesAndEmitsNetherStructuresInNetherDimension() throws Exception {
		RecordingSource source = new RecordingSource();
		GtnhRoguelikeDungeonProducers producers =
				new GtnhRoguelikeDungeonProducers(
						new GtnhMinecraftInterface(source),
						24680L);
		CoordinatesInWorld corner = CoordinatesInWorld.from(0, 0);

		List<WorldIcon> islands = producers
				.get(GtnhNetherStructureType.TINKERS_NETHER_SLIME_ISLAND)
				.getAt(corner, null);
		List<WorldIcon> spires = producers
				.get(GtnhNetherStructureType.AUTOMAGY_NETHER_SPIRE)
				.getAt(corner, null);

		assertEquals(1, source.netherQueryCount);
		assertEquals(Dimension.NETHER.getId(), source.dimensionId);
		assertEquals(1, islands.size());
		assertEquals(
				"Possible Tinkers' Nether Slime Island (approximate center)",
				islands.get(0).getName());
		assertEquals(Dimension.OVERWORLD, islands.get(0).getDimension());
		assertEquals(1, spires.size());
		assertEquals("Possible Automagy Nether Spire", spires.get(0).getName());
		assertEquals(Dimension.OVERWORLD, spires.get(0).getDimension());
	}

	@Test
	public void twilightLandmarkTypesCanBeLoadedIndependently() throws Exception {
		RecordingSource source = new RecordingSource();
		GtnhRoguelikeDungeonProducers producers =
				new GtnhRoguelikeDungeonProducers(
						new GtnhMinecraftInterface(source),
						13579L);
		CoordinatesInWorld corner = CoordinatesInWorld.from(0, 0);

		List<WorldIcon> naga = producers
				.get(GtnhTwilightForestFeatureType.NAGA_COURTYARD)
				.getAt(corner, null);
		List<WorldIcon> lich = producers
				.get(GtnhTwilightForestFeatureType.LICH_TOWER)
				.getAt(corner, null);
		List<WorldIcon> hydra = producers
				.get(GtnhTwilightForestFeatureType.HYDRA_LAIR)
				.getAt(corner, null);

		assertEquals(1, source.twilightQueryCount);
		assertEquals(1, naga.size());
		assertEquals("Naga Courtyard", naga.get(0).getName());
		assertEquals(1, lich.size());
		assertEquals("Lich Tower", lich.get(0).getName());
		assertEquals(0, hydra.size());
	}

	@Test
	public void moonStructuresShareOneQueryAndUseTheMoonDimension() throws Exception {
		RecordingSource source = new RecordingSource();
		GtnhRoguelikeDungeonProducers producers =
				new GtnhRoguelikeDungeonProducers(
						new GtnhMinecraftInterface(source),
						97531L);
		CoordinatesInWorld corner = CoordinatesInWorld.from(0, 0);

		List<WorldIcon> dungeons = producers
				.get(GtnhMoonStructureType.MOON_DUNGEON)
				.getAt(corner, null);
		List<WorldIcon> villages = producers
				.get(GtnhMoonStructureType.MOON_VILLAGE)
				.getAt(corner, null);

		assertEquals(1, source.moonQueryCount);
		assertEquals(-28, source.dimensionId);
		assertEquals(1, dungeons.size());
		assertEquals("Moon Dungeon", dungeons.get(0).getName());
		assertEquals(Dimension.MOON, dungeons.get(0).getDimension());
		assertEquals(
				"galacticraft_moon_dungeon_brick.png",
				GtnhMoonStructureType.MOON_DUNGEON.getMenuIcon());
		assertEquals(1, villages.size());
		assertEquals("Moon Village", villages.get(0).getName());
		assertEquals(Dimension.MOON, villages.get(0).getDimension());
		assertEquals(
				"galacticraft_alien_villager.png",
				GtnhMoonStructureType.MOON_VILLAGE.getMenuIcon());
	}

	@Test
	public void spaceStructuresShareTheDimensionCacheAndKeepDedicatedIcons() throws Exception {
		RecordingSource source = new RecordingSource();
		GtnhRoguelikeDungeonProducers producers =
				new GtnhRoguelikeDungeonProducers(
						new GtnhMinecraftInterface(source),
						86420L);
		CoordinatesInWorld corner = CoordinatesInWorld.from(0, 0);

		List<WorldIcon> caverns = producers
				.get(GtnhSpaceStructureType.MARS_CAVERN)
				.getAt(corner, null);
		List<WorldIcon> dungeons = producers
				.get(GtnhSpaceStructureType.MARS_DUNGEON)
				.getAt(corner, null);

		assertEquals(1, source.spaceQueryCount);
		assertEquals(Dimension.MARS.getId(), source.dimensionId);
		assertEquals(1, caverns.size());
		assertEquals("Mars Cavern", caverns.get(0).getName());
		assertEquals(Dimension.MARS, caverns.get(0).getDimension());
		assertEquals(
				"galacticraft_mars_cavern_vine.png",
				GtnhSpaceStructureType.MARS_CAVERN.getMenuIcon());
		assertEquals(1, dungeons.size());
		assertEquals("Mars Dungeon", dungeons.get(0).getName());
		assertEquals(Dimension.MARS, dungeons.get(0).getDimension());
	}

	@Test
	public void emitsDedicatedWorldSpawnOnlyInContainingFragment() throws Exception {
		RecordingSource source = new RecordingSource();
		CoordinatesInWorld spawn = CoordinatesInWorld.from(530, -15);
		GtnhRoguelikeDungeonProducers producers = new GtnhRoguelikeDungeonProducers(
				new GtnhMinecraftInterface(source),
				123L,
				spawn);

		List<WorldIcon> containing = producers
				.get(GtnhOverworldStructureType.WORLD_SPAWN)
				.getAt(CoordinatesInWorld.from(512, -512), null);
		List<WorldIcon> adjacent = producers
				.get(GtnhOverworldStructureType.WORLD_SPAWN)
				.getAt(CoordinatesInWorld.from(0, -512), null);

		assertEquals(1, containing.size());
		assertEquals("GTNH World Spawn", containing.get(0).getName());
		assertEquals(spawn, containing.get(0).getCoordinates());
		assertEquals(0, adjacent.size());
		assertEquals(0, source.queryCount);
	}

	private static final class RecordingSource implements GtnhBiomeSource {
		private int queryCount;
		private long seed;
		private int x;
		private int z;
		private int width;
		private int height;
		private int dungeonQueryCount;
		private int netherQueryCount;
		private int twilightQueryCount;
		private int moonQueryCount;
		private int spaceQueryCount;
		private int dimensionId;

		@Override
		public GtnhWorkerInfo getWorkerInfo() {
			return new GtnhWorkerInfo(
					PROTOCOL_VERSION,
					0L,
					"RWG",
					"net.minecraft.world.WorldProviderSurface",
					"rwg.world.ChunkManagerRealistic",
					List.of(new GtnhBiomeDescriptor(
							1,
							"Plains",
							0x77AA55,
							0.8F,
							0.4F,
							0.1F,
							0.2F)));
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
			return new int[width * height];
		}

		@Override
		public List<GtnhStructureDescriptor> sampleStructures(
				long seed,
				int dimensionId,
				int blockX,
				int blockZ,
				int width,
				int height) {
			queryCount++;
			this.dimensionId = dimensionId;
			if (dimensionId == Dimension.NETHER.getId()) {
				netherQueryCount++;
				return List.of(
						new GtnhStructureDescriptor(
								"TINKERS_NETHER_SLIME_ISLAND",
								"",
								64,
								80,
								"EXPECTED"),
						new GtnhStructureDescriptor(
								"AUTOMAGY_NETHER_SPIRE",
								"",
								96,
								112,
								"POSSIBLE"));
			}
			if (dimensionId == Dimension.TWILIGHT_FOREST.getId()) {
				twilightQueryCount++;
				return List.of(
						new GtnhStructureDescriptor(
								"TWILIGHT_FEATURE",
								"NAGA_COURTYARD",
								72,
								88,
								"EXACT"),
						new GtnhStructureDescriptor(
								"TWILIGHT_FEATURE",
								"LICH_TOWER",
								328,
								344,
								"EXACT"));
			}
			if (dimensionId == Dimension.MOON.getId()) {
				moonQueryCount++;
				return List.of(
						new GtnhStructureDescriptor(
								"MOON_DUNGEON",
								"",
								120,
								136,
								"EXPECTED"),
						new GtnhStructureDescriptor(
								"MOON_VILLAGE",
								"",
								248,
								264,
							"EXPECTED"));
			}
			if (dimensionId == Dimension.MARS.getId()) {
				spaceQueryCount++;
				return List.of(
						new GtnhStructureDescriptor(
								"MARS_CAVERN",
								"",
								48,
								64,
								"EXPECTED"),
						new GtnhStructureDescriptor(
								"MARS_DUNGEON",
								"",
								144,
								160,
								"EXPECTED"));
			}
			this.seed = seed;
			this.x = blockX;
			this.z = blockZ;
			this.width = width;
			this.height = height;
			return List.of(
					new GtnhStructureDescriptor(
							"ROGUELIKE_DUNGEON",
							"DESERT",
							-450,
							1090,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"ROGUELIKE_DUNGEON",
							"SWAMP",
							-200,
							1300,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"OTHER",
							"DESERT",
							-400,
							1100,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"VILLAGE",
							"",
							-300,
							1200,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"LOOTGAMES_DUNGEON",
							"",
							-250,
							1210,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"TINKERS_SLIME_ISLAND",
							"",
							-220,
							1240,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"THAUMCRAFT_AURA_NODE",
							"WILD",
							-210,
							1250,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"THAUMCRAFT_ELDRITCH_ALTAR",
							"",
							-205,
							1260,
							"POSSIBLE"),
					new GtnhStructureDescriptor(
							"AE2_METEORITE",
							"SURFACE_BIASED",
							-180,
							1280,
							"POSSIBLE"));
		}

		@Override
		public List<GtnhStructureDescriptor> sampleVanillaDungeons(
				long seed,
				int dimensionId,
				int blockX,
				int blockZ,
				int width,
				int height) {
			dungeonQueryCount++;
			return List.of(new GtnhStructureDescriptor(
					"VANILLA_SPAWNER_DUNGEON",
					"34",
					-190,
					1270,
					"POSSIBLE"));
		}
	}
}
