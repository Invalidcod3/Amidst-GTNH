package amidst.gtnh.worker;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;

/** One background world-state request at a time; the fragment scheduler never waits on its socket. */
public final class GtnhWorldStatePoller {
	private static final long POLL_NANOS = 1_000_000_000L;
	private static final long RETRY_NANOS = 5_000_000_000L;
	private final Query query;
	private final Consumer<Runnable> executor;
	private final LongSupplier clock;
	private FutureTask<GtnhWorldState> pending;
	private long revision = -1L;
	private long nextPoll;
	private boolean closed;

	@FunctionalInterface
	public interface Query {
		GtnhWorldState get(long sinceRevision) throws MinecraftInterfaceException;
	}

	public GtnhWorldStatePoller(Query query, Consumer<Runnable> executor) {
		this(query, executor, System::nanoTime);
	}

	GtnhWorldStatePoller(Query query, Consumer<Runnable> executor, LongSupplier clock) {
		this.query = query;
		this.executor = executor;
		this.clock = clock;
	}

	/** Returns only completed changes; the first response also refreshes tiles loaded before its baseline. */
	public synchronized GtnhWorldState poll() {
		if (closed) {
			return null;
		}
		if (pending != null) {
			if (!pending.isDone()) {
				return null;
			}
			try {
				GtnhWorldState state = pending.get();
				boolean baseline = revision < 0;
				revision = state.revision();
				nextPoll = clock.getAsLong() + POLL_NANOS;
				// Tiles can now load while the initial poll is outstanding. Refresh
				// them once so changes before this baseline are not silently missed.
				return baseline ? new GtnhWorldState(revision, true, new int[0], new int[0]) : state;
			} catch (ExecutionException e) {
				nextPoll = clock.getAsLong() + RETRY_NANOS;
				AmidstLogger.warn(e.getCause(), "Unable to read GTNH overworld chunk state");
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				nextPoll = clock.getAsLong() + RETRY_NANOS;
			} finally {
				pending = null;
			}
		} else if (nextPoll == 0 || clock.getAsLong() >= nextPoll) {
			long sinceRevision = revision;
			pending = new FutureTask<>(() -> query.get(sinceRevision));
			executor.accept(pending);
		}
		return null;
	}

	public synchronized void close() {
		closed = true;
		if (pending != null) {
			pending.cancel(true);
			pending = null;
		}
	}
}
