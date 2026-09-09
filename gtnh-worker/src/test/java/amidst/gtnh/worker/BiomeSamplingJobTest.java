package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class BiomeSamplingJobTest {
    @Test public void yieldingKeepsOddEdgesCoordinatesAndCompletedPatches() {
        for (int step : new int[] {1, 4}) {
            int[] counts = new int[35 * 19];
            BiomeSamplingJob job = new BiomeSamplingJob(-65, -129, 35, 19, step, (x, z, w, h) -> {
                int[] result = new int[w * h];
                for (int row = 0; row < h; row++) for (int col = 0; col < w; col++) {
                    int px = x + col * step, pz = z + row * step;
                    counts[((pz + 129) / step) * 35 + (px + 65) / step]++;
                    result[row * w + col] = px * 10000 + pz;
                }
                return result;
            });
            assertFalse(job.advance(0, () -> 0));
            int slices = 0;
            boolean done;
            do {
                AtomicLong clock = new AtomicLong();
                done = job.advance(1, clock::getAndIncrement);
                assertTrue(++slices <= 6);
            } while (!done);
            assertEquals(6, slices);
            for (int row = 0; row < 19; row++) for (int col = 0; col < 35; col++) {
                int index = row * 35 + col;
                assertEquals(1, counts[index]);
                assertEquals((-65 + col * step) * 10000 - 129 + row * step, job.result[index]);
            }
            assertTrue(job.advance(0, () -> 0));
        }
    }
}
