package amidst.gtnh.worker;

import java.awt.Color;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.biome.BiomeColor;

public final class GtnhBiomeColorPalette {
	private GtnhBiomeColorPalette() {
	}

	public static Map<Integer, BiomeColor> create(GtnhWorkerInfo info, Path overrideFile)
			throws MinecraftInterfaceException {
		Map<Integer, BiomeColor> result = new LinkedHashMap<>();
		for (GtnhBiomeDescriptor biome : info.biomes()) {
			result.put(biome.id(), defaultColor(biome));
		}
		if (overrideFile != null) {
			applyOverrides(result, info, overrideFile);
		}
		return Map.copyOf(result);
	}

	static BiomeColor defaultColor(GtnhBiomeDescriptor biome) {
		String name = biome.name().toLowerCase(Locale.ROOT);
		int variation = Math.floorMod(biome.id() * 17, 13) - 6;

		BiomeColor gtnhEndColor = gtnhEndColor(name);
		if (gtnhEndColor != null) {
			return gtnhEndColor;
		}
		if (biome.hasTag("TWILIGHT_FOREST")) {
			return color(biome.mapColor(), 0);
		}
		if (biome.hasTag("MOON")) {
			return color(0x8C8C84, 0);
		}
		if (biome.hasTag("MARS")) {
			return color(0x9C563B, variation);
		}
		if (biome.hasTag("ASTEROIDS")) {
			return color(0x76716C, variation);
		}
		if (biome.hasTag("GALAXYSPACE")) {
			if (name.contains("ioash") || name.contains("io ash")) {
				return color(0x66564B, variation);
			}
			if ("io".equals(name)) {
				return color(0xB39857, variation);
			}
			if (name.startsWith("pluto")) {
				return color(0xA7B7C2, variation);
			}
			return color(0x737B83, variation);
		}
		if (biome.hasTag("RIVER")) {
			return riverColor(biome, variation);
		}
		BiomeColor bopNetherColor = bopNetherColor(name, variation);
		if (bopNetherColor != null) {
			return bopNetherColor;
		}
		if (biome.hasTag("WATER") && !biome.hasTag("OCEAN")) {
			return color(0x5E9CBC, variation);
		}
		if (biome.hasTag("SNOWY") || contains(name, "frozen", "ice", "glacier", "snow")) {
			return color(0xD7D5CC, variation);
		}
		if ((biome.hasTag("BEACH") && !biome.hasTag("OCEAN")) || contains(name, "beach", "shore")) {
			return color(0xD8C58B, variation);
		}
		if (biome.hasTag("MESA") || contains(name, "mesa", "canyon", "badland", "red desert")) {
			return color(0xB96B43, variation);
		}
		if (biome.hasTag("SANDY") || contains(name, "desert", "dune", "oasis")) {
			return color(0xD9B66F, variation);
		}
		if (biome.hasTag("SWAMP") || contains(name, "swamp", "wetland", "marsh", "bog", "fen")) {
			return color(0x587B64, variation);
		}
		if (biome.hasTag("JUNGLE") || contains(name, "jungle", "rainforest", "tropical")) {
			return color(0x357A4B, variation);
		}
		if (biome.hasTag("MUSHROOM") || contains(name, "mushroom", "fung", "mystic")) {
			return color(0x8A668F, variation);
		}
		if (contains(name, "volcan", "lava", "inferno")) {
			return color(0x765047, variation);
		}
		if (biome.hasTag("WASTELAND") || contains(name, "wasteland", "dead", "ominous", "scrub")) {
			return color(0x817660, variation);
		}
		if (biome.hasTag("MOUNTAIN")
				|| contains(name, "alps", "mountain", "highland", "crag", "cliff", "peak")) {
			return color(0x87958A, variation);
		}
		if (biome.hasTag("FOREST") || contains(name, "forest", "wood", "grove", "redwood", "taiga")) {
			return color(0x4F8254, variation);
		}
		if (biome.hasTag("PLAINS")
				|| contains(name, "savanna", "steppe", "prairie", "plains", "meadow", "field")) {
			return color(0x91A866, variation);
		}

		float rainfall = clamp(biome.rainfall(), 0.0f, 1.0f);
		float temperature = clamp(biome.temperature(), 0.0f, 1.0f);
		float hue = 0.16f + rainfall * 0.17f;
		float saturation = 0.30f + rainfall * 0.30f + temperature * 0.08f;
		float brightness = clamp(0.72f - Math.max(0.0f, biome.rootHeight()) * 0.06f, 0.56f, 0.78f);
		int rgb = Color.HSBtoRGB(hue, saturation, brightness);
		return color(rgb, variation);
	}

	private static BiomeColor gtnhEndColor(String name) {
		if ("gtnh end void".equals(name)) {
			return color(0x17131F, 0);
		}
		if ("gtnh central end".equals(name)) {
			return color(0xC9C29B, 0);
		}
		if ("hee infested forest".equals(name)) {
			return color(0x526B48, 0);
		}
		if ("hee burning mountains".equals(name)) {
			return color(0x9B4B32, 0);
		}
		if ("hee enchanted island".equals(name)) {
			return color(0x6575A8, 0);
		}
		return null;
	}

