package amidst.gtnh.worker;

import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Optional disk-cache setup must never occupy the game thread or a socket worker. */
final class MapCachePreparation {
    private static final long RETRY_NANOS = 30_000_000_000L;
    private final Supplier<String> prepare;
    private final Consumer<Runnable> executor;
    private final LongSupplier clock;
    private final Consumer<Throwable> onFailure;
    private String value;
    private boolean running;
    private long retryAt;

    MapCachePreparation(Supplier<String> prepare) {
        this(
                prepare,
                task -> {
                    Thread thread = new Thread(task, "GTNH map cache preparation");
                    thread.setDaemon(true);
                    thread.start();
                },
                System::nanoTime,
                failure ->
                        AmidstGtnhWorkerLog.LOG.warn(
                                "Map cache preparation failed; maps will load without disk cache"
                                        + " and preparation will retry",
                                failure));
    }

    MapCachePreparation(
            Supplier<String> prepare,
            Consumer<Runnable> executor,
            LongSupplier clock,
            Consumer<Throwable> onFailure) {
        this.prepare = prepare;
        this.executor = executor;
        this.clock = clock;
        this.onFailure = onFailure;
    }

    /** Null means not ready. Callers must continue without disk caching. */
    synchronized String getIfReady() {
        if (value == null && !running && (retryAt == 0 || clock.getAsLong() - retryAt >= 0)) {
            running = true;
            try {
                executor.accept(this::run);
            } catch (RuntimeException failure) {
                failed(failure);
            }
        }
        return value;
    }

    private void run() {
        try {
            String prepared = prepare.get();
            if (prepared == null) throw new IllegalStateException("Missing map cache fingerprint");
            synchronized (this) {
                value = prepared;
                running = false;
            }
        } catch (RuntimeException | LinkageError failure) {
            failed(failure);
        }
    }

    private synchronized void failed(Throwable failure) {
        running = false;
        retryAt = clock.getAsLong() + RETRY_NANOS;
        onFailure.accept(failure);
    }
}
