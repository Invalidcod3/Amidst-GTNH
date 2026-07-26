package amidst.gtnh.worker;

import java.util.List;
import java.util.Locale;

import amidst.documentation.Immutable;
import amidst.mojangapi.world.biome.Biome;
import amidst.mojangapi.world.biome.BiomeType;

@Immutable
public record GtnhBiomeDescriptor(
		int id,
		String name,
		int mapColor,
		float temperature,
		float rainfall,
		float rootHeight,
		float heightVariation,
		List<String> tags) {

	public GtnhBiomeDescriptor(
			int id,
			String name,
			int mapColor,
			float temperature,
			float rainfall,
			float rootHeight,
			float heightVariation) {
		this(id, name, mapColor, temperature, rainfall, rootHeight, heightVariation, List.of());
	}

	public GtnhBiomeDescriptor {
		if (id < 0) {
			throw new IllegalArgumentException("biome id must not be negative");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("biome name must not be blank");
		}
		tags = tags == null
				? List.of()
				: tags.stream()
						.filter(tag -> tag != null && !tag.isBlank())
						.map(tag -> tag.toUpperCase(Locale.ROOT))
						.distinct()
						.toList();
	}

	public boolean hasTag(String tag) {
		return tags.contains(tag.toUpperCase(Locale.ROOT));
	}

	public Biome toBiome() {
		return new Biome(id, name, new BiomeType(rootHeight, heightVariation));
	}
}
