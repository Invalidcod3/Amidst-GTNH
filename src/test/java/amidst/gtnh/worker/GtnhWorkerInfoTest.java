package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.List;

import org.junit.Test;

public class GtnhWorkerInfoTest {
	@Test
	public void rejectsAnEmptyBiomeRegistry() {
		IllegalArgumentException error = assertThrows(
				IllegalArgumentException.class,
				() -> info(List.of()));

		assertEquals("worker returned an empty biome registry", error.getMessage());
	}

	@Test
	public void mergesDuplicateBiomeIdsForOlderWorkers() {
		GtnhBiomeDescriptor biome = biome(40, "Alps");
		GtnhWorkerInfo result = info(List.of(biome, biome(40, "Other Alps")));

		assertEquals(1, result.biomes().size());
		assertSame(biome, result.biomes().get(0));
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

	private static GtnhBiomeDescriptor biome(int id, String name) {
		return new GtnhBiomeDescriptor(id, name, 0x729A6A, 0.3f, 0.8f, 0.1f, 0.2f);
	}
}
