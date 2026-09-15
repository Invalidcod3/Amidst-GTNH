package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Real TCP requests with an explicitly driven game-tick queue; no world boot required. */
public class BiomeWorkerServerTest {
    @Test(timeout=6000) public void allSocketWorkersRemainAvailableDuringColdCachePreparation() throws Exception {
        int port=freeIpv4Port();ExecutorService clients=Executors.newFixedThreadPool(4);
        java.util.ArrayDeque<Runnable> preparationTasks=new java.util.ArrayDeque<>();
        MapCachePreparation preparation=new MapCachePreparation(()->"test-environment",preparationTasks::add,()->1L,failure->{throw new AssertionError(failure);});
        try(BiomeWorkerServer worker=new BiomeWorkerServer(port,"",new MapCacheIdentity(preparation))) {
            java.lang.reflect.Field field=BiomeWorkerServer.class.getDeclaredField("arbitrarySeedSamplers");field.setAccessible(true);
            ((java.util.Map<Long,SurfaceBiomeSampler>)field.get(worker)).put(7L,new SurfaceBiomeSampler() {
                @Override public net.minecraft.world.biome.BiomeGenBase getBiomeAt(int x,int z){return net.minecraft.world.biome.BiomeGenBase.forest;}
                @Override public boolean isVillageLocationViable(int x,int z){throw new AssertionError();}
                @Override public float getApproximateSurfaceHeight(int x,int z){throw new AssertionError();}
                @Override public net.minecraft.world.ChunkPosition findSpawnBiomePosition(java.util.Random random){throw new AssertionError();}
            });
            assertTrue(worker.start());
            java.util.List<Future<JsonObject>> metadata=new java.util.ArrayList<>();
            for(int i=0;i<4;i++)metadata.add(clients.submit(()->exchange(port,
                    "{\"protocol\":25,\"command\":\"cache_context\",\"seed\":7,\"width\":1,\"height\":1,\"step\":1}")));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(metadata.stream().anyMatch(f->!f.isDone()) && System.nanoTime()<deadline){worker.executeQueuedQueries();Thread.sleep(5);}
            for(Future<JsonObject> pending:metadata) {
                JsonObject response=pending.get(1,TimeUnit.SECONDS);
                assertTrue(response.toString(),response.get("ok").getAsBoolean());assertFalse(response.has("cacheStamp"));
            }
            assertEquals(1,preparationTasks.size()); // Still pending: nothing waited for disk I/O.
            Future<JsonObject> tile=clients.submit(()->exchange(port,
                    "{\"protocol\":25,\"command\":\"biomes\",\"seed\":7,\"width\":1,\"height\":1,\"step\":1}"));
            while(!tile.isDone() && System.nanoTime()<deadline){worker.executeQueuedQueries();Thread.sleep(5);}
            JsonObject result=tile.get(1,TimeUnit.SECONDS);
            assertTrue(result.toString(),result.get("ok").getAsBoolean());assertEquals(4,result.getAsJsonArray("ids").get(0).getAsInt());
            preparationTasks.remove().run();assertEquals("test-environment",preparation.getIfReady());
        }finally{clients.shutdownNow();}
    }

