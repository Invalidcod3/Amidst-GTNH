package amidst.gtnh.worker;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Game-thread preparation, exclusively leased samplers, background arithmetic only. */
final class Rwg2SamplingPool implements AutoCloseable {
    interface Factory {
        SurfaceBiomeSampler create(long seed, boolean simplex);
    }

    private final Factory factory;
    private final int maximum;
    private final ThreadPoolExecutor executor;
    private final Deque<Context> idle = new ArrayDeque<>();
    private final Set<Job> jobs = new HashSet<>();
    private final Set<Runnable> capacityWaiters = new LinkedHashSet<>();
    private boolean closed;

    Rwg2SamplingPool() {
        this(
                Rwg2SurfaceBiomeSampler::new,
                Math.max(
                        1,
                        Math.min(
                                64,
                                Integer.getInteger(
                                        "gtnh.amidst.worker.rwg2Threads",
                                        Math.max(
                                                1,
                                                Runtime.getRuntime().availableProcessors() - 2)))));
    }

    Rwg2SamplingPool(Factory factory, int maximum) {
        this.factory = factory;
        this.maximum = Math.max(1, Math.min(64, maximum));
        AtomicInteger sequence = new AtomicInteger();
        executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        5L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<Runnable>(128),
                        work -> {
                            Thread thread =
                                    new Thread(
                                            work,
                                            "GTNH RWG2 sampling " + sequence.incrementAndGet());
                            thread.setDaemon(true);
                            thread.setPriority(Thread.NORM_PRIORITY - 1);
                            return thread;
                        });
        executor.allowCoreThreadTimeOut(true);
    }

    /** Called only on the game thread; constructors never run on a sampling thread. */
    synchronized Job submit(
            long seed,
            boolean simplex,
            int x,
            int z,
            int width,
            int height,
            int step,
            int requestedThreads,
            Runnable completed) {
        if (closed) throw new IllegalStateException("RWG2 sampling pool is closed");
        int count = Math.min(maximum, Math.max(1, requestedThreads));
        if (count > executor.getMaximumPoolSize()) executor.setMaximumPoolSize(count);
        executor.setCorePoolSize(count);
        if (count < executor.getMaximumPoolSize()) executor.setMaximumPoolSize(count);
        // Do not construct a heavyweight sampler for every socket waiting in a burst.
        // Park excess requests; a released context wakes them on the game thread.
        // At most two prepared successors keep the CPU busy between game ticks.
        if (jobs.size() >= contextLimit(count)) {
            capacityWaiters.add(completed);
            return null;
        }
        Context context = null;
        for (Iterator<Context> iterator = idle.iterator(); iterator.hasNext(); ) {
            Context candidate = iterator.next();
            if (candidate.seed == seed && candidate.simplex == simplex) {
                iterator.remove();
                context = candidate;
                break;
            }
        }
        if (context == null) context = new Context(seed, simplex, factory.create(seed, simplex));
        Job job = new Job(context, x, z, width, height, step, completed);
        jobs.add(job);
        try {
            executor.execute(job);
        } catch (RuntimeException failure) {
            job.cancel();
            throw failure;
        }
        return job;
    }

    private synchronized void release(Job job) {
        jobs.remove(job);
        if (!closed) {
            idle.addFirst(job.context);
            while (idle.size() > contextLimit(maximum)) idle.removeLast();
            // Wake all registered requests: the first callback may already be cancelled.
            // Re-admission remains bounded and reuses this idle context before constructing one.
            List<Runnable> waiting = new ArrayList<>(capacityWaiters);
            capacityWaiters.clear();
            for (Runnable wake : waiting) wake.run();
        }
    }

    private static int contextLimit(int threads) {
        return Math.min(64, threads + Math.min(2, threads));
    }

    @Override
    public synchronized void close() {
        closed = true;
        capacityWaiters.clear();
        for (Job job : new ArrayList<>(jobs)) job.cancel();
        idle.clear();
        executor.shutdownNow();
    }

    private static final class Context {
        final long seed;
        final boolean simplex;
        final SurfaceBiomeSampler sampler;

        Context(long seed, boolean simplex, SurfaceBiomeSampler sampler) {
            this.seed = seed;
            this.simplex = simplex;
            this.sampler = sampler;
        }
    }

    final class Job implements Runnable {
        final CompletableFuture<int[]> result = new CompletableFuture<>();
        private final AtomicInteger state = new AtomicInteger(); // queued / running / released
        private final Context context;
        private final int x, z, width, height, step;
        private final Runnable completed;
        volatile double computeMillis;

        Job(Context context, int x, int z, int width, int height, int step, Runnable completed) {
            this.context = context;
            this.x = x;
            this.z = z;
            this.width = width;
            this.height = height;
            this.step = step;
            this.completed = completed;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) return;
            long start = System.nanoTime();
            try {
                int[] ids = new int[Math.multiplyExact(width, height)];
                int offset = step == 4 ? 2 : 0;
                for (int row = 0; row < height; row++)
                    for (int column = 0; column < width; column++) {
                        if (result.isCancelled() || Thread.currentThread().isInterrupted())
                            throw new CancellationException();
                        ids[row * width + column] =
                                context.sampler.getPredictedBiomeAt(
                                                x + column * step + offset, z + row * step + offset)
                                        .biomeID;
                    }
                result.complete(ids);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            } finally {
                computeMillis = (System.nanoTime() - start) / 1e6;
                state.set(2);
                release(this);
                completed.run();
            }
        }

        void cancel() {
            result.cancel(false);
            if (state.compareAndSet(0, 2)) {
                executor.remove(this);
                release(this);
                completed.run();
            }
            // Running contexts stay leased until their current native chunk returns.
        }
    }
}
