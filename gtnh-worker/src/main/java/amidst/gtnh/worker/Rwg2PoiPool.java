package amidst.gtnh.worker;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Bounded idle-expiring POI threads, independent of terrain workers; contexts are prepared on the
 * game thread.
 */
final class Rwg2PoiPool<C> implements AutoCloseable {
    interface Factory<C> {
        C create(long seed, boolean simplex);
    }

    private final Factory<C> factory;
    private final int maximum;
    private final ThreadPoolExecutor executor;
    private final Set<Job<?>> jobs = new HashSet<>();
    private final Deque<Context> idle = new ArrayDeque<>();
    private final Set<Runnable> waiters = new LinkedHashSet<>();
    private boolean closed;

    Rwg2PoiPool(Factory<C> factory) {
        this(factory, Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 2)));
    }

    Rwg2PoiPool(Factory<C> factory, int maximum) {
        this.factory = factory;
        this.maximum = Math.max(1, Math.min(4, maximum));
        executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        5L,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<Runnable>(5),
                        work -> {
                            Thread thread = new Thread(work, "GTNH RWG2 cave POI");
                            thread.setDaemon(true);
                            thread.setPriority(Thread.NORM_PRIORITY - 1);
                            return thread;
                        });
        executor.allowCoreThreadTimeOut(true);
    }

    /** The legacy entry point remains single-threaded; each running query owns its context. */
    synchronized <T> Job<T> submit(
            long seed, boolean simplex, Function<C, T> work, Runnable completed) {
        return submit(seed, simplex, 1, work, completed);
    }

    synchronized <T> Job<T> submit(
            long seed,
            boolean simplex,
            int requestedThreads,
            Function<C, T> work,
            Runnable completed) {
        if (closed) throw new IllegalStateException("RWG2 POI pool is closed");
        int count = Math.max(1, Math.min(maximum, requestedThreads));
        if (count > executor.getMaximumPoolSize()) executor.setMaximumPoolSize(count);
        executor.setCorePoolSize(count);
        if (count < executor.getMaximumPoolSize()) executor.setMaximumPoolSize(count);
        // One prepared successor at most, regardless of the number of waiting sockets.
        if (jobs.size() >= count + 1) {
            waiters.add(completed);
            return null;
        }
        Context context = null;
        for (Iterator<Context> i = idle.iterator(); i.hasNext(); ) {
            Context candidate = i.next();
            if (candidate.seed == seed && candidate.simplex == simplex) {
                context = candidate;
                i.remove();
                break;
            }
        }
        if (context == null) context = new Context(seed, simplex, factory.create(seed, simplex));
        Job<T> job = new Job<>(context, work, completed);
        jobs.add(job);
        try {
            executor.execute(job);
        } catch (RuntimeException e) {
            job.cancel();
            throw e;
        }
        return job;
    }

    private synchronized void release(Job<?> job) {
        jobs.remove(job);
        if (!closed) {
            idle.addFirst(job.context);
            while (idle.size() > maximum + 1) idle.removeLast();
            List<Runnable> wake = new ArrayList<>(waiters);
            waiters.clear();
            for (Runnable callback : wake) callback.run();
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        waiters.clear();
        for (Job<?> job : new ArrayList<>(jobs)) job.cancel();
        idle.clear();
        executor.shutdownNow();
    }

    private final class Context {
        final long seed;
        final boolean simplex;
        final C value;

        Context(long seed, boolean simplex, C value) {
            this.seed = seed;
            this.simplex = simplex;
            this.value = value;
        }
    }

    final class Job<T> implements Runnable {
        final CompletableFuture<T> result = new CompletableFuture<>();
        private final Context context;
        private final Function<C, T> work;
        private final Runnable completed;
        private final AtomicInteger state = new AtomicInteger();
        private volatile Thread runner;

        Job(Context context, Function<C, T> work, Runnable completed) {
            this.context = context;
            this.work = work;
            this.completed = completed;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(0, 1)) return;
            synchronized (this) {
                runner = Thread.currentThread();
            }
            try {
                if (result.isCancelled()) throw new CancellationException();
                result.complete(work.apply(context.value));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            } finally {
                synchronized (this) {
                    runner = null;
                    Thread.interrupted();
                }
                state.set(2);
                release(this);
                completed.run();
            }
        }

        void cancel() {
            if (!result.cancel(false)) return;
            if (state.compareAndSet(0, 2)) {
                executor.remove(this);
                release(this);
                completed.run();
            } else {
                synchronized (this) {
                    if (runner != null) runner.interrupt();
                }
            }
        }
    }
}