    private static void driveBusyTick(BiomeWorkerServer worker) throws Exception {
        java.lang.reflect.Field field=BiomeWorkerServer.class.getDeclaredField("tickBudget");field.setAccessible(true);
        // One millisecond allowance makes the fairness assertion independent of
        // a socket response racing the rest of a 20 ms batch on Windows.
        ((WorkerTickBudget)field.get(worker)).begin(System.nanoTime()-50_000_000L);
        worker.executeQueuedQueries();
    }
    @Test public void largeBiomeQueryYieldsToTicksAndOtherClients() throws Exception {
        int port = freeIpv4Port();
        ExecutorService clients = Executors.newFixedThreadPool(2);
        java.util.concurrent.atomic.AtomicInteger samples = new java.util.concurrent.atomic.AtomicInteger();
        SurfaceBiomeSampler sampler = new SurfaceBiomeSampler() {
            @Override public net.minecraft.world.biome.BiomeGenBase getBiomeAt(int x, int z) { return getPredictedBiomeAt(x,z); }
            @Override public boolean isVillageLocationViable(int x, int z) { throw new AssertionError(); }
            @Override public float getApproximateSurfaceHeight(int x, int z) { throw new AssertionError(); }
            @Override public net.minecraft.world.ChunkPosition findSpawnBiomePosition(java.util.Random random) { throw new AssertionError(); }
            @Override public net.minecraft.world.biome.BiomeGenBase getPredictedBiomeAt(int x, int z) {
                samples.incrementAndGet();
                long until = System.nanoTime() + 300_000;
                while (System.nanoTime() < until) { /* Controlled CPU work; avoid Windows sleep rounding. */ }
                return net.minecraft.world.biome.BiomeGenBase.forest;
            }
        };
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "")) {
            java.lang.reflect.Field cacheField = BiomeWorkerServer.class.getDeclaredField("arbitrarySeedSamplers");
            cacheField.setAccessible(true);
            ((java.util.Map<Long, SurfaceBiomeSampler>) cacheField.get(worker)).put(7L, sampler);
            assertTrue(worker.start());
            Future<JsonObject> tile = clients.submit(() -> exchange(port,
                    "{\"protocol\":25,\"command\":\"biomes\",\"seed\":7,\"dimension\":0,\"x\":0,\"z\":0,\"width\":16,\"height\":8,\"step\":4,\"profile\":true}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (samples.get() == 0 && System.nanoTime() < deadline) {
                driveBusyTick(worker); Thread.sleep(5);
            }
            assertTrue(samples.get() > 0 && samples.get() < 128);
            assertFalse(tile.isDone());
            Future<JsonObject> state = clients.submit(() -> exchange(port,
                    "{\"protocol\":25,\"command\":\"world_state\"}"));
            while (!state.isDone() && System.nanoTime() < deadline) {
                driveBusyTick(worker); Thread.sleep(5);
            }
            assertTrue(state.get(1, TimeUnit.SECONDS).get("ok").getAsBoolean());
            assertFalse("A short request must pass a yielded tile", tile.isDone());
            while (!tile.isDone() && System.nanoTime() < deadline) {
                driveBusyTick(worker); Thread.sleep(5);
            }
            JsonObject result = tile.get(1, TimeUnit.SECONDS);
            assertTrue(result.toString(), result.get("ok").getAsBoolean());
            assertEquals(128, samples.get());
            assertEquals(128, result.getAsJsonArray("ids").size());
            assertTrue(result.get("slices").getAsInt() > 1);
            assertTrue(result.get("elapsedMillis").getAsDouble() > result.get("computeMillis").getAsDouble());
        } finally { clients.shutdownNow(); }
    }
    @Test
    public void profilingCanBeEnabledPerRequestWithoutRestartingWorker() throws Exception {
        int port = freeIpv4Port();
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "")) {
            assertTrue(worker.start());
            for (boolean profile : new boolean[] { true, false }) {
                Future<JsonObject> response = client.submit(() -> exchange(port,
                        "{\"protocol\":25,\"command\":\"world_state\",\"profile\":" + profile + "}"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!response.isDone() && System.nanoTime() < deadline) {
                    worker.executeQueuedQueries();
                    Thread.sleep(5);
                }
                JsonObject result = response.get(1, TimeUnit.SECONDS);
                assertTrue(result.get("ok").getAsBoolean());
                assertEquals(profile, result.has("queueMillis"));
                assertEquals(profile, result.has("computeMillis"));
                if (profile) {
                    assertTrue(result.get("queueMillis").getAsDouble() >= 0.0);
                    assertTrue(result.get("computeMillis").getAsDouble() >= 0.0);
                }
            }
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    public void servesIpv4RequestsEvenWhenJvmPrefersIpv6() throws Exception {
        int port = freeIpv4Port();
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "test-token")) {
            assertTrue(worker.start());
            worker.recordOverworldChunkChange(3, -4);
            Future<JsonObject> response = client.submit(() -> exchange(port,
                    "{\"protocol\":25,\"command\":\"world_state\",\"token\":\"test-token\",\"sinceRevision\":0}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!response.isDone() && System.nanoTime() < deadline) {
                worker.executeQueuedQueries();
                Thread.sleep(5);
            }
            JsonObject result = response.get(1, TimeUnit.SECONDS);
            assertTrue(result.get("ok").getAsBoolean());
            assertEquals(25, result.get("protocol").getAsInt());
            assertEquals(1, result.get("worldRevision").getAsLong());
            assertEquals(3, result.getAsJsonArray("chunkXs").get(0).getAsInt());
            assertEquals(-4, result.getAsJsonArray("chunkZs").get(0).getAsInt());
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    public void rejectsProtocolMismatchWithoutWaitingForGameTick() throws Exception {
        int port = freeIpv4Port();
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "")) {
            assertTrue(worker.start());
            JsonObject result = exchange(port, "{\"protocol\":17,\"command\":\"hello\"}");
            assertFalse(result.get("ok").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("unsupported protocol"));
        }
    }

    @Test
    public void rejectsWrongTokenWithoutWaitingForGameTick() throws Exception {
        int port = freeIpv4Port();
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "expected")) {
            assertTrue(worker.start());
            JsonObject result = exchange(port, "{\"protocol\":25,\"command\":\"hello\",\"token\":\"wrong\"}");
            assertFalse(result.get("ok").getAsBoolean());
            assertEquals("authentication failed", result.get("error").getAsString());
        }
    }

    @Test
    public void occupiedPortDoesNotThrowIntoMinecraftLifecycle() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                BiomeWorkerServer worker = new BiomeWorkerServer(occupied.getLocalPort(), "")) {
            assertFalse(worker.start());
        }
    }

    @Test
    public void comparisonRejectsMissingWorldInsteadOfTreatingPredictionsAsGroundTruth() throws Exception {
        int port = freeIpv4Port();
        ExecutorService client = Executors.newSingleThreadExecutor();
        try (BiomeWorkerServer worker = new BiomeWorkerServer(port, "")) {
            assertTrue(worker.start());
            Future<JsonObject> response = client.submit(() -> exchange(port,
                    "{\"protocol\":25,\"command\":\"compare_biomes\",\"dimension\":0,\"width\":1,\"height\":1,\"step\":1}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!response.isDone() && System.nanoTime() < deadline) {
                worker.executeQueuedQueries();
                Thread.sleep(5);
            }
            JsonObject result = response.get(1, TimeUnit.SECONDS);
            assertFalse(result.get("ok").getAsBoolean());
            assertTrue(result.get("error").getAsString().contains("requires a loaded RWG overworld"));
        } finally {
            client.shutdownNow();
        }
    }

    @Test
    public void invalidPortDoesNotThrowIntoMinecraftLifecycle() {
        try (BiomeWorkerServer worker = new BiomeWorkerServer(-1, "")) {
            assertFalse(worker.start());
        }
    }

    private static int freeIpv4Port() throws Exception {
        try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return reservation.getLocalPort();
        }
    }

    private static JsonObject exchange(int port, String request) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            writer.write(request + "\n");
            writer.flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            return new JsonParser().parse(reader.readLine()).getAsJsonObject();
        }
    }
}
