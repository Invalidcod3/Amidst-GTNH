package amidst.gtnh.export;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.IntConsumer;

/**
 * Best-first search over map tiles. A point is emitted only after every closer tile was scanned.
 */
public final class RadialCoordinateLocator {
    public static final int TILE_SIZE = 512;
    public static final int WORLD_LIMIT = 30_000_000;
    public static final int MAX_QUERIES = 65_536;

    @FunctionalInterface
    public interface Source {
        List<GtnhCoordinate> load(int x, int z) throws Exception;
    }

    public record Result(List<GtnhCoordinate> coordinates, int scanned, boolean exhausted) {}

    private record Tile(int x, int z, long distance) {}

    public static Result locate(Source source, int x, int z, int count, IntConsumer progress)
            throws Exception {
        return locate(source, x, z, count, MAX_QUERIES, progress);
    }

    static Result locate(Source source, int x, int z, int count, int budget, IntConsumer progress)
            throws Exception {
        if (Math.abs((long) x) > WORLD_LIMIT
                || Math.abs((long) z) > WORLD_LIMIT
                || count < 1
                || count > 10_000
                || budget < 1)
            throw new IllegalArgumentException("Invalid radial export origin or count.");
        Comparator<GtnhCoordinate> order =
                Comparator.comparingLong((GtnhCoordinate p) -> distance(x, z, p.x(), p.z()))
                        .thenComparingInt(GtnhCoordinate::x)
                        .thenComparingInt(GtnhCoordinate::z);
        PriorityQueue<Tile> tiles =
                new PriorityQueue<>(
                        Comparator.comparingLong(Tile::distance)
                                .thenComparingInt(Tile::x)
                                .thenComparingInt(Tile::z));
        TreeSet<GtnhCoordinate> candidates = new TreeSet<>(order);
        Set<Long> visited = new HashSet<>();
        List<GtnhCoordinate> result = new ArrayList<>();
        add(tiles, visited, Math.floorDiv(x, TILE_SIZE), Math.floorDiv(z, TILE_SIZE), x, z);
        int scanned = 0;
        while (!tiles.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            long next = tiles.peek().distance;
            // Scan equal-distance tiles too, for deterministic ties at tile boundaries.
            while (!candidates.isEmpty()
                    && distance(x, z, candidates.first().x(), candidates.first().z()) < next) {
                result.add(candidates.pollFirst());
                if (result.size() == count) return new Result(List.copyOf(result), scanned, false);
            }
            if (scanned == budget) return new Result(List.copyOf(result), scanned, true);
            Tile tile = tiles.remove();
            int tx = tile.x * TILE_SIZE, tz = tile.z * TILE_SIZE;
            for (GtnhCoordinate p : source.load(tx, tz)) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                // Producers may return neighboring markers. Only this tile owns these points.
                if (Math.floorDiv(p.x(), TILE_SIZE) != tile.x
                        || Math.floorDiv(p.z(), TILE_SIZE) != tile.z
                        || Math.abs((long) p.x()) > WORLD_LIMIT
                        || Math.abs((long) p.z()) > WORLD_LIMIT) continue;
                candidates.add(p);
                if (candidates.size() > count - result.size()) candidates.pollLast();
            }
            add(tiles, visited, tile.x - 1, tile.z, x, z);
            add(tiles, visited, tile.x + 1, tile.z, x, z);
            add(tiles, visited, tile.x, tile.z - 1, x, z);
            add(tiles, visited, tile.x, tile.z + 1, x, z);
            progress.accept(++scanned);
        }
        while (!candidates.isEmpty() && result.size() < count) result.add(candidates.pollFirst());
        return new Result(List.copyOf(result), scanned, result.size() < count);
    }

    private static void add(Queue<Tile> queue, Set<Long> visited, int tx, int tz, int x, int z) {
        long minX = Math.max(-WORLD_LIMIT, (long) tx * TILE_SIZE),
                maxX = Math.min(WORLD_LIMIT, (long) tx * TILE_SIZE + TILE_SIZE - 1);
        long minZ = Math.max(-WORLD_LIMIT, (long) tz * TILE_SIZE),
                maxZ = Math.min(WORLD_LIMIT, (long) tz * TILE_SIZE + TILE_SIZE - 1);
        if (minX > maxX || minZ > maxZ || !visited.add(key(tx, tz))) return;
        queue.add(
                new Tile(
                        tx,
                        tz,
                        distance(
                                x,
                                z,
                                Math.max(minX, Math.min(maxX, x)),
                                Math.max(minZ, Math.min(maxZ, z)))));
    }

    private static long distance(long x, long z, long px, long pz) {
        long dx = x - px, dz = z - pz;
        return dx * dx + dz * dz;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }
}
