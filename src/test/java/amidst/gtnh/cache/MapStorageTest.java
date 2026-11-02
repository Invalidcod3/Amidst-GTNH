package amidst.gtnh.cache;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class MapStorageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @After
    public void reset() {
        MapStorage.enabled = true;
    }

    @Test(timeout = 5000)
    public void slowDiskWriterCannotDelayReadsOrReturnedTerrainAndOwnsItsData() throws Exception {
        Path dir = temporary.newFolder().toPath();
        new MapStorage(dir).write("old", new int[] {3});
        CountDownLatch started = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        ExecutorService disk = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        MapStorage store =
                new MapStorage(
                        dir,
                        task ->
                                disk.execute(
                                        () -> {
                                            started.countDown();
                                            try {
                                                release.await();
                                                task.run();
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            } finally {
                                                finished.countDown();
                                            }
                                        }));
        try {
            int[] data = {8, 329};
            Future<?> published = caller.submit(() -> store.writeAsync("new", data));
            assertTrue(started.await(1, TimeUnit.SECONDS));
            published.get(1, TimeUnit.SECONDS); // Disk still blocked; the tile can already publish.
            data[0] = 99;
            assertArrayEquals(
                    new int[] {3},
                    caller.submit(() -> store.read("old", 1)).get(1, TimeUnit.SECONDS));
            int[] pending = store.read("new", 2);
            assertArrayEquals(new int[] {8, 329}, pending);
            pending[0] = 111;
            assertArrayEquals(new int[] {8, 329}, store.read("new", 2));
            release.countDown();
            assertTrue(finished.await(1, TimeUnit.SECONDS));
            assertArrayEquals(new int[] {8, 329}, new MapStorage(dir).read("new", 2));
        } finally {
            release.countDown();
            disk.shutdownNow();
            caller.shutdownNow();
        }
    }

    @Test
    public void fullQueueSkipsPersistenceAndDisabledQueuedWritesDoNotTouchDisk() throws Exception {
        Path dir = temporary.newFolder().toPath();
        ArrayDeque<Runnable> queue = new ArrayDeque<>();
        MapStorage store =
                new MapStorage(
                        dir,
                        task -> {
                            if (!queue.isEmpty()) throw new RejectedExecutionException();
                            queue.add(task);
                        });
        store.writeAsync("accepted", new int[] {1});
        store.writeAsync("dropped", new int[] {2});
        assertEquals(1, queue.size());
        assertArrayEquals(new int[] {1}, store.read("accepted", 1));
        assertNull(store.read("dropped", 1));
        MapStorage.enabled = false;
        queue.remove().run();
        MapStorage.enabled = true;
        assertNull(store.read("accepted", 1));
        try (var entries = Files.list(dir)) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    public void evictionUsesOldestFilesAndIgnoresTemporaryAndUnrelatedEntries() throws Exception {
        Path dir = temporary.newFolder().toPath();
        MapStorage store = new MapStorage(dir);
        for (int i = 0; i < 4; i++) {
            store.write("tile-" + i, new int[] {i});
            Files.setLastModifiedTime(
                    dir.resolve(MapStorage.hash("tile-" + i) + ".bin"),
                    java.nio.file.attribute.FileTime.fromMillis(1000L + i));
        }
        Files.writeString(dir.resolve("tile-in-progress.tmp"), "keep");
        Files.writeString(dir.resolve("notes.txt"), "keep");
        Files.createDirectory(dir.resolve(MapStorage.hash("directory") + ".bin"));
        store.trim(40); // Two complete 20-byte cache entries.
        assertNull(store.read("tile-0", 1));
        assertNull(store.read("tile-1", 1));
        assertArrayEquals(new int[] {2}, store.read("tile-2", 1));
        assertArrayEquals(new int[] {3}, store.read("tile-3", 1));
        assertEquals("keep", Files.readString(dir.resolve("notes.txt")));
        assertEquals("keep", Files.readString(dir.resolve("tile-in-progress.tmp")));
        assertTrue(Files.isDirectory(dir.resolve(MapStorage.hash("directory") + ".bin")));
    }

    @Test
    public void persistsAndIsolatesWorldAndDimensionKeys() throws Exception {
        Path dir = temporary.newFolder().toPath();
        new MapStorage(dir).write("save-A/pluto", new int[] {3, 7, 9});
        MapStorage restored = new MapStorage(dir);
        assertArrayEquals(new int[] {3, 7, 9}, restored.read("save-A/pluto", 3));
        assertNull(restored.read("save-B/pluto", 3));
        assertNull(restored.read("save-A/moon", 3));
        assertNull(restored.read("save-A/pluto", 4));
    }

    @Test
    public void corruptionAndTruncationAreCacheMisses() throws Exception {
        Path dir = temporary.newFolder().toPath();
        MapStorage store = new MapStorage(dir);
        store.write("tile", new int[] {4, 7});
        Path file = dir.resolve(MapStorage.hash("tile") + ".bin");
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 1;
        Files.write(file, bytes);
        assertNull(store.read("tile", 2));
        Files.write(file, new byte[4]);
        assertNull(store.read("tile", 2));
        store.write("tile", new int[] {8, 9});
        assertArrayEquals(new int[] {8, 9}, store.read("tile", 2));
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    public void disabledCacheNeitherReadsNorWrites() throws Exception {
        Path dir = temporary.newFolder().toPath();
        MapStorage store = new MapStorage(dir);
        store.write("old", new int[] {1});
        MapStorage.enabled = false;
        assertNull(store.read("old", 1));
        store.write("new", new int[] {2});
        MapStorage.enabled = true;
        assertNull(store.read("new", 1));
        assertArrayEquals(new int[] {1}, store.read("old", 1));
    }

    @Test
    public void viewRestoreValidatesAndSeparatesSaves() throws Exception {
        Path dir = temporary.newFolder().toPath();
        var view = new MapViewState(1, -351, 245, 12, "PLUTO", "ORES", "ore.mix.test", 0);
        view.save(dir, "save-A");
        assertEquals(view, MapViewState.read(dir, "save-A"));
        assertNull(MapViewState.read(dir, "save-B"));
        assertFalse(new MapViewState(1, 0, 0, 500, "PLUTO", "ORES", "", 0).valid());
        Files.writeString(dir.resolve(MapStorage.hash("save-A") + ".json"), "{broken");
        assertNull(MapViewState.read(dir, "save-A"));
    }
}
