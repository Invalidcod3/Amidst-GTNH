package amidst.gtnh.worker;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import amidst.mojangapi.world.Dimension;

/** Debounced, single-flight exact-block lookup; never waits for a socket on the EDT. */
public final class GtnhCursorBiomeLookup {

	@FunctionalInterface
	public interface Query {
		String get(Dimension dimension, int x, int z) throws Exception;
	}

	private record Position(Dimension dimension, int x, int z) {}
	private static final long DEBOUNCE_NANOS = 150_000_000L;
	private static final long REFRESH_NANOS = 1_000_000_000L;
	private static final long RETRY_NANOS = 5_000_000_000L;
	private final Query query;
	private final Consumer<Runnable> executor;
	private final LongSupplier clock;
	private Position target;
	private Position pendingPosition;
	private FutureTask<String> pending;
	private long generation;
	private long pendingGeneration;
	private long changedAt;
	private long nextQuery;
	private String result;
	private boolean closed;

	public GtnhCursorBiomeLookup(Query query, Consumer<Runnable> executor) {
		this(query, executor, System::nanoTime);
	}

	GtnhCursorBiomeLookup(Query query, Consumer<Runnable> executor, LongSupplier clock) {
		this.query = query;
		this.executor = executor;
		this.clock = clock;
	}

	public synchronized String poll(Dimension dimension, int x, int z) {
		if (closed) return null;
		long now = clock.getAsLong();
		Position position = new Position(dimension, x, z);
		if (!position.equals(target)) {
			target = position;
			changedAt = now;
			nextQuery = now;
			result = null;
		}
		if (pending != null && pending.isDone()) {
			boolean current = generation == pendingGeneration && target.equals(pendingPosition);
			try {
				String value = pending.get();
				if (current) {
					result = value;
					nextQuery = now + REFRESH_NANOS;
				}
			} catch (ExecutionException | java.util.concurrent.CancellationException failure) {
				if (current) {
					result = null;
					nextQuery = now + RETRY_NANOS;
				}
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				if (current) nextQuery = now + RETRY_NANOS;
			} finally {
				pending = null;
			}
		}
		if (pending == null && now - changedAt >= DEBOUNCE_NANOS && now >= nextQuery) {
			pendingPosition = target;
			pendingGeneration = generation;
			Position requested = target;
			pending = new FutureTask<>(() -> query.get(requested.dimension(), requested.x(), requested.z()));
			try {
				executor.accept(pending);
			} catch (java.util.concurrent.RejectedExecutionException stopping) {
				pending = null;
				result = null;
				nextQuery = now + RETRY_NANOS;
			}
		}
		return result;
	}

	public synchronized void invalidate() {
		generation++;
		result = null;
		nextQuery = clock.getAsLong();
	}

	public synchronized void close() {
		closed = true;
		result = null;
		if (pending != null) pending.cancel(true);
		pending = null;
	}
}
