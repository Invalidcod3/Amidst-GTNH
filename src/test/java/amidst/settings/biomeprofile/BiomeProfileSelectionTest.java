package amidst.settings.biomeprofile;

import static org.junit.Assert.assertEquals;

import java.util.Map;

import org.junit.Test;

import amidst.mojangapi.world.biome.BiomeColor;

public class BiomeProfileSelectionTest {
	@Test
	public void selectedUserProfileOverridesRuntimeColorsAndFallsBackForMissingIds() throws Exception {
		BiomeProfile defaultProfile = new BiomeProfile(
				"default",
				null,
				Map.of(40, new BiomeColorJson(1, 2, 3)));
		BiomeProfileSelection selection = new BiomeProfileSelection(defaultProfile);
		selection.setRuntimeBiomeColors(Map.of(
				40, BiomeColor.from(10, 20, 30),
				41, BiomeColor.from(40, 50, 60)));

		assertColor(10, 20, 30, selection.getBiomeColor(40));

		selection.set(new BiomeProfile(
				"custom",
				null,
				Map.of(40, new BiomeColorJson(100, 110, 120))));

		assertColor(100, 110, 120, selection.getBiomeColor(40));
		assertColor(40, 50, 60, selection.getBiomeColor(41));
	}

	private static void assertColor(int r, int g, int b, BiomeColor actual) {
		assertEquals(r, actual.getR());
		assertEquals(g, actual.getG());
		assertEquals(b, actual.getB());
	}
}
