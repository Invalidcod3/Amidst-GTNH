package amidst.gtnh.worker;

import amidst.threading.TaskCancellation;

import java.util.function.LongSupplier;

/**
 * Bounds live map requests and backs off after congestion without changing the user's thread
 * setting.
 */
final class WorkerRequestGate {
    private final LongSupplier clock;
    private int active, limit = 64;
    private long lastBackoff, lastIncrease;

    WorkerRequestGate() {
        this(System::nanoTime);
    }

    WorkerRequestGate(LongSupplier clock) {
        this.clock = clock;
    }

    synchronized void acquire(int requested) {
        int target = Math.max(1, Math.min(64, requested));
        while (active >= Math.min(limit, target)) {
            TaskCancellation.check();
            try {
                wait(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new java.util.concurrent.CancellationException(
                        "Map request cancelled while waiting for capacity");
            }
        }
        TaskCancellation.check();
        active++;
    }

    synchronized void release() {
        active--;
        notifyAll();
    }

    synchronized int congested(int requested) {
        long now = clock.getAsLong();
        // One overloaded wave should reduce capacity once, not once per failed socket.
        if (lastBackoff == 0 || now - lastBackoff >= 5_000_000_000L) {
            limit = Math.max(1, Math.min(limit, Math.max(1, requested)) / 2);
            lastBackoff = now;
            lastIncrease = now;
        }
        return limit;
    }

    synchronized void succeeded() {
        long now = clock.getAsLong();
        if (limit < 64
                && now - lastBackoff >= 10_000_000_000L
                && now - lastIncrease >= 1_000_000_000L) {
            limit++;
            lastIncrease = now;
            notifyAll();
        }
    }
}
