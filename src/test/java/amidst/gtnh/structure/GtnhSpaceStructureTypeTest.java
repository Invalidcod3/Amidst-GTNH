package amidst.gtnh.structure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import amidst.fragment.layer.LayerIds;
import amidst.mojangapi.world.Dimension;

public class GtnhSpaceStructureTypeTest {

	@Test
	public void everyRequestedStructureHasAUniqueLayerAndTexture() {
		Set<Integer> layerIds = new HashSet<>();
		Set<String> wireNames = new HashSet<>();
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			assertTrue(layerIds.add(type.getLayerId()));
			assertTrue(wireNames.add(type.getWireName()));
			assertNotNull(type.getIcon());
			assertTrue(type.getLayerId() >= LayerIds.GTNH_MARS_CAVERN);
			assertTrue(type.getLayerId() < LayerIds.NUMBER_OF_LAYERS);
		}
		assertEquals(15, layerIds.size());
	}

	@Test
	public void abandonedBaseVariantsUseTheRequestedMarkerTextures() {
		assertEquals(
				"galacticraft_decorative_tin.png",
				GtnhSpaceStructureType.ASTEROID_BASE_HUMAN.getMenuIcon());
		assertEquals(
				"minecraft_iron_block.png",
				GtnhSpaceStructureType.ASTEROID_BASE_MECHANICAL.getMenuIcon());
		assertEquals(
				"galacticraft_moon_rock.png",
				GtnhSpaceStructureType.ASTEROID_BASE_BIRD.getMenuIcon());
		assertEquals(
				Dimension.ASTEROIDS,
				GtnhSpaceStructureType.ASTEROID_BASE_HUMAN.getDimension());
	}

	@Test
	public void newDimensionFeaturesUseTheRequestedMarkerTextures() {
		assertEquals(
				"gregtech_molybdenum_dust.png",
				GtnhSpaceStructureType.NETHER_MOLYBDENUM_VEIN.getMenuIcon());
		assertEquals(
				"avaritia_stellar_fuel.png",
				GtnhSpaceStructureType.MEHEN_DARK_MATTER_ASTEROID.getMenuIcon());
		assertEquals(
				"minecraft_bricks.png",
				GtnhSpaceStructureType.ROSS_128B_RUIN.getMenuIcon());
		assertEquals(
				"gregtech_indium_dust.png",
				GtnhSpaceStructureType.ROSS_128B_ARSENOPYRITE_VEIN.getMenuIcon());
		assertEquals(
				Dimension.MEHEN_BELT,
				GtnhSpaceStructureType.MEHEN_DARK_MATTER_ASTEROID.getDimension());
		assertEquals(
				Dimension.ROSS_128B,
				GtnhSpaceStructureType.ROSS_128B_RUIN.getDimension());
	}
}
