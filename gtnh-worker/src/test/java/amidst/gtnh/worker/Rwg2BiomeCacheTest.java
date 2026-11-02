package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

public class Rwg2BiomeCacheTest {
    public static class Manager {
        public String getBiomeDataAt(int x, int z) {
            return x + ":" + z;
        }
    }

    @Test
    public void collisionsAndEvictionNeverReturnAnotherCoordinate() throws Exception {
        Manager manager = new Manager();
        Rwg2BiomeCache cache = new Rwg2BiomeCache(manager, true);
        for (int round = 0; round < 2; round++)
            for (int x = -200; x < 200; x++)
                for (int z = -100; z < 100; z++) {
                    assertEquals(manager.getBiomeDataAt(x, z), cache.sample(manager, x, z));
                    assertEquals(manager.getBiomeDataAt(x, z), cache.sample(manager, x, z));
                }
        for (int x : new int[] {0, Integer.MIN_VALUE, Integer.MAX_VALUE})
            for (int z : new int[] {0, Integer.MIN_VALUE, Integer.MAX_VALUE})
                assertEquals(manager.getBiomeDataAt(x, z), cache.sample(manager, x, z));
        assertTrue(cache.hits >= 160000);
        assertThrows(IllegalStateException.class, () -> cache.sample(new Manager(), 0, 0));
    }
}
