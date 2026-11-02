package amidst.fragment;

import static org.junit.Assert.*;

import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

import org.junit.Test;

public class ChangedChunkTilesTest {
    @Test
    public void negativeChunksAndTileEdgesIncludeStructureNeighborsWithoutDuplicates() {
        var tiles = ChangedChunkTiles.fromChunks(new int[] {-1, -32, -1}, new int[] {-1, -32, -1});
        assertEquals(9, tiles.size());
        assertTrue(tiles.contains(CoordinatesInWorld.from(-1024, -1024)));
        assertTrue(tiles.contains(CoordinatesInWorld.origin()));
        assertFalse(tiles.contains(CoordinatesInWorld.from(512, 0)));
        var edge = ChangedChunkTiles.fromChunks(new int[] {31, 32}, new int[] {0, 0});
        assertEquals(12, edge.size());
        assertTrue(edge.contains(CoordinatesInWorld.from(1024, 0)));
    }

    @Test
    public void emptyChangesAreEmptyAndLargeCoordinatesDoNotWrap() {
        assertTrue(ChangedChunkTiles.fromChunks(new int[0], new int[0]).isEmpty());
        var tiles =
                ChangedChunkTiles.fromChunks(
                        new int[] {Integer.MAX_VALUE}, new int[] {Integer.MIN_VALUE});
        assertEquals(9, tiles.size());
        assertTrue(
                tiles.stream()
                        .allMatch(
                                c -> c.getX() > Integer.MAX_VALUE && c.getY() < Integer.MIN_VALUE));
        assertThrows(
                IllegalArgumentException.class,
                () -> ChangedChunkTiles.fromChunks(new int[1], new int[0]));
    }
}
