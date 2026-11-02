package amidst.fragment;

import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

import java.util.HashSet;
import java.util.Set;

/** Shared region for terrain, structure caches and retained tiles after live chunk changes. */
public final class ChangedChunkTiles {
    private ChangedChunkTiles() {}

    public static Set<CoordinatesInWorld> fromChunks(int[] chunkXs, int[] chunkZs) {
        if (chunkXs.length != chunkZs.length)
            throw new IllegalArgumentException("chunk coordinate arrays must have equal lengths");
        Set<CoordinatesInWorld> centers = new HashSet<>();
        for (int i = 0; i < chunkXs.length; i++)
            centers.add(
                    CoordinatesInWorld.from((long) chunkXs[i] * 16, (long) chunkZs[i] * 16)
                            .toFragmentCorner());
        Set<CoordinatesInWorld> affected = new HashSet<>();
        // Structures can read loaded chunks beyond their tile (e.g. dungeon reach).
        // One neighboring tile in each direction conservatively covers these reads.
        for (CoordinatesInWorld center : centers)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    affected.add(
                            CoordinatesInWorld.from(
                                    center.getX() + (long) dx * Fragment.SIZE,
                                    center.getY() + (long) dz * Fragment.SIZE));
        return affected;
    }
}
