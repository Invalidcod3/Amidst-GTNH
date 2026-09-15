package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.biome.BiomeColor;

public class GtnhBiomeColorPaletteTest {
	@Test
	public void plutoColorsFollowSurfaceMaterialsAcrossRegistryAndScopedIds() throws Exception {
		String[] names = { "Pluto", "Pluto2", "Pluto3", "Pluto4" };
		String[] textureMeans = { "#A57E61", "#F9F0D6", "#C3B9AF", "#8C5235" };
		// Raw mod colors and cold/snowy tags must not override actual surface colors.
		// Worker scopes dimension-local biome IDs, and modpack configurations can change IDs.
		for (int baseId : new int[] { 40, 220, 4096, 5632 }) {
			var biomes = new java.util.ArrayList<GtnhBiomeDescriptor>();
			for (int i = 0; i < names.length; i++) {
				biomes.add(biome(baseId + i, names[i], 0x00FFFF, "GALAXYSPACE", "COLD", "SNOWY"));
			}
			Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(info(biomes), null);
			for (int i = 0; i < names.length; i++) assertEquals(textureMeans[i], hex(colors.get(baseId + i)));
			assertEquals(4, colors.values().stream().map(GtnhBiomeColorPaletteTest::hex).distinct().count());
		}
	}

	@Test
	public void explicitPlutoColorOverridesStillTakePriority() throws Exception {
		Path file = Files.createTempFile("pluto-biome-colors", ".json");
		try {
			Files.writeString(file, "{\"byName\":{\"Pluto\":\"#112233\"},\"byId\":{\"41\":\"#445566\"}}");
			var colors = GtnhBiomeColorPalette.create(info(List.of(
					biome(40, "Pluto", 0, "GALAXYSPACE"), biome(41, "Pluto2", 0, "GALAXYSPACE"))), file);
			assertEquals("#112233", hex(colors.get(40)));
			assertEquals("#445566", hex(colors.get(41)));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	public void twilightForestUsesTheMagicMapBiomeColorExactly() {
		GtnhBiomeDescriptor biome = biome(
				220,
				"Twilight Stream",
				0x3253B7,
				0.5f,
				"RIVER",
				"TWILIGHT_FOREST");

		assertEquals("#3253B7", hex(GtnhBiomeColorPalette.defaultColor(biome)));
	}

	@Test
	public void moonUsesOneStableLunarColor() {
		GtnhBiomeDescriptor biome = biome(
				102,
				"Moon",
				0,
				0.5f,
				"MOON");

		assertEquals("#8C8C84", hex(GtnhBiomeColorPalette.defaultColor(biome)));
	}

	@Test
	public void usesReadableCategoryColorsInsteadOfRawModColors() throws Exception {
		GtnhWorkerInfo info = info(List.of(
				biome(40, "Deep Ocean", 0xFF00FF, "OCEAN"),
				biome(41, "Rainforest", 0xFF0000),
				biome(42, "Desert Mountains", 0x00FFFF)));

		Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(info, null);

		assertEquals("#70B44E", hex(colors.get(40)));
		assertEquals("#377C4D", hex(colors.get(41)));
		assertEquals("#DFBC75", hex(colors.get(42)));
	}

	@Test
	public void bopNetherBiomesUseDistinctDimensionColors() throws Exception {
		GtnhWorkerInfo info = info(List.of(
				biome(100, "Visceral Heap", 0, "NETHER"),
				biome(101, "Polar Chasm", 0, "NETHER"),
				biome(102, "Corrupted Sands", 0, "NETHER"),
				biome(103, "Boneyard", 0, "NETHER"),
				biome(104, "Undergarden", 0, "NETHER"),
				biome(105, "Phantasmagoric Inferno", 0, "NETHER"),
				biome(8, "Hell", 0, "NETHER")));

		Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(info, null);

		assertEquals(7, colors.values().stream().map(GtnhBiomeColorPaletteTest::hex).distinct().count());
		assertEquals("#9C505C", hex(colors.get(100)));
		assertEquals("#6B2D2D", hex(colors.get(8)));
	}

	@Test
	public void gtnhEndBiomesUseDedicatedHighContrastColors() throws Exception {
		GtnhWorkerInfo info = info(List.of(
				biome(3000, "GTNH End Void", 0, "END"),
				biome(3001, "GTNH Central End", 0, "END"),
				biome(3002, "HEE Infested Forest", 0, "END"),
				biome(3003, "HEE Burning Mountains", 0, "END"),
				biome(3004, "HEE Enchanted Island", 0, "END")));

		Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(info, null);

		assertEquals("#17131F", hex(colors.get(3000)));
		assertEquals("#C9C29B", hex(colors.get(3001)));
		assertEquals("#526B48", hex(colors.get(3002)));
		assertEquals("#9B4B32", hex(colors.get(3003)));
		assertEquals("#6575A8", hex(colors.get(3004)));
	}

	@Test
	public void barnardaCBiomesUseDedicatedHighContrastPurplePalette() throws Exception {
		GtnhWorkerInfo info = info(List.of(
				biome(224, "BarnardaCShores", 0, "GALAXYSPACE"),
				biome(225, "BarnardaCOceans", 0, "GALAXYSPACE"),
				biome(226, "BarnardaCFlowers", 0, "GALAXYSPACE"),
				biome(227, "BarnardaCLowPlains", 0, "GALAXYSPACE"),
				biome(228, "BarnardaCHills", 0, "GALAXYSPACE")));

		Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(info, null);

		assertEquals("#32105F", hex(colors.get(224)));
		assertEquals("#4B4FB5", hex(colors.get(225)));
		assertEquals("#D02A9F", hex(colors.get(226)));
		assertEquals("#7B3FA1", hex(colors.get(227)));
		assertEquals("#777982", hex(colors.get(228)));
		assertEquals(5, colors.values().stream().map(GtnhBiomeColorPaletteTest::hex).distinct().count());
	}

	@Test
	public void oceanTagAndNameNeverTurnABiomeBlue() {
		GtnhBiomeDescriptor taggedOcean = biome(42, "Deep Ocean", 0, "OCEAN");
		GtnhBiomeDescriptor untagged = biome(42, "Deep Ocean", 0);

		assertEquals(
				hex(GtnhBiomeColorPalette.defaultColor(untagged)),
				hex(GtnhBiomeColorPalette.defaultColor(taggedOcean)));
		assertFalse(isVisiblyBlue(GtnhBiomeColorPalette.defaultColor(taggedOcean)));
	}

	@Test
	public void oceanTagAloneDoesNotTurnLandBlue() {
		GtnhBiomeDescriptor mistaggedLand = biome(70, "Ocean Tagged Highlands", 0, "OCEAN", "MOUNTAIN");
		GtnhBiomeDescriptor ordinaryLand = biome(70, "Ordinary Highlands", 0, "MOUNTAIN");

		assertEquals(
				hex(GtnhBiomeColorPalette.defaultColor(ordinaryLand)),
				hex(GtnhBiomeColorPalette.defaultColor(mistaggedLand)));
	}

	@Test
	public void oceanBiomesUseTheirSecondaryLandTags() {
		assertSameDefaultColor(
				biome(71, "Cold Ocean", 0, "OCEAN", "BEACH", "FOREST"),
				biome(71, "Cold Ocean", 0, "FOREST"));
		assertSameDefaultColor(
				biome(72, "Hot Ocean", 0, "OCEAN", "BEACH", "SANDY"),
				biome(72, "Hot Ocean", 0, "SANDY"));
		assertSameDefaultColor(
				biome(73, "Wet Ocean", 0, "OCEAN", "BEACH", "JUNGLE"),
				biome(73, "Wet Ocean", 0, "JUNGLE"));
		assertSameDefaultColor(
				biome(74, "Ice Ocean", 0, "OCEAN", "BEACH", "SNOWY"),
				biome(74, "Ice Ocean", 0, "SNOWY"));
	}

	@Test
	public void riverColorsFollowRwgTemperatureAndTags() throws Exception {
		GtnhWorkerInfo info = info(List.of(
				biome(200, "Ice River", 0, 0.0f, "RIVER", "COLD", "SNOWY"),
				biome(201, "Cold River", 0, 0.5f, "RIVER", "COLD"),
				biome(202, "Temperate River", 0, 0.8f, "RIVER", "COLD"),
				biome(203, "Hot River", 0, 0.8f, "RIVER", "HOT"),
				biome(204, "Wet River", 0, 0.9f, "RIVER", "HOT", "WET"),
				biome(205, "River Oasis", 0, 0.9f, "RIVER", "HOT", "WET")));

		Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(info, null);

		assertEquals("#B9DDEB", hex(colors.get(200)));
		assertEquals("#84BCD8", hex(colors.get(201)));
		assertEquals("#599CC0", hex(colors.get(202)));
		assertEquals("#4B96B6", hex(colors.get(203)));
		assertEquals("#4999B2", hex(colors.get(204)));
		assertEquals("#4090A9", hex(colors.get(205)));
	}

	@Test
	public void waterNamesDoNotTurnUntaggedBiomesBlue() {
		GtnhBiomeDescriptor namedLikeWater = biome(40, "Deep Ocean River", 0);
		GtnhBiomeDescriptor ordinary = biome(40, "Ordinary Basin", 0);

		assertEquals(
				hex(GtnhBiomeColorPalette.defaultColor(ordinary)),
				hex(GtnhBiomeColorPalette.defaultColor(namedLikeWater)));
	}

	@Test
	public void nonWaterDefaultPaletteAvoidsBlueHues() {
		List<GtnhBiomeDescriptor> landBiomes = List.of(
				biome(50, "Snowy Forest", 0, "SNOWY", "FOREST"),
				biome(51, "Beach", 0, "BEACH"),
				biome(52, "Mesa", 0, "MESA"),
				biome(53, "Desert", 0, "SANDY"),
				biome(54, "Wetland", 0, "SWAMP"),
				biome(55, "Rainforest", 0, "JUNGLE"),
				biome(56, "Mushroom Island", 0, "MUSHROOM"),
				biome(57, "Volcanic Plains", 0),
				biome(58, "Wasteland", 0, "WASTELAND"),
				biome(59, "Alps", 0, "MOUNTAIN"),
				biome(60, "Forest", 0, "FOREST"),
				biome(61, "Meadow", 0, "PLAINS"),
				biome(62, "Ordinary Basin", 0));

		for (GtnhBiomeDescriptor biome : landBiomes) {
			BiomeColor color = GtnhBiomeColorPalette.defaultColor(biome);
			assertFalse(biome.name() + " unexpectedly used a blue default: " + hex(color), isVisiblyBlue(color));
		}

		assertEquals("#D6D4CB", hex(GtnhBiomeColorPalette.defaultColor(landBiomes.get(0))));
	}

	@Test
	public void appliesNameOverridesThenMoreSpecificIdOverrides() throws Exception {
		Path file = Files.createTempFile("gtnh-biome-colors", ".json");
		try {
			Files.writeString(
					file,
					"""
					{
					  "byName": { "Alps": "#010203" },
					  "byId": { "40": "#AABBCC" }
					}
					""");
			Map<Integer, BiomeColor> colors = GtnhBiomeColorPalette.create(
					info(List.of(biome(40, "Alps", 0))),
					file);

			assertEquals("#AABBCC", hex(colors.get(40)));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	public void rejectsMalformedOverrideColors() throws Exception {
		Path file = Files.createTempFile("gtnh-biome-colors", ".json");
		try {
			Files.writeString(file, "{ \"byId\": { \"40\": \"green\" } }");

			MinecraftInterfaceException error = assertThrows(
					MinecraftInterfaceException.class,
					() -> GtnhBiomeColorPalette.create(
							info(List.of(biome(40, "Alps", 0))),
							file));

			assertEquals("GTNH color for biome id 40 must use #RRGGBB format", error.getMessage());
		} finally {
			Files.deleteIfExists(file);
		}
	}

	private static GtnhWorkerInfo info(List<GtnhBiomeDescriptor> biomes) {
		return new GtnhWorkerInfo(
				GtnhBiomeSource.PROTOCOL_VERSION,
				123L,
				"RWG",
				"net.minecraft.world.WorldProviderSurface",
				"rwg.world.ChunkManagerRealistic",
				biomes);
	}

	private static GtnhBiomeDescriptor biome(int id, String name, int mapColor) {
		return biome(id, name, mapColor, new String[0]);
	}

	private static GtnhBiomeDescriptor biome(int id, String name, int mapColor, String... tags) {
		return biome(id, name, mapColor, 0.6f, tags);
	}

	private static GtnhBiomeDescriptor biome(
			int id,
			String name,
			int mapColor,
			float temperature,
			String... tags) {
		return new GtnhBiomeDescriptor(
				id,
				name,
				mapColor,
				temperature,
				0.7f,
				0.1f,
				0.2f,
				List.of(tags));
	}

	private static void assertSameDefaultColor(
			GtnhBiomeDescriptor first,
			GtnhBiomeDescriptor second) {
		assertEquals(
				hex(GtnhBiomeColorPalette.defaultColor(second)),
				hex(GtnhBiomeColorPalette.defaultColor(first)));
	}

	private static boolean isVisiblyBlue(BiomeColor color) {
		float[] hsb = Color.RGBtoHSB(color.getR(), color.getG(), color.getB(), null);
		return hsb[1] >= 0.12f && hsb[0] >= 0.48f && hsb[0] <= 0.72f;
	}

	private static String hex(BiomeColor color) {
		return String.format("#%02X%02X%02X", color.getR(), color.getG(), color.getB());
	}
}
