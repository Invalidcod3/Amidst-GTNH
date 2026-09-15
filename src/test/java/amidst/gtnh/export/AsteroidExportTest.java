package amidst.gtnh.export;

import static org.junit.Assert.*;

import amidst.gtnh.prospecting.ProspectingData.*;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

import org.junit.Test;

import java.nio.file.Files;
import java.util.List;

public class AsteroidExportTest {
    private Deposit asteroid() {
        Deposit d = new Deposit();
        d.id = "asteroid.small:ore.small.iron";
        d.kind = "ASTEROID_SMALL";
        d.name = "Iron";
        d.source = "PREDICTED";
        d.x = -258;
        d.z = 364;
        d.y = 250;
        d.minY = 237;
        d.maxY = 254;
        return d;
    }

    @Test
    public void clickUsesAsteroidCenterHeightInsteadOfEnvelopeMidpoint() {
        for (Dimension dim :
                new Dimension[] {
                    Dimension.ASTEROIDS, Dimension.KUIPER_BELT, Dimension.MEHEN_BELT, Dimension.END
                }) {
            GtnhMapWaypoint clicked =
                    GtnhMapWaypoint.deposit(dim, asteroid(), CoordinatesInWorld.from(0, 0));
            assertEquals(dim, clicked.dimension());
            assertEquals(-258, clicked.waypoint().x());
            assertEquals(364, clicked.waypoint().z());
            assertEquals(250, clicked.waypoint().y());
        }
    }

    @Test
    public void exportPreservesAsteroidTypeCenterAndHeightInFilesAndDirectImport()
            throws Exception {
        QueryFilter filter = new QueryFilter();
        filter.id = asteroid().id;
        List<GtnhCoordinate> points =
                ProspectingExport.points(
                        asteroid(),
                        new ProspectingExport.Options(filter, false, 10),
                        -512,
                        0,
                        0,
                        512);
        assertEquals(1, points.size());
        assertEquals(Integer.valueOf(250), points.get(0).y());
        assertTrue(points.get(0).name().contains(amidst.i18n.I18n.text("Small-ore asteroid")));
        GtnhCoordinateType type =
                GtnhCoordinateType.forDimension(Dimension.ASTEROIDS).stream()
                        .filter(t -> t.isProspecting() && t.prospectingMode().equals("ORES"))
                        .findFirst()
                        .orElseThrow();
        var waypoint = GtnhCoordinateFiles.createJourneyMapWaypoint(points.get(0), type, -30);
        assertEquals(250, waypoint.get("y"));
        assertEquals(-258, waypoint.get("x"));
        assertEquals(List.of(-30), waypoint.get("dimensions"));
        assertTrue(waypoint.get("name").toString().contains("Iron"));
        assertEquals(250, GtnhCoordinateFiles.toWaypoints(points, type).get(0).y());
        var path = Files.createTempFile("asteroid-export", ".csv");
        try {
            GtnhCoordinateFiles.writeCsv(path, points);
            assertEquals("x,z,y\n-258,364,250\n", Files.readString(path).replace("\r\n", "\n"));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    @Test
    public void ordinaryRecordedVeinFiltersCannotTurnAsteroidsIntoRecordedVeins() {
        Deposit d = asteroid();
        QueryFilter filter = new QueryFilter();
        assertTrue(filter.accepts(d));
        filter.recordedOnly = true;
        assertFalse(filter.accepts(d));
        filter.recordedOnly = false;
        filter.id = "ore.small.iron";
        assertFalse(filter.accepts(d));
        filter.id = d.id;
        filter.minY = 255;
        assertFalse(filter.accepts(d));
    }
}
