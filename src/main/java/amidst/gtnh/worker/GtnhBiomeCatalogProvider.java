package amidst.gtnh.worker;

import amidst.mojangapi.world.biome.BiomeList;

/**
 * Marker for Minecraft interfaces whose biome registry is supplied by the
 * running game rather than Amidst's vanilla version table.
 */
public interface GtnhBiomeCatalogProvider {
	BiomeList getBiomeList();
}
