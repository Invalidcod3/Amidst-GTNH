package amidst.threading;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Cooperative cancellation for synchronous work spanning loaders and network calls.
 * A task owns its buffers until run() returns; cancellation never interrupts a
 * game-thread query or lets another task reuse those buffers early.
 */
public final class TaskCancellation {
	private static final ThreadLocal<BooleanSupplier> CURRENT = new ThreadLocal<>();

	private TaskCancellation() {
	}

	public static void run(BooleanSupplier cancelled, Runnable work) {
		BooleanSupplier previous = CURRENT.get();
		CURRENT.set(cancelled);
		try {
			check();
			work.run();
			check();
		} finally {
			if (previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(previous);
			}
		}
	}

	public static void check() {
		BooleanSupplier cancelled = CURRENT.get();
		if (cancelled != null && (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())) {
			throw new CancellationException("Task is no longer needed by the current view");
		}
	}
}
