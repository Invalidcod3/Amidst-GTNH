package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.world.biome.BiomeGenBase;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class Rwg2SamplingPoolTest {
    private abstract static class Sampler implements SurfaceBiomeSampler {
        public BiomeGenBase getBiomeAt(int x, int z) {
            return getPredictedBiomeAt(x, z);
        }

        public boolean isVillageLocationViable(int x, int z) {
            throw new AssertionError();
        }

        public float getApproximateSurfaceHeight(int x, int z) {
            throw new AssertionError();
        }

        public net.minecraft.world.ChunkPosition findSpawnBiomePosition(java.util.Random random) {
            throw new AssertionError();
        }
    }

    @Test(timeout = 7000)
    public void eightActualSamplersRunTogetherWithGameThreadPreparation() throws Exception {
        Thread game = Thread.currentThread();
        CountDownLatch entered = new CountDownLatch(8), release = new CountDownLatch(1);
        AtomicInteger created = new AtomicInteger();
        try (Rwg2SamplingPool pool =
                new Rwg2SamplingPool(
                        (seed, simplex) -> {
                            assertSame(game, Thread.currentThread());
                            created.incrementAndGet();
                            return new Sampler() {
                                final AtomicBoolean active = new AtomicBoolean();

                                public BiomeGenBase getPredictedBiomeAt(int x, int z) {
                                    assertNotSame(game, Thread.currentThread());
                                    assertTrue(
                                            "Each mutable sampler has exactly one owner",
                                            active.compareAndSet(false, true));
                                    try {
                                        assertEquals(-62, x);
                                        assertEquals(34, z); // Native quarter-pixel centre.
                                        entered.countDown();
                                        assertTrue(release.await(3, TimeUnit.SECONDS));
                                        return BiomeGenBase.forest;
                                    } catch (InterruptedException e) {
                                        throw new AssertionError(e);
                                    } finally {
                                        active.set(false);
                                    }
                                }
                            };
                        },
                        8)) {
            Rwg2SamplingPool.Job[] jobs = new Rwg2SamplingPool.Job[8];
            for (int i = 0; i < jobs.length; i++)
                jobs[i] = pool.submit(7, false, -64, 32, 1, 1, 4, 8, () -> {});
            assertTrue(
                    "Sampling itself must overlap, not merely socket waits",
                    entered.await(3, TimeUnit.SECONDS));
            assertEquals(8, created.get());
            release.countDown();
            for (Rwg2SamplingPool.Job job : jobs)
                assertArrayEquals(new int[] {4}, job.result.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test(timeout = 7000)
    public void cancellationNeverReusesAnExecutingSamplerAndRemovesQueuedWork() throws Exception {
        AtomicInteger created = new AtomicInteger(), calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                retired = new CountDownLatch(1);
        try (Rwg2SamplingPool pool =
                new Rwg2SamplingPool(
                        (seed, simplex) -> {
                            int context = created.incrementAndGet();
                            return new Sampler() {
                                public BiomeGenBase getPredictedBiomeAt(int x, int z) {
                                    calls.incrementAndGet();
                                    if (context == 1) {
                                        entered.countDown();
                                        try {
                                            assertTrue(release.await(3, TimeUnit.SECONDS));
                                        } catch (InterruptedException e) {
                                            throw new AssertionError(e);
                                        }
                                    }
                                    return BiomeGenBase.forest;
                                }
                            };
                        },
                        1)) {
            Rwg2SamplingPool.Job running =
                    pool.submit(1, false, 0, 0, 128, 128, 4, 1, retired::countDown);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            CountDownLatch capacity = new CountDownLatch(1);
            Runnable wake = capacity::countDown;
            Rwg2SamplingPool.Job successor = pool.submit(1, false, 0, 0, 1, 1, 4, 64, () -> {});
            assertNotNull(
                    "One successor can start without waiting for another game tick", successor);
            for (int i = 0; i < 64; i++) assertNull(pool.submit(1, false, 0, 0, 1, 1, 4, 64, wake));
            // A disconnected waiter must not consume the only wakeup and starve the rest.
            assertNull(pool.submit(1, false, 0, 0, 1, 1, 4, 64, () -> {}));
            assertEquals(
                    "A burst needs only the running context and its successor", 2, created.get());
            running.cancel();
            assertNull(
                    "Cancellation cannot lend a context while its native callback runs",
                    pool.submit(1, false, 0, 0, 1, 1, 4, 1, wake));
            release.countDown();
            assertTrue(retired.await(2, TimeUnit.SECONDS));
            assertTrue(capacity.await(2, TimeUnit.SECONDS));
            assertEquals(4, successor.result.get(2, TimeUnit.SECONDS)[0]);
            Rwg2SamplingPool.Job next = pool.submit(1, false, 0, 0, 1, 1, 4, 1, () -> {});
            assertNotNull(next);
            assertEquals(4, next.result.get(2, TimeUnit.SECONDS)[0]);
            assertEquals("The completed context must be reused", 2, created.get());
            assertEquals("No cancelled job may finish the rest of its raster", 3, calls.get());
            assertTrue(running.result.isCancelled());
        } finally {
            release.countDown();
        }
    }
}