	private static BiomeColor bopNetherColor(String name, int variation) {
		if (name.contains("visceral heap")) {
			return color(0x984C58, variation);
		}
		if (name.contains("polar chasm")) {
			return color(0x9CB5BA, variation);
		}
		if (name.contains("corrupted sands")) {
			return color(0x70505C, variation);
		}
		if (name.contains("boneyard")) {
			return color(0xB2A68D, variation);
		}
		if (name.contains("undergarden")) {
			return color(0x3F6046, variation);
		}
		if (name.contains("phantasmagoric inferno")) {
			return color(0xC6532D, variation);
		}
		if ("hell".equals(name)) {
			return color(0x6B2D2D, variation);
		}
		return null;
	}

	private static BiomeColor riverColor(GtnhBiomeDescriptor biome, int variation) {
		if (biome.hasTag("SNOWY")) {
			return color(0xB8DCEA, variation);
		}

		float temperature = clamp(biome.temperature(), 0.0f, 1.0f);
		if (biome.hasTag("HOT")) {
			temperature = Math.max(temperature, 0.8f);
		} else if (biome.hasTag("COLD")) {
			/*
			 * RWG 1.7.10 marks its 0.8-temperature "Temperate River" as COLD.
			 * Keep the measured temperature useful while preventing that legacy tag
			 * from pushing it all the way into the warm-river range.
			 */
			temperature = Math.min(temperature, 0.65f);
		}

		if (temperature <= 0.5f) {
			return interpolatedColor(0xB8DCEA, 0x7FB7D3, temperature / 0.5f, variation);
		}
		if (temperature <= 0.7f) {
			return interpolatedColor(0x7FB7D3, 0x5298BF, (temperature - 0.5f) / 0.2f, variation);
		}
		return interpolatedColor(0x5298BF, 0x3E93A5, (temperature - 0.7f) / 0.3f, variation);
	}

	private static BiomeColor interpolatedColor(int start, int end, float amount, int variation) {
		float clampedAmount = clamp(amount, 0.0f, 1.0f);
		int red = Math.round(((start >> 16) & 0xFF) * (1.0f - clampedAmount)
				+ ((end >> 16) & 0xFF) * clampedAmount);
		int green = Math.round(((start >> 8) & 0xFF) * (1.0f - clampedAmount)
				+ ((end >> 8) & 0xFF) * clampedAmount);
		int blue = Math.round((start & 0xFF) * (1.0f - clampedAmount)
				+ (end & 0xFF) * clampedAmount);
		return color((red << 16) | (green << 8) | blue, variation);
	}

	private static void applyOverrides(
			Map<Integer, BiomeColor> colors,
			GtnhWorkerInfo info,
			Path file) throws MinecraftInterfaceException {
		OverrideFile overrides;
		try (Reader reader = Files.newBufferedReader(file)) {
			overrides = new Gson().fromJson(reader, OverrideFile.class);
		} catch (IOException | JsonParseException e) {
			throw new MinecraftInterfaceException("Unable to read GTNH biome colors from " + file, e);
		}
		if (overrides == null) {
			throw new MinecraftInterfaceException("GTNH biome color file is empty: " + file);
		}

		Map<String, String> byName = overrides.byName == null ? Map.of() : overrides.byName;
		for (Map.Entry<String, String> override : byName.entrySet()) {
			boolean matched = false;
			for (GtnhBiomeDescriptor biome : info.biomes()) {
				if (biome.name().equalsIgnoreCase(override.getKey())) {
					colors.put(biome.id(), parseColor(override.getValue(), "biome '" + override.getKey() + "'"));
					matched = true;
				}
			}
			if (!matched) {
				AmidstLogger.warn("GTNH color override did not match biome name '{}'", override.getKey());
			}
		}

		Map<String, String> byId = overrides.byId == null ? Map.of() : overrides.byId;
		for (Map.Entry<String, String> override : byId.entrySet()) {
			int id;
			try {
				id = Integer.parseInt(override.getKey());
			} catch (NumberFormatException e) {
				throw new MinecraftInterfaceException(
						"GTNH biome color ID must be an integer: " + override.getKey(),
						e);
			}
			if (!colors.containsKey(id)) {
				AmidstLogger.warn("GTNH color override did not match biome id {}", id);
				continue;
			}
			colors.put(id, parseColor(override.getValue(), "biome id " + id));
		}
		AmidstLogger.info("loaded GTNH biome color overrides from '{}'", file);
	}

	private static BiomeColor parseColor(String text, String subject) throws MinecraftInterfaceException {
		if (text == null || !text.matches("#?[0-9a-fA-F]{6}")) {
			throw new MinecraftInterfaceException(
					"GTNH color for " + subject + " must use #RRGGBB format");
		}
		int rgb = Integer.parseInt(text.replace("#", ""), 16);
		return BiomeColor.from((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
	}

	private static boolean contains(String name, String... fragments) {
		for (String fragment : fragments) {
			if (name.contains(fragment)) {
				return true;
			}
		}
		return false;
	}

	private static BiomeColor color(int rgb, int variation) {
		return BiomeColor.from(
				clamp(((rgb >> 16) & 0xFF) + variation, 0, 255),
				clamp(((rgb >> 8) & 0xFF) + variation, 0, 255),
				clamp((rgb & 0xFF) + variation, 0, 255));
	}

	private static int clamp(int value, int minimum, int maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static final class OverrideFile {
		private Map<String, String> byId;
		private Map<String, String> byName;
	}
}
