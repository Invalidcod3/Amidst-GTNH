package amidst.gtnh.validation;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public class AccuracyReportFilesTest {
    @Test
    public void reportsUnknownSeparatelyAndExportsContextWithQuotedDetails() {
        AccuracyReport r = new AccuracyReport();
        r.seed = 123;
        r.dimension = -28;
        r.category = "STRUCTURES";
        r.worldSession = "session-A";
        r.add(
                "ROGUELIKE_DUNGEON",
                -16,
                30,
                "candidate",
                "",
                "UNVERIFIED",
                "Terrain fails, recheck after \"save switch\"");
        assertEquals(0, r.matched);
        assertEquals(0, r.mismatched);
        assertEquals(1, r.unverified);
        String csv = AccuracyReportFiles.csv(List.of(r));
        assertTrue(csv.contains("\"session-A\""));
        assertTrue(csv.contains("\"-28\""));
        assertTrue(csv.contains("Terrain fails, recheck after \"\"save switch\"\""));
        assertTrue(csv.contains("\"UNVERIFIED\""));
    }

    @Test
    public void boundedDetailsRetainAllCounts() {
        AccuracyReport r = new AccuracyReport();
        for (int i = 0; i < 2000; i++) r.add("BIOME", i, 0, "1", "2", "MISMATCH", "difference");
        assertEquals(2000, r.mismatched);
        assertEquals(1000, r.details.size());
        assertEquals(1000, r.omittedDetails);
    }

    @Test
    public void keepsNumericCoordinatesAndEscapesFormulaText() {
        AccuracyReport report = new AccuracyReport();
        report.x = -32;
        report.add("ORE", -32, 16, "=SUM(A1:A2)", "@value", "UNVERIFIED", "+explanation");
        String csv = AccuracyReportFiles.csv(List.of(report));
        assertTrue(csv.contains("\"-32\""));
        assertTrue(csv.contains("\"'=SUM(A1:A2)\""));
        assertTrue(csv.contains("\"'@value\""));
        assertTrue(csv.contains("\"'+explanation\""));
        assertFalse(csv.contains("\"'-32\""));
    }

    @Test
    public void jsonRetainsContextAndUnverifiedEvidence() {
        AccuracyReport report = new AccuracyReport();
        report.seed = Long.MIN_VALUE;
        report.dimension = -28;
        report.worldSession = "save-session";
        report.add("BIOME", 0, 0, "1", "", "UNVERIFIED", "Chunk not loaded");
        AccuracyReport[] decoded =
                new com.google.gson.Gson()
                        .fromJson(
                                AccuracyReportFiles.json(List.of(report)), AccuracyReport[].class);
        assertEquals(1, decoded.length);
        assertEquals(Long.MIN_VALUE, decoded[0].seed);
        assertEquals(-28, decoded[0].dimension);
        assertEquals("save-session", decoded[0].worldSession);
        assertEquals(1, decoded[0].unverified);
        assertEquals("Chunk not loaded", decoded[0].details.get(0).reason);
    }
}
