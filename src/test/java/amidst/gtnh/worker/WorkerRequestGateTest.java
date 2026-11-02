package amidst.gtnh.worker;

import static org.junit.Assert.*;

import amidst.threading.TaskCancellation;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WorkerRequestGateTest {
    @Test(timeout = 4000)
    public void congestionBoundsRetriesAndSuccessfulRecoveryRestoresCapacity() throws Exception {
        AtomicLong now = new AtomicLong(1);
        WorkerRequestGate gate = new WorkerRequestGate(now::get);
        assertEquals(2, gate.congested(4));
        assertEquals("One failed wave must not collapse capacity repeatedly", 2, gate.congested(4));
        gate.acquire(4);
        gate.acquire(4);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch waiting = new CountDownLatch(1);
        try {
            Future<?> third =
                    worker.submit(
                            () -> {
                                waiting.countDown();
                                gate.acquire(4);
                                gate.release();
                            });
            assertTrue(waiting.await(1, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> third.get(100, TimeUnit.MILLISECONDS));
            now.set(11_000_000_001L);
            gate.succeeded();
            third.get(1, TimeUnit.SECONDS);
            gate.release();
            gate.release();
        } finally {
            worker.shutdownNow();
        }
    }

    @Test(timeout = 4000)
    public void cancelledViewportDoesNotWaitForCapacityOrLeakAPermit() throws Exception {
        WorkerRequestGate gate = new WorkerRequestGate();
        gate.acquire(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> queued =
                    worker.submit(
                            () -> TaskCancellation.run(cancelled::get, () -> gate.acquire(1)));
            cancelled.set(true);
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            gate.release();
            gate.acquire(1);
            gate.release();
        } finally {
            worker.shutdownNow();
        }
    }
}
