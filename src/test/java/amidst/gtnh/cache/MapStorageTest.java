package amidst.gtnh.cache;

import static org.junit.Assert.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import java.nio.file.*;

public class MapStorageTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @After
    public void reset() {
        MapStorage.enabled = true;
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
