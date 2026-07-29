package amidst.gtnh.export;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import amidst.mojangapi.world.Dimension;

public final class GtnhCoordinateFiles {
	private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

	private GtnhCoordinateFiles() {
	}

	public static void writeCsv(Path file, List<GtnhCoordinate> coordinates) throws IOException {
		try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
			writer.write("x,z");
			writer.newLine();
			for (GtnhCoordinate coordinate : coordinates) {
				writer.write(Integer.toString(coordinate.x()));
				writer.write(',');
				writer.write(Integer.toString(coordinate.z()));
				writer.newLine();
			}
		}
	}

	/**
	 * JourneyMap 1.7.10 stores one JSON document per waypoint. The ZIP is a
	 * portable coordinate file whose contents can be extracted into the
	 * matching world's JourneyMap waypoint directory.
	 */
	public static void writeJourneyMapZip(
			Path file,
			List<GtnhCoordinate> coordinates,
			GtnhCoordinateType type,
			int dimensionId) throws IOException {
		try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
			for (int index = 0; index < coordinates.size(); index++) {
				GtnhCoordinate coordinate = coordinates.get(index);
				Map<String, Object> waypoint =
						createJourneyMapWaypoint(coordinate, type, dimensionId);
				String id = (String) waypoint.get("id");
				String entryName = sanitizeWaypointFilename(id) + ".json";
				zip.putNextEntry(new ZipEntry(entryName));
				zip.write(PRETTY_GSON.toJson(waypoint).getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
	}

	public static List<GtnhWaypoint> toWaypoints(
			List<GtnhCoordinate> coordinates,
			GtnhCoordinateType type) {
		return coordinates.stream()
				.map(coordinate -> new GtnhWaypoint(
						waypointName(type, coordinate),
						coordinate.x(),
						64,
						coordinate.z(),
						type.getColor().getRed(),
						type.getColor().getGreen(),
						type.getColor().getBlue()))
				.toList();
	}

	static Map<String, Object> createJourneyMapWaypoint(
			GtnhCoordinate coordinate,
			GtnhCoordinateType type,
			int dimensionId) {
		String name = waypointName(type, coordinate);
		int storedX = dimensionId == Dimension.NETHER.getId()
				? Math.multiplyExact(coordinate.x(), 8)
				: coordinate.x();
		int storedZ = dimensionId == Dimension.NETHER.getId()
				? Math.multiplyExact(coordinate.z(), 8)
				: coordinate.z();
		int height = 64;
		Map<String, Object> waypoint = new LinkedHashMap<>();
		waypoint.put("id", name + "_" + storedX + "," + height + "," + storedZ);
		waypoint.put("name", name);
		waypoint.put("icon", "waypoint-normal.png");
		waypoint.put("x", storedX);
		waypoint.put("y", height);
		waypoint.put("z", storedZ);
		waypoint.put("r", type.getColor().getRed());
		waypoint.put("g", type.getColor().getGreen());
		waypoint.put("b", type.getColor().getBlue());
		waypoint.put("enable", true);
		waypoint.put("type", "Normal");
		waypoint.put("origin", "JourneyMap");
		waypoint.put("dimensions", List.of(dimensionId));
		return waypoint;
	}

	private static String waypointName(
			GtnhCoordinateType type,
			GtnhCoordinate coordinate) {
		return type.getDisplayName() + " " + coordinate.x() + "," + coordinate.z();
	}

	private static String sanitizeWaypointFilename(String id) {
		return id.replaceAll("[\\\\/:\"*?<>|]", "_");
	}
}
