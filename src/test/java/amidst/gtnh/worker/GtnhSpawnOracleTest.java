package amidst.gtnh.worker;

import static org.junit.Assert.*;

import amidst.gtnh.structure.*;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.producer.SpawnProducer;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

public class GtnhSpawnOracleTest {
    @Test
    public void menuMapAndExportProducerShareLiveSnapshotIncludingHeightAndSource()
            throws Exception {
        AtomicReference<GtnhSpawnPoint> value =
                new AtomicReference<>(new GtnhSpawnPoint(1234, null, -800, "ESTIMATED"));
        GtnhSpawnOracle oracle = new GtnhSpawnOracle(value::get);
        SpawnProducer menu = new SpawnProducer(oracle);
        var map =
                new GtnhRoguelikeDungeonProducers(
                                new GtnhMinecraftInterface(
                                        new GtnhBiomeSource() {
                                            public GtnhWorkerInfo getWorkerInfo() {
                                                return new GtnhWorkerInfo(
                                                        PROTOCOL_VERSION,
                                                        0,
                                                        "RWG",
                                                        "provider",
                                                        "manager",
                                                        java.util.List.of(
                                                                new GtnhBiomeDescriptor(
                                                                        1, "Plains", 0x77aa55, 0.8F,
                                                                        0.4F, 0.1F, 0.2F)));
                                            }

                                            public int[] sampleBiomes(
                                                    long seed,
                                                    int dim,
                                                    int x,
                                                    int z,
                                                    int width,
                                                    int height,
                                                    int step) {
                                                return new int[width * height];
                                            }
                                        }),
                                71,
                                oracle)
                        .get(GtnhOverworldStructureType.WORLD_SPAWN);
        assertNull(menu.getFirstWorldIcon());
        assertTrue(menu.getWorldIcons().isEmpty());
        assertTrue(oracle.refresh());
        assertFalse(oracle.refresh());
        assertEquals(value.get().coordinates(), menu.getFirstWorldIcon().getCoordinates());
        assertEquals(value.get().label(), menu.getFirstWorldIcon().getName());
        value.set(new GtnhSpawnPoint(-1650, 78, 900, "RECORDED"));
        assertTrue(oracle.refresh());
        var icons = map.getAt(CoordinatesInWorld.from(-2048, 512), null);
        assertEquals(1, icons.size());
        assertEquals(menu.getFirstWorldIcon().getCoordinates(), icons.get(0).getCoordinates());
        assertEquals(Integer.valueOf(78), icons.get(0).getHeight());
        assertEquals(value.get().label(), icons.get(0).getName());
        oracle.invalidate();
        assertNull(menu.getFirstWorldIcon());
        assertTrue(map.getAt(CoordinatesInWorld.from(-2048, 512), null).isEmpty());
    }

    @Test(timeout = 5000)
    public void replyFromBeforeSaveInvalidationCannotRestoreOldMarker() throws Exception {
        CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
        GtnhSpawnOracle oracle =
                new GtnhSpawnOracle(
                        () -> {
                            started.countDown();
                            try {
                                finish.await();
                            } catch (InterruptedException e) {
                                throw new RuntimeException(e);
                            }
                            return new GtnhSpawnPoint(1100, 64, 700, "RECORDED");
                        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = executor.submit(oracle::refresh);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            oracle.invalidate();
            finish.countDown();
            assertFalse(pending.get(1, TimeUnit.SECONDS));
            assertNull(oracle.get());
            assertTrue(oracle.refresh());
            assertEquals(1100, oracle.get().getX());
        } finally {
            finish.countDown();
            executor.shutdownNow();
        }
    }
}
