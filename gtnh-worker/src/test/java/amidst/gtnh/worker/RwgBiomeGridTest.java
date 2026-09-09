package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RwgBiomeGridTest {
    @Test public void negativeCoordinatesAndPageEdgesNeverAliasAndReuseCells() throws Exception {
        RwgBiomeGrid grid = new RwgBiomeGrid(16);
        AtomicInteger calls = new AtomicInteger();
        RwgBiomeGrid.Source source = (x, z) -> {
            calls.incrementAndGet();
            return new RwgBiomeGrid.Entry(x + ":" + z, (x ^ z) & 255);
        };
        int[] edges = {-136, -128, -8, 0, 8, 120, 128};
        for (int pass = 0; pass < 2; pass++) for (int x : edges) for (int z : edges) {
            RwgBiomeGrid.Entry entry = grid.get(x, z, source);
            assertEquals(x + ":" + z, entry.biome);
            assertEquals((x ^ z) & 255, entry.id);
        }
        assertEquals(edges.length * edges.length, calls.get());
    }
    @Test public void evictionRecomputesOnlyTheEvictedPage() throws Exception {
        RwgBiomeGrid grid = new RwgBiomeGrid(2);
        AtomicInteger calls = new AtomicInteger();
        RwgBiomeGrid.Source source = (x, z) -> new RwgBiomeGrid.Entry(x, calls.incrementAndGet());
        RwgBiomeGrid.Entry first = grid.get(0, 0, source);
        grid.get(128, 0, source);
        assertSame(first, grid.get(0, 0, source));
        grid.get(256, 0, source);
        assertSame(first, grid.get(0, 0, source));
        assertEquals(4, grid.get(128, 0, source).id);
        assertEquals(4, calls.get());
    }
}
