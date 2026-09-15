package amidst.gtnh.export;

import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;
import amidst.threading.WorkerExecutor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Called on Swing; imports run in the background with an immutable click snapshot. */
public final class JourneyMapAutoImport implements AutoCloseable {
    @FunctionalInterface
    public interface Importer {
        int importWaypoints(Dimension dimension, List<GtnhWaypoint> waypoints) throws Exception;
    }

    private final Setting<Boolean> enabled;
    private final WorkerExecutor executor;
    private final Importer importer;
    private final Consumer<GtnhMapWaypoint> onSuccess;
    private final Consumer<Exception> onFailure;
    private final Set<GtnhMapWaypoint> pending = new HashSet<>();
    private volatile boolean closed;

    public JourneyMapAutoImport(
            Setting<Boolean> enabled,
            WorkerExecutor executor,
            Importer importer,
            Consumer<GtnhMapWaypoint> onSuccess,
            Consumer<Exception> onFailure) {
        this.enabled = enabled;
        this.executor = executor;
        this.importer = importer;
        this.onSuccess = onSuccess;
        this.onFailure = onFailure;
    }

    public void add(GtnhMapWaypoint target) {
        if (closed || !enabled.get() || target == null || !pending.add(target)) return;
        executor.run(
                () -> {
                    if (closed || !enabled.get()) return false;
                    if (importer.importWaypoints(target.dimension(), List.of(target.waypoint()))
                            != 1)
                        throw new IllegalStateException("JourneyMap did not save the waypoint.");
                    return true;
                },
                imported -> {
                    pending.remove(target);
                    if (!closed && imported) onSuccess.accept(target);
                },
                failure -> {
                    pending.remove(target);
                    if (!closed) onFailure.accept(failure);
                });
    }

    @Override
    public void close() {
        closed = true;
        pending.clear();
    }
}
