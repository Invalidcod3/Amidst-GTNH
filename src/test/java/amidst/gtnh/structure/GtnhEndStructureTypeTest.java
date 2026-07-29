package amidst.gtnh.structure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class GtnhEndStructureTypeTest {
	@Test
	public void chaosIslandUsesTheDraconicChaoticCoreTexture() {
		GtnhEndStructureType type = GtnhEndStructureType.DRACONIC_CHAOS_ISLAND;

		assertEquals("draconic_chaotic_core.png", type.getMenuIcon());
		assertNotNull(type.getIcon());
	}

	@Test
	public void biomeIslandUsesThreeDifferentHeeTerrainSideTextures() {
		GtnhEndStructureType type = GtnhEndStructureType.HEE_BIOME_ISLAND;

		assertEquals("hee_infested_endstone_side.png", type.getMenuIcon());
		assertNotNull(type.getIcon("INFESTED_FOREST"));
		assertNotNull(type.getIcon("BURNING_MOUNTAINS"));
		assertNotNull(type.getIcon("ENCHANTED_ISLAND"));
		assertNotSame(type.getIcon("INFESTED_FOREST"), type.getIcon("BURNING_MOUNTAINS"));
		assertNotSame(type.getIcon("BURNING_MOUNTAINS"), type.getIcon("ENCHANTED_ISLAND"));
	}

	@Test
	public void dungeonTowerUsesTheVanillaEnderEyeTexture() {
		GtnhEndStructureType type = GtnhEndStructureType.HEE_DUNGEON_TOWER;

		assertEquals("ender_eye.png", type.getMenuIcon());
		assertNotNull(type.getIcon());
	}

	@Test
	public void endAsteroidsUseTheRequestedOreDustTextures() {
		assertEquals(
				"gregtech_naquadah_dust_highlighted.png",
				GtnhEndStructureType.END_NAQUADAH_ASTEROID.getMenuIcon());
		assertEquals(
				"gregtech_scheelite_dust.png",
				GtnhEndStructureType.END_SCHEELITE_ASTEROID.getMenuIcon());
		assertEquals(
				"gregtech_platinum_dust.png",
				GtnhEndStructureType.END_PLATINUM_ASTEROID.getMenuIcon());
		assertNotNull(GtnhEndStructureType.END_NAQUADAH_ASTEROID.getIcon());
		assertNotNull(GtnhEndStructureType.END_SCHEELITE_ASTEROID.getIcon());
		assertNotNull(GtnhEndStructureType.END_PLATINUM_ASTEROID.getIcon());
	}
}
