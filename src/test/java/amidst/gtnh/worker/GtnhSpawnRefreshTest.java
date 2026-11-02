package amidst.gtnh.worker;

import static org.junit.Assert.*;

import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

import org.junit.Test;

public class GtnhSpawnRefreshTest {
    @Test
    public void spawnDiscoveryOrMovementKeepsTheExistingDeltaAndNeverCancelsWholeMap() {
        var original = new GtnhWorldState(7, false, new int[] {100}, new int[] {200});
        var first = original.withSpawnChange(null, CoordinatesInWorld.from(-1, -17));
        assertFalse(first.fullRefresh());
        assertArrayEquals(new int[] {100, -1}, first.chunkXs());
        assertArrayEquals(new int[] {200, -2}, first.chunkZs());
        var moved =
                original.withSpawnChange(
                        CoordinatesInWorld.from(-1, -17), CoordinatesInWorld.from(32, 48));
        assertArrayEquals(new int[] {100, -1, 2}, moved.chunkXs());
        assertArrayEquals(new int[] {200, -2, 3}, moved.chunkZs());
        assertArrayEquals(new int[] {100}, original.chunkXs());
        var full = new GtnhWorldState(8, true, new int[0], new int[0]);
        assertSame(full, full.withSpawnChange(null, CoordinatesInWorld.origin()));
    }
}
