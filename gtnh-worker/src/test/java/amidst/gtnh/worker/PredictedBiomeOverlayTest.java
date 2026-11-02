package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

public class PredictedBiomeOverlayTest {
    @Test
    public void loadedEvidenceIsFreshAndNeverPollutesPurePredictions() {
        int[] prediction = {300, 329, 4, 7};
        AtomicInteger reads = new AtomicInteger();
        Thread game = Thread.currentThread();
        PredictedBiomeOverlay overlay =
                new PredictedBiomeOverlay(
                        prediction,
                        -8,
                        -4,
                        2,
                        4,
                        (x, z) -> {
                            assertSame(game, Thread.currentThread());
                            reads.incrementAndGet();
                            assertTrue(x == -6 || x == -2);
                            assertTrue(z == -2 || z == 2);
                            return x == -6 && z == -2 ? 65534 : -1;
                        });
        assertFalse(overlay.advance(0));
        assertEquals(0, reads.get());
        assertTrue(overlay.advance(Long.MAX_VALUE));
        assertArrayEquals(new int[] {65534, 329, 4, 7}, overlay.result);
        assertArrayEquals(new int[] {300, 329, 4, 7}, prediction);
        PredictedBiomeOverlay unloaded =
                new PredictedBiomeOverlay(prediction, -8, -4, 2, 4, (x, z) -> -1);
        assertTrue(unloaded.advance(Long.MAX_VALUE));
        assertArrayEquals(prediction, unloaded.result);
    }
}
