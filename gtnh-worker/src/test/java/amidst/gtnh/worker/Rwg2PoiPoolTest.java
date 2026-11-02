package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class Rwg2PoiPoolTest {
    @Test
    public void parallelPoiUsesExclusiveContextsWithOnlyOnePreparedSuccessor() throws Exception {
        Thread owner = Thread.currentThread();
        AtomicInteger created = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(4), release = new CountDownLatch(1);
        java.util.Set<Integer> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.List<Rwg2PoiPool<Integer>.Job<Integer>> jobs = new java.util.ArrayList<>();
        try (Rwg2PoiPool<Integer> pool =
                new Rwg2PoiPool<>(
                        (seed, simplex) -> {
                            assertSame(owner, Thread.currentThread());
                            return created.incrementAndGet();
                        },
                        4)) {
            for (int i = 0; i < 5; i++)
                jobs.add(
                        pool.submit(
                                1,
                                false,
                                64,
                                context -> {
                                    assertTrue(
                                            "Each task exclusively owns a context",
                                            active.add(context));
                                    entered.countDown();
                                    try {
                                        release.await();
                                    } catch (InterruptedException e) {
                                        throw new AssertionError(e);
                                    } finally {
                                        active.remove(context);
                                    }
                                    return context;
                                },
                                () -> {}));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(4, active.size());
            assertEquals(5, created.get());
            assertNull(pool.submit(1, false, 64, context -> context, () -> {}));
            release.countDown();
            for (int i = 0; i < jobs.size(); i++)
                assertEquals(Integer.valueOf(i + 1), jobs.get(i).result.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    public void boundedPreparationSingleWorkerCancellationAndWakeup() throws Exception {
        Thread owner = Thread.currentThread();
        AtomicInteger created = new AtomicInteger(),
                running = new AtomicInteger(),
                maximum = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1),
                released = new CountDownLatch(1),
                parked = new CountDownLatch(1);
        try (Rwg2PoiPool<Integer> pool =
                new Rwg2PoiPool<>(
                        (seed, simplex) -> {
                            assertSame(
                                    "Prepare only on game thread", owner, Thread.currentThread());
                            return created.incrementAndGet();
                        })) {
            Rwg2PoiPool<Integer>.Job<Integer> first =
                    pool.submit(
                            1,
                            false,
                            context -> {
                                assertNotSame(owner, Thread.currentThread());
                                maximum.set(Math.max(maximum.get(), running.incrementAndGet()));
                                entered.countDown();
                                try {
                                    released.await();
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                } finally {
                                    running.decrementAndGet();
                                }
                                return context;
                            },
                            () -> {});
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Rwg2PoiPool<Integer>.Job<Integer> second =
                    pool.submit(
                            1,
                            false,
                            context -> {
                                maximum.set(Math.max(maximum.get(), running.incrementAndGet()));
                                running.decrementAndGet();
                                return context;
                            },
                            () -> {});
            assertNull(pool.submit(2, true, context -> context, parked::countDown));
            assertEquals(2, created.get());
            first.cancel();
            assertTrue(parked.await(5, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(2), second.result.get(5, TimeUnit.SECONDS));
            assertTrue(first.result.isCancelled());
            assertEquals(1, maximum.get());
            // A different seed/mode must never inherit the earlier context's caches.
            Rwg2PoiPool<Integer>.Job<Integer> other =
                    pool.submit(2, true, context -> context, () -> {});
            assertEquals(Integer.valueOf(3), other.result.get(5, TimeUnit.SECONDS));
        } finally {
            released.countDown();
        }
    }
}
