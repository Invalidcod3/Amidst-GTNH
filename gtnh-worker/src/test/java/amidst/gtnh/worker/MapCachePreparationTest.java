package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MapCachePreparationTest {
    @Test(timeout = 5000)
    public void coldFingerprintNeverBlocksOtherCacheRequests() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        ExecutorService background = Executors.newSingleThreadExecutor();
        AtomicInteger calls = new AtomicInteger();
        MapCachePreparation preparation =
                new MapCachePreparation(
                        () -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(3, TimeUnit.SECONDS))
                                    throw new IllegalStateException("timeout");
                            } catch (InterruptedException e) {
                                throw new IllegalStateException(e);
                            }
                            return "complete fingerprint";
                        },
                        background::execute,
                        System::nanoTime,
                        failure -> {
                            throw new AssertionError(failure);
                        });
        try {
            assertNull(preparation.getIfReady());
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            // This must return while the background task is still waiting.
            for (int i = 0; i < 50; i++) assertNull(preparation.getIfReady());
            assertEquals(1, calls.get());
            release.countDown();
            background.shutdown();
            assertTrue(background.awaitTermination(1, TimeUnit.SECONDS));
            assertEquals("complete fingerprint", preparation.getIfReady());
        } finally {
            release.countDown();
            background.shutdownNow();
        }
    }

    @Test
    public void startupFailureRetriesWithoutCachingAPartialIdentity() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicLong now = new AtomicLong(1);
        AtomicInteger calls = new AtomicInteger();
        MapCachePreparation preparation =
                new MapCachePreparation(
                        () -> {
                            if (calls.incrementAndGet() == 1)
                                throw new IllegalStateException("config still being written");
                            return "ready";
                        },
                        tasks::add,
                        now::get,
                        failure -> {});
        assertNull(preparation.getIfReady());
        tasks.remove().run();
        assertNull(preparation.getIfReady());
        assertTrue(tasks.isEmpty());
        now.addAndGet(30_000_000_000L);
        assertNull(preparation.getIfReady());
        tasks.remove().run();
        assertEquals("ready", preparation.getIfReady());
        assertEquals(2, calls.get());
    }

    @Test
    public void pendingIdentityDoesNotProduceATileStampOrReadWorldData() {
        ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        MapCacheIdentity identity =
                new MapCacheIdentity(
                        new MapCachePreparation(
                                () -> "ready", tasks::add, () -> 1L, failure -> {}));
        assertNull(identity.viewIdentity(73));
        assertNull(identity.tile(73, 0, "minecraft:overworld", 0, 0, 128, 128, 4));
        assertEquals(1, tasks.size());
    }
}
