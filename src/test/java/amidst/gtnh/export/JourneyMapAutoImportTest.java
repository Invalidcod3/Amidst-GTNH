package amidst.gtnh.export;

import static org.junit.Assert.*;

import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;
import amidst.threading.WorkerExecutor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import javax.swing.SwingUtilities;

public class JourneyMapAutoImportTest {
    private static final GtnhMapWaypoint TARGET =
            new GtnhMapWaypoint(
                    Dimension.NETHER, new GtnhWaypoint("Fortress", -137, 64, 259, 12, 34, 56));

    @Test
    public void disabledDoesNothingAndImportRunsOffSwingWithCapturedDimension() throws Exception {
        QueuedExecutor queue = new QueuedExecutor();
        var enabled = Setting.createDummy(false);
        List<GtnhMapWaypoint> saved = new ArrayList<>();
        List<GtnhMapWaypoint> successes = new ArrayList<>();
        try (var imports =
                new JourneyMapAutoImport(
                        enabled,
                        new WorkerExecutor(queue),
                        (dimension, waypoints) -> {
                            assertFalse(SwingUtilities.isEventDispatchThread());
                            saved.add(new GtnhMapWaypoint(dimension, waypoints.get(0)));
                            return 1;
                        },
                        target -> {
                            assertTrue(SwingUtilities.isEventDispatchThread());
                            successes.add(target);
                        },
                        failure -> fail(failure.toString()))) {
            SwingUtilities.invokeAndWait(() -> imports.add(TARGET));
            assertTrue(queue.tasks.isEmpty());
            SwingUtilities.invokeAndWait(
                    () -> {
                        enabled.set(true);
                        imports.add(TARGET);
                        imports.add(TARGET);
                        imports.add(null);
                    });
            assertEquals(
                    "Repeated clicks while pending must not duplicate the import",
                    1,
                    queue.tasks.size());
            assertTrue(saved.isEmpty());
            queue.runNext();
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(List.of(TARGET), saved);
            assertEquals(List.of(TARGET), successes);
        }
    }

    @Test
    public void disablingOrClosingBeforeDispatchCancelsQueuedWork() throws Exception {
        QueuedExecutor queue = new QueuedExecutor();
        var enabled = Setting.createDummy(true);
        var imports =
                new JourneyMapAutoImport(
                        enabled,
                        new WorkerExecutor(queue),
                        (d, w) -> {
                            fail("Cancelled work must not reach the game");
                            return 0;
                        },
                        target -> fail("No success expected"),
                        failure -> fail(failure.toString()));
        SwingUtilities.invokeAndWait(
                () -> {
                    imports.add(TARGET);
                    enabled.set(false);
                });
        queue.runNext();
        SwingUtilities.invokeAndWait(
                () -> {
                    enabled.set(true);
                    imports.add(TARGET);
                    imports.close();
                });
        queue.runNext();
        SwingUtilities.invokeAndWait(() -> {});
    }

    @Test
    public void failureIsReportedAndAllowsRetry() throws Exception {
        QueuedExecutor queue = new QueuedExecutor();
        List<Exception> errors = new ArrayList<>();
        try (var imports =
                new JourneyMapAutoImport(
                        Setting.createDummy(true),
                        new WorkerExecutor(queue),
                        (d, w) -> {
                            throw new IllegalStateException("Worker disconnected");
                        },
                        target -> fail("A failed import is not a success"),
                        errors::add)) {
            for (int attempt = 0; attempt < 2; attempt++) {
                SwingUtilities.invokeAndWait(() -> imports.add(TARGET));
                queue.runNext();
                SwingUtilities.invokeAndWait(() -> {});
            }
            assertEquals(2, errors.size());
        }
    }

    private static class QueuedExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();

        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            tasks.remove(0).run();
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return false;
        }
    }
}
