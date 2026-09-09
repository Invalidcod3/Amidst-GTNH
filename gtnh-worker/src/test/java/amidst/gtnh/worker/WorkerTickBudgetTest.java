package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class WorkerTickBudgetTest {
    @Test public void idleAndBusyTicksShareTimeWithoutSkippingEarlyTicks() {
        WorkerTickBudget budget = new WorkerTickBudget(20_000_000L);
        budget.begin(1_000_000_000L);
        assertEquals(20_000_000L, budget.finish(1_005_000_000L));
        // Next tick starts 49 ms later: a fixed 50 ms gate used to miss it.
        budget.begin(1_049_000_000L);
        assertEquals(20_000_000L, budget.finish(1_050_000_000L));
        budget.begin(2_000_000_000L);
        assertEquals(10_000_000L, budget.finish(2_035_000_000L));
        budget.begin(3_000_000_000L);
        assertEquals(1_000_000L, budget.finish(3_080_000_000L));
        assertEquals(20_000_000L, budget.finish(4_000_000_000L)); // Paused tick.
    }
}
