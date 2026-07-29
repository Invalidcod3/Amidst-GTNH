package amidst.gtnh.export;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import amidst.mojangapi.world.Dimension;

public class GtnhCoordinateExportTest {
	@Test
	public void catalogueContainsEveryCurrentGtnhCoordinateLayer() {
		assertEquals(67, GtnhCoordinateType.all().size());
		assertEquals(18, GtnhCoordinateType.forDimension(Dimension.OVERWORLD).size());
		assertEquals(4, GtnhCoordinateType.forDimension(Dimension.NETHER).size());
		assertEquals(6, GtnhCoordinateType.forDimension(Dimension.END).size());
		assertEquals(2, GtnhCoordinateType.forDimension(Dimension.MOON).size());
		for (Dimension dimension : Dimension.values()) {
			if (dimension == Dimension.BARNARDA_C) {
				assertTrue(GtnhCoordinateType.forDimension(dimension).isEmpty());
				continue;
			}
			assertFalse(
					"no coordinate types for " + dimension,
					GtnhCoordinateType.forDimension(dimension).isEmpty());
		}
	}

	@Test
	public void csvUsesRequestedXYShape() throws Exception {
		Path file = Files.createTempFile("amidst-coordinates-", ".csv");
		try {
			GtnhCoordinateFiles.writeCsv(
					file,
					List.of(
							new GtnhCoordinate(-12, 34, "first"),
							new GtnhCoordinate(56, -78, "second")));
			assertEquals(
					"x,z\n-12,34\n56,-78\n",
					Files.readString(file, StandardCharsets.UTF_8)
							.replace("\r\n", "\n"));
		} finally {
			Files.deleteIfExists(file);
		}
	}

	@Test
	public void netherJourneyMapCoordinatesUseLegacyStoredScale() {
		GtnhCoordinateType type =
				GtnhCoordinateType.forDimension(Dimension.NETHER).get(0);
		Map<String, Object> waypoint = GtnhCoordinateFiles.createJourneyMapWaypoint(
				new GtnhCoordinate(12, -7, "ignored"),
				type,
				-1);
		assertEquals(96, waypoint.get("x"));
		assertEquals(-56, waypoint.get("z"));
		assertEquals(List.of(-1), waypoint.get("dimensions"));
		assertEquals("Normal", waypoint.get("type"));
		assertEquals("JourneyMap", waypoint.get("origin"));
	}

	@Test
	public void journeyMapArchiveContainsOneOfficialShapeJsonPerCoordinate() throws Exception {
		Path file = Files.createTempFile("amidst-journeymap-", ".zip");
		GtnhCoordinateType type =
				GtnhCoordinateType.forDimension(Dimension.END).get(0);
		try {
			GtnhCoordinateFiles.writeJourneyMapZip(
					file,
					List.of(
							new GtnhCoordinate(10, 20, "first"),
							new GtnhCoordinate(30, 40, "second")),
					type,
					1);
			try (ZipFile zip = new ZipFile(file.toFile())) {
				assertEquals(2, zip.size());
				var entry = zip.entries().nextElement();
				assertTrue(entry.getName().endsWith(".json"));
				String json = new String(
						zip.getInputStream(entry).readAllBytes(),
						StandardCharsets.UTF_8);
				JsonObject waypoint = JsonParser.parseString(json).getAsJsonObject();
				assertEquals("waypoint-normal.png", waypoint.get("icon").getAsString());
				assertTrue(waypoint.get("enable").getAsBoolean());
				assertEquals(1, waypoint.getAsJsonArray("dimensions").get(0).getAsInt());
			}
		} finally {
			Files.deleteIfExists(file);
		}
	}
}
