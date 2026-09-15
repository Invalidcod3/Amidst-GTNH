package amidst.gtnh.export;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.CancellationException;

public class RadialCoordinateLocatorTest {
    @Test
    public void matchesGlobalDistanceOracleAcrossNegativeTileBoundaries() throws Exception {
        Random random = new Random(98127);
        List<GtnhCoordinate> points = new ArrayList<>();
        for (int i = 0; i < 1800; i++)
            points.add(
                    new GtnhCoordinate(
                            random.nextInt(9000) - 4500, random.nextInt(9000) - 4500, "point"));
        for (int[] origin : new int[][] {{0, 0}, {-1, -512}, {511, 512}, {-2400, 1500}}) {
            int x = origin[0], z = origin[1];
            Comparator<GtnhCoordinate> order =
                    Comparator.comparingLong((GtnhCoordinate p) -> distance(x, z, p))
                            .thenComparingInt(GtnhCoordinate::x)
                            .thenComparingInt(GtnhCoordinate::z);
            List<GtnhCoordinate> expected = points.stream().sorted(order).limit(80).toList();
            var actual = RadialCoordinateLocator.locate((tx, tz) -> points, x, z, 80, n -> {});
            assertEquals(expected, actual.coordinates());
            assertFalse(actual.exhausted());
        }
    }

    @Test
    public void equalDistancePointsAreDeterministicAndDuplicatesDoNotConsumeCount()
            throws Exception {
        List<GtnhCoordinate> points =
                List.of(
                        new GtnhCoordinate(1, 0, "A"),
                        new GtnhCoordinate(-1, 0, "B"),
                        new GtnhCoordinate(0, 1, "C"),
                        new GtnhCoordinate(0, -1, "D"),
                        new GtnhCoordinate(-1, 0, "B"));
        var result = RadialCoordinateLocator.locate((x, z) -> points, 0, 0, 4, n -> {});
        assertEquals(
                List.of(points.get(1), points.get(3), points.get(2), points.get(0)),
                result.coordinates());
    }

    @Test
    public void budgetNeverReturnsUnprovenNearerPoints() throws Exception {
        var r =
                RadialCoordinateLocator.locate(
                        (x, z) -> List.of(new GtnhCoordinate(510, 510, "far")),
                        0,
                        0,
                        1,
                        1,
                        n -> {});
        assertTrue(r.exhausted());
        assertTrue(r.coordinates().isEmpty());
        assertEquals(1, r.scanned());
    }

    @Test
    public void handlesWorldEdgeAndEmptySearch() throws Exception {
        var point = new GtnhCoordinate(30000000, -30000000, "edge");
        assertEquals(
                List.of(point),
                RadialCoordinateLocator.locate(
                                (x, z) -> List.of(point), point.x(), point.z(), 1, n -> {})
                        .coordinates());
        assertTrue(
                RadialCoordinateLocator.locate((x, z) -> List.of(), 0, 0, 2, 7, n -> {})
                        .exhausted());
    }

    @Test
    public void cancellationStopsBeforeQuery() throws Exception {
        Thread.currentThread().interrupt();
        try {
            RadialCoordinateLocator.locate(
                    (x, z) -> {
                        fail("Must not query");
                        return List.of();
                    },
                    0,
                    0,
                    1,
                    n -> {});
            fail();
        } catch (CancellationException expected) {
        } finally {
            Thread.interrupted();
        }
    }

    private static long distance(int x, int z, GtnhCoordinate p) {
        long dx = (long) x - p.x(), dz = (long) z - p.z();
        return dx * dx + dz * dz;
    }
}
