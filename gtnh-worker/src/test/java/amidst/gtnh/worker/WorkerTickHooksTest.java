package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import amidst.gtnh.worker.core.IntegratedServerTickTransformer;
import amidst.gtnh.worker.core.IntegratedServerTickTransformerTest;

public class WorkerTickHooksTest {
    @After
    public void unregisterWorker() {
        WorkerTickHooks.setWorker(null);
    }

    @Test
    public void transformedPausedLoopServesTcpOnServerThreadWithoutAdvancingWorld() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = reservation.getLocalPort();
        }
        ExecutorService client = Executors.newSingleThreadExecutor();
        ExecutorService serverLoop = Executors.newSingleThreadExecutor();
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "")) {
            assertTrue(worker.start());
            WorkerTickHooks.setWorker(worker);
            Object fixture = transformedFixture();
            Class<?> type = fixture.getClass();
            Method tick = type.getMethod("tick");
            type.getField("forgeEndTick").set(fixture, (Runnable) worker::onForgeTickEnd);
            Thread serverThread = serverLoop.submit(() -> Thread.currentThread()).get(3, TimeUnit.SECONDS);
            AtomicInteger completedProbes = new AtomicInteger();
            int expectedWorldTicks = 0;
            for (boolean paused : new boolean[] { true, false, true, false }) {
                type.getField("paused").setBoolean(fixture, paused);
                Future<JsonObject> response = client.submit(() -> exchange(port));
                awaitQueuedRequest(worker);
                FutureTask<Thread> probe = enqueueProbe(worker, completedProbes);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                do {
                    Thread.sleep(55); // The real integrated-server loop advances at 20 Hz.
                    serverLoop.submit(() -> {
                        tick.invoke(fixture);
                        return null;
                    }).get(3, TimeUnit.SECONDS);
                    if (!paused) expectedWorldTicks++;
                } while ((!response.isDone() || !probe.isDone()) && System.nanoTime() < deadline);
                assertTrue(response.get(3, TimeUnit.SECONDS).get("ok").getAsBoolean());
                assertSame(serverThread, probe.get(3, TimeUnit.SECONDS));
                assertEquals(expectedWorldTicks, type.getField("worldTicks").getInt(fixture));
            }
            assertEquals("Forge END plus the injected hook must not repeat a query", 4, completedProbes.get());
        } finally {
            WorkerTickHooks.setWorker(null);
            client.shutdownNow();
            serverLoop.shutdownNow();
        }
    }

    @Test
    public void hookCanRunBeforeWorkerStartsAndAfterItStops() throws Exception {
        try (BiomeWorkerServer worker = new BiomeWorkerServer(0, "")) {
            FutureTask<Thread> probe = enqueueProbe(worker, new AtomicInteger());
            WorkerTickHooks.setWorker(null);
            WorkerTickHooks.onIntegratedServerTickEnd();
            assertFalse(probe.isDone());
            WorkerTickHooks.setWorker(worker);
            WorkerTickHooks.onIntegratedServerTickEnd();
            assertSame(Thread.currentThread(), probe.get(1, TimeUnit.SECONDS));
            WorkerTickHooks.setWorker(null);
            FutureTask<Thread> stopped = enqueueProbe(worker, new AtomicInteger());
            WorkerTickHooks.onIntegratedServerTickEnd();
            assertFalse(stopped.isDone());
        }
    }

    @Test
    public void restartingWorkerPublishesOnlyCurrentQueue() throws Exception {
        try (BiomeWorkerServer previous = new BiomeWorkerServer(0, "");
                BiomeWorkerServer current = new BiomeWorkerServer(0, "")) {
            FutureTask<Thread> oldProbe = enqueueProbe(previous, new AtomicInteger());
            FutureTask<Thread> newProbe = enqueueProbe(current, new AtomicInteger());
            WorkerTickHooks.setWorker(previous);
            WorkerTickHooks.setWorker(current);
            WorkerTickHooks.onIntegratedServerTickEnd();
            assertFalse(oldProbe.isDone());
            assertSame(Thread.currentThread(), newProbe.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    public void outerHookDoesNotSpendAnotherBudgetAfterForgeEnd() throws Exception {
        try (BiomeWorkerServer worker = new BiomeWorkerServer(0, "")) {
            worker.beginGameTick();
            worker.onForgeTickEnd();
            FutureTask<Thread> late = enqueueProbe(worker, new AtomicInteger());
            worker.onIntegratedTickEnd();
            assertFalse(late.isDone());
            worker.onIntegratedTickEnd(); // Next paused tick has no Forge events.
            assertSame(Thread.currentThread(), late.get(1, TimeUnit.SECONDS));
        }
    }

    private static Object transformedFixture() throws Exception {
        String name = IntegratedServerTickTransformerTest.FIXTURE;
        byte[] bytes = new IntegratedServerTickTransformer().transform(name,
                IntegratedServerTickTransformerTest.TARGET,
                IntegratedServerTickTransformerTest.readClass(name));
        class FixtureLoader extends ClassLoader {
            FixtureLoader() { super(WorkerTickHooksTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }
        return new FixtureLoader().define().getConstructor().newInstance();
    }

    // Test-only queue probes observe the real execution thread without adding
    // a diagnostic wire command or exposing the queue in the production API.
    @SuppressWarnings("unchecked")
    private static BlockingQueue<FutureTask<?>> queue(BiomeWorkerServer worker) throws Exception {
        Field field = BiomeWorkerServer.class.getDeclaredField("serverThreadQueries");
        field.setAccessible(true);
        return (BlockingQueue<FutureTask<?>>) field.get(worker);
    }

    private static FutureTask<Thread> enqueueProbe(BiomeWorkerServer worker, AtomicInteger count) throws Exception {
        FutureTask<Thread> probe = new FutureTask<>(() -> {
            count.incrementAndGet();
            return Thread.currentThread();
        });
        queue(worker).add(probe);
        return probe;
    }

    private static void awaitQueuedRequest(BiomeWorkerServer worker) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (queue(worker).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertFalse("TCP request was never queued", queue(worker).isEmpty());
    }

    private static JsonObject exchange(int port) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write("{\"protocol\":25,\"command\":\"world_state\"}\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return new JsonParser().parse(reader.readLine()).getAsJsonObject();
        }
    }
}
