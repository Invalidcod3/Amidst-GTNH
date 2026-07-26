package amidst.gtnh.cli;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.gtnh.worker.GtnhBiomeColorPalette;
import amidst.gtnh.worker.GtnhBiomeWorkerClient;
import amidst.gtnh.worker.GtnhWorkerInfo;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.biome.BiomeColor;

/**
 * Headless vertical slice used before the GTNH backend is wired into the
 * Swing profile-selection flow.
 */
public final class GtnhBiomePreview {
	private GtnhBiomePreview() {
	}

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		GtnhBiomeWorkerClient client = new GtnhBiomeWorkerClient(
				options.host,
				options.port,
				options.token,
				options.timeoutMillis);
		GtnhWorkerInfo info = client.getWorkerInfo();
		int dimension = options.resolveDimension(info);
		int[] ids = client.sampleBiomes(
				info.seed(),
				dimension,
				options.x,
				options.z,
				options.width,
				options.height,
				options.step);
		writePreview(options, GtnhBiomeColorPalette.create(info, options.colors), ids);
		printSummary(options, info, dimension, ids);
	}

	private static void writePreview(Options options, Map<Integer, BiomeColor> palette, int[] ids)
			throws IOException {
		Map<Integer, Integer> colors = palette.entrySet().stream().collect(Collectors.toMap(
				Map.Entry::getKey,
				entry -> entry.getValue().getRGB(),
				(previous, replacement) -> previous));
		BufferedImage image = new BufferedImage(options.width, options.height, BufferedImage.TYPE_INT_RGB);
		for (int z = 0; z < options.height; z++) {
			for (int x = 0; x < options.width; x++) {
				int id = ids[z * options.width + x];
				image.setRGB(x, z, colors.getOrDefault(id, deterministicFallbackColor(id)));
			}
		}
		Path parent = options.output.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		ImageIO.write(image, "png", options.output.toFile());
	}

	private static int deterministicFallbackColor(int biomeId) {
		float hue = Math.floorMod(biomeId * 47, 360) / 360.0f;
		return Color.HSBtoRGB(hue, 0.55f, 0.82f) & 0x00FF_FFFF;
	}

	private static void printSummary(
			Options options,
			GtnhWorkerInfo info,
			int dimension,
			int[] ids) {
		Map<Integer, String> names = info.biomes().stream().collect(Collectors.toMap(
				GtnhBiomeDescriptor::id,
				GtnhBiomeDescriptor::name,
				(previous, replacement) -> previous));
		Map<Integer, Integer> counts = new LinkedHashMap<>();
		Arrays.stream(ids).forEach(id -> counts.merge(id, 1, Integer::sum));
		System.out.println("GTNH biome preview written to " + options.output.toAbsolutePath());
		System.out.println(
				"seed="
						+ info.seed()
						+ ", dimension="
						+ dimensionName(
								dimension,
								info)
						+ ", worldType="
						+ info.worldType()
						+ ", chunkManager="
						+ info.chunkManagerClass());
		counts.entrySet().stream()
				.sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
				.forEach(entry -> System.out.println(
						entry.getKey()
								+ " "
								+ names.getOrDefault(entry.getKey(), "<unknown>")
								+ ": "
								+ entry.getValue()));
	}

	private static String dimensionName(
			int dimension,
			GtnhWorkerInfo info) {
		if (dimension == info.twilightForestDimensionId()) {
			return "twilight-forest";
		}
		if (dimension == info.moonDimensionId()) {
			return "moon";
		}
		if (dimension == info.marsDimensionId()) return "mars";
		if (dimension == info.asteroidsDimensionId()) return "asteroids";
		if (dimension == info.ceresDimensionId()) return "ceres";
		if (dimension == info.ioDimensionId()) return "io";
		if (dimension == info.enceladusDimensionId()) return "enceladus";
		if (dimension == info.proteusDimensionId()) return "proteus";
		if (dimension == info.plutoDimensionId()) return "pluto";
		if (dimension == info.mehenBeltDimensionId()) return "mehen-belt";
		if (dimension == info.ross128bDimensionId()) return "ross-128b";
		return switch (dimension) {
			case -1 -> "nether";
			case 1 -> "end";
			default -> "overworld";
		};
	}

	private static final class Options {
		private String host = "127.0.0.1";
		private int port = 47_117;
		private String token = "";
		private int timeoutMillis = 30_000;
		private String dimension = "overworld";
		private int x = -4_096;
		private int z = -4_096;
		private int width = 256;
		private int height = 256;
		private int step = 32;
		private Path output = Path.of("gtnh-overworld-biomes.png");
		private Path colors;

		private static Options parse(String[] args) throws MinecraftInterfaceException {
			Map<String, String> values = new HashMap<>();
			for (int i = 0; i < args.length; i += 2) {
				if (i + 1 >= args.length || !args[i].startsWith("--")) {
					throw new MinecraftInterfaceException(
							"arguments must be --name value pairs; received " + Arrays.toString(args));
				}
				values.put(args[i].substring(2), args[i + 1]);
			}
			Options options = new Options();
			options.host = values.getOrDefault("host", options.host);
			options.port = integer(values, "port", options.port);
			options.token = values.getOrDefault("token", options.token);
			options.timeoutMillis = integer(values, "timeout-ms", options.timeoutMillis);
			options.dimension = values.getOrDefault("dimension", options.dimension);
			options.x = integer(values, "x", options.x);
			options.z = integer(values, "z", options.z);
			options.width = integer(values, "width", options.width);
			options.height = integer(values, "height", options.height);
			options.step = integer(values, "step", options.step);
			options.output = Path.of(values.getOrDefault("output", options.output.toString()));
			if (values.containsKey("colors")) {
				options.colors = Path.of(values.get("colors"));
			}
			if (options.width < 1
					|| options.height < 1
					|| options.step < 1
					|| (long) options.width * options.height > 65_536L) {
				throw new MinecraftInterfaceException(
						"width*height must be between 1 and 65,536 samples; use step to cover a larger area");
			}
			return options;
		}

		private int resolveDimension(GtnhWorkerInfo info)
				throws MinecraftInterfaceException {
			String value = dimension;
			if ("overworld".equalsIgnoreCase(value) || "0".equals(value)) {
				return 0;
			}
			if ("nether".equalsIgnoreCase(value) || "-1".equals(value)) {
				return -1;
			}
			if ("end".equalsIgnoreCase(value) || "1".equals(value)) {
				return 1;
			}
			if ("moon".equalsIgnoreCase(value)) {
				return info.moonDimensionId();
			}
			if ("mars".equalsIgnoreCase(value)) {
				return info.marsDimensionId();
			}
			if ("asteroids".equalsIgnoreCase(value)
					|| "asteroid".equalsIgnoreCase(value)) {
				return info.asteroidsDimensionId();
			}
			if ("ceres".equalsIgnoreCase(value)) {
				return info.ceresDimensionId();
			}
			if ("io".equalsIgnoreCase(value)) {
				return info.ioDimensionId();
			}
			if ("enceladus".equalsIgnoreCase(value)) {
				return info.enceladusDimensionId();
			}
			if ("proteus".equalsIgnoreCase(value)) {
				return info.proteusDimensionId();
			}
			if ("pluto".equalsIgnoreCase(value)) {
				return info.plutoDimensionId();
			}
			if ("mehen-belt".equalsIgnoreCase(value)
					|| "mehen".equalsIgnoreCase(value)) {
				return info.mehenBeltDimensionId();
			}
			if ("ross-128b".equalsIgnoreCase(value)
					|| "ross128b".equalsIgnoreCase(value)) {
				return info.ross128bDimensionId();
			}
			if ("twilight-forest".equalsIgnoreCase(value)
					|| "twilight".equalsIgnoreCase(value)) {
				return info.twilightForestDimensionId();
			}
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException ignored) {
			}
			throw new MinecraftInterfaceException(
					"--dimension must be overworld, nether, end, moon, mars, asteroids, "
							+ "ceres, io, enceladus, proteus, pluto, mehen-belt, "
							+ "ross-128b, twilight-forest, "
							+ "or a numeric dimension ID");
		}

		private static int integer(Map<String, String> values, String key, int defaultValue)
				throws MinecraftInterfaceException {
			String value = values.get(key);
			if (value == null) {
				return defaultValue;
			}
			try {
				return Integer.parseInt(value);
			} catch (NumberFormatException e) {
				throw new MinecraftInterfaceException("--" + key + " must be an integer", e);
			}
		}
	}
}
