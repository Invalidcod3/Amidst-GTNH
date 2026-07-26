package amidst.gtnh.worker.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Exact seed-only replay of Galacticraft's Moon dungeon and Moon village
 * start-chunk selection.
 */
final class MoonStructurePredictor {

    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int MARKER_OFFSET = 8;
    private static final int DUNGEON_SPACING = 44;
    private static final int VILLAGE_SPACING = 32;
    private static final int VILLAGE_SEPARATION = 8;
    private static final long X_SEED_MULTIPLIER = 341873128712L;
    private static final long Z_SEED_MULTIPLIER = 132897987541L;
    private static final long DUNGEON_SALT = 4291754L;
    private static final long VILLAGE_SALT = 10387312L;
    private static final int MAX_REGIONS = 262144;

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int dimension,
            int minX,
            int minZ,
            int width,
            int height,
            boolean villagesEnabled) {
        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        addDungeons(seed, dimension, minX, minZ, maxX, maxZ, result);
        if (villagesEnabled) {
            addVillages(seed, minX, minZ, maxX, maxZ, result);
        }
        return result;
    }

    private static void addDungeons(
            long seed,
            int dimension,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        RegionBounds regions = regionBounds(
                minX,
                minZ,
                maxX,
                maxZ,
                DUNGEON_SPACING);
        validateRegionCount(regions);
        for (int regionZ = regions.minZ; regionZ <= regions.maxZ; regionZ++) {
            for (int regionX = regions.minX; regionX <= regions.maxX; regionX++) {
                Random random = new Random(
                        regionX * X_SEED_MULTIPLIER
                                + regionZ * Z_SEED_MULTIPLIER
                                + seed
                                + DUNGEON_SALT
                                + dimension);
                int chunkX = regionX * DUNGEON_SPACING
                        + random.nextInt(DUNGEON_SPACING);
                int chunkZ = regionZ * DUNGEON_SPACING
                        + random.nextInt(DUNGEON_SPACING);
                addIfInside(
                        "MOON_DUNGEON",
                        chunkX,
                        chunkZ,
                        minX,
                        minZ,
                        maxX,
                        maxZ,
                        result);
            }
        }
    }

    private static void addVillages(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        int minChunkX = markerChunk(minX, true);
        int maxChunkX = markerChunk(maxX - 1L, false);
        int minChunkZ = markerChunk(minZ, true);
        int maxChunkZ = markerChunk(maxZ - 1L, false);
        long chunkCount = ((long) maxChunkX - minChunkX + 1L)
                * ((long) maxChunkZ - minChunkZ + 1L);
        if (chunkCount > MAX_REGIONS) {
            throw new IllegalArgumentException(
                    "structure query covers too many Moon village chunks");
        }
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                if (isVillageStart(seed, chunkX, chunkZ)) {
                    addIfInside(
                            "MOON_VILLAGE",
                            chunkX,
                            chunkZ,
                            minX,
                            minZ,
                            maxX,
                            maxZ,
                            result);
                }
            }
        }
    }

    private static boolean isVillageStart(long seed, int chunkX, int chunkZ) {
        int adjustedX = chunkX < 0 ? chunkX - VILLAGE_SPACING + 1 : chunkX;
        int adjustedZ = chunkZ < 0 ? chunkZ - VILLAGE_SPACING + 1 : chunkZ;
        int regionX = adjustedX / VILLAGE_SPACING;
        int regionZ = adjustedZ / VILLAGE_SPACING;
        Random random = new Random(
                adjustedX * X_SEED_MULTIPLIER
                        + adjustedZ * Z_SEED_MULTIPLIER
                        + seed
                        + VILLAGE_SALT);
        int candidateX = regionX * VILLAGE_SPACING
                + random.nextInt(VILLAGE_SPACING - VILLAGE_SEPARATION);
        int candidateZ = regionZ * VILLAGE_SPACING
                + random.nextInt(VILLAGE_SPACING - VILLAGE_SEPARATION);
        return chunkX == candidateX && chunkZ == candidateZ;
    }

    private static void addIfInside(
            String kind,
            int chunkX,
            int chunkZ,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        long markerX = (long) chunkX * BLOCKS_PER_CHUNK + MARKER_OFFSET;
        long markerZ = (long) chunkZ * BLOCKS_PER_CHUNK + MARKER_OFFSET;
        if (markerX >= minX && markerX < maxX && markerZ >= minZ && markerZ < maxZ) {
            result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                    kind,
                    "",
                    (int) markerX,
                    (int) markerZ,
                    "EXPECTED"));
        }
    }

    private static RegionBounds regionBounds(
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            int spacing) {
        int minChunkX = markerChunk(minX, true);
        int maxChunkX = markerChunk(maxX - 1L, false);
        int minChunkZ = markerChunk(minZ, true);
        int maxChunkZ = markerChunk(maxZ - 1L, false);
        return new RegionBounds(
                Math.floorDiv(minChunkX, spacing),
                Math.floorDiv(maxChunkX, spacing),
                Math.floorDiv(minChunkZ, spacing),
                Math.floorDiv(maxChunkZ, spacing));
    }

    private static int markerChunk(long blockCoordinate, boolean lowerBound) {
        long shifted = blockCoordinate - MARKER_OFFSET;
        long chunk = lowerBound
                ? ceilingDiv(shifted, BLOCKS_PER_CHUNK)
                : Math.floorDiv(shifted, (long) BLOCKS_PER_CHUNK);
        if (chunk < Integer.MIN_VALUE || chunk > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Moon structure query coordinate is outside supported range");
        }
        return (int) chunk;
    }

    private static long ceilingDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static void validateRegionCount(RegionBounds regions) {
        long count = ((long) regions.maxX - regions.minX + 1L)
                * ((long) regions.maxZ - regions.minZ + 1L);
        if (count > MAX_REGIONS) {
            throw new IllegalArgumentException(
                    "structure query covers too many Moon dungeon regions");
        }
    }

    private static final class RegionBounds {

        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;

        private RegionBounds(int minX, int maxX, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }
    }
}
