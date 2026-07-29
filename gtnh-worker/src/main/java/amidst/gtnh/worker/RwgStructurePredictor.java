package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seed-only predictors for the three MapGen structures RWG actually invokes:
 * villages, mineshafts and strongholds. RWG does not invoke the vanilla
 * scattered-feature generator, so temples and witch huts are intentionally
 * absent.
 */
final class RwgStructurePredictor {

    private static final int VILLAGE_DISTANCE = 24;
    private static final int VILLAGE_SEPARATION = 8;
    private static final int VILLAGE_SALT = 10387312;

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int minX,
            int minZ,
            int width,
            int height,
            SurfaceBiomeSampler sampler) {
        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();
        if (getRwgFlag("generateVillages")) {
            addVillages(seed, minX, minZ, maxX, maxZ, sampler, result);
        }
        if (getRwgFlag("generateMineshafts")) {
            addMineshafts(seed, minX, minZ, maxX, maxZ, result);
        }
        addStrongholds(seed, minX, minZ, maxX, maxZ, result);
        return result;
    }

    private static void addVillages(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            SurfaceBiomeSampler sampler,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        int minChunkX = floorDiv((long) minX - 4L, 16);
        int maxChunkX = floorDiv(maxX - 1L - 4L, 16);
        int minChunkZ = floorDiv((long) minZ - 4L, 16);
        int maxChunkZ = floorDiv(maxZ - 1L - 4L, 16);
        int minRegionX = Math.floorDiv(minChunkX, VILLAGE_DISTANCE);
        int maxRegionX = Math.floorDiv(maxChunkX, VILLAGE_DISTANCE);
        int minRegionZ = Math.floorDiv(minChunkZ, VILLAGE_DISTANCE);
        int maxRegionZ = Math.floorDiv(maxChunkZ, VILLAGE_DISTANCE);

        for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                Random random = seededRandom(seed, regionX, regionZ, VILLAGE_SALT);
                int chunkX = regionX * VILLAGE_DISTANCE
                        + random.nextInt(VILLAGE_DISTANCE - VILLAGE_SEPARATION);
                int chunkZ = regionZ * VILLAGE_DISTANCE
                        + random.nextInt(VILLAGE_DISTANCE - VILLAGE_SEPARATION);
                int x = chunkX * 16 + 4;
                int z = chunkZ * 16 + 4;
                if (inside(x, z, minX, minZ, maxX, maxZ)
                        && sampler.isVillageLocationViable(chunkX * 16 + 8, chunkZ * 16 + 8)) {
                    result.add(descriptor("VILLAGE", x, z, "POSSIBLE"));
                }
            }
        }
    }

    private static void addMineshafts(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        int minChunkX = floorDiv((long) minX - 8L, 16);
        int maxChunkX = floorDiv(maxX - 1L - 8L, 16);
        int minChunkZ = floorDiv((long) minZ - 8L, 16);
        int maxChunkZ = floorDiv(maxZ - 1L - 8L, 16);
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                Random random = new Random(seed);
                long xSeed = (long) chunkX * random.nextLong();
                long zSeed = (long) chunkZ * random.nextLong();
                random.setSeed(xSeed ^ zSeed ^ seed);
                random.nextInt();
                if (random.nextDouble() < 0.004D
                        && random.nextInt(80) < Math.max(Math.abs(chunkX), Math.abs(chunkZ))) {
                    int x = chunkX * 16 + 8;
                    int z = chunkZ * 16 + 8;
                    if (inside(x, z, minX, minZ, maxX, maxZ)) {
                        result.add(descriptor("MINESHAFT", x, z, "POSSIBLE"));
                    }
                }
            }
        }
    }

    private static void addStrongholds(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        Random random = new Random(seed);
        double angle = random.nextDouble() * Math.PI * 2.0D;
        for (int i = 0; i < 3; i++) {
            double distance = (1.25D + random.nextDouble()) * 32.0D;
            int chunkX = (int) Math.round(Math.cos(angle) * distance);
            int chunkZ = (int) Math.round(Math.sin(angle) * distance);
            int x = chunkX * 16;
            int z = chunkZ * 16;
            if (inside(x, z, minX, minZ, maxX, maxZ)) {
                result.add(descriptor("STRONGHOLD", x, z, "EXPECTED"));
            }
            angle += Math.PI * 2.0D / 3.0D;
        }
    }

    private static RoguelikeDungeonPredictor.StructureDescriptor descriptor(
            String kind,
            int x,
            int z,
            String certainty) {
        return new RoguelikeDungeonPredictor.StructureDescriptor(
                kind,
                "",
                x,
                z,
                certainty);
    }

    private static boolean inside(int x, int z, int minX, int minZ, long maxX, long maxZ) {
        return x >= minX && x < maxX && z >= minZ && z < maxZ;
    }

    private static Random seededRandom(long seed, int x, int z, int salt) {
        return new Random(
                (long) x * 341873128712L
                        + (long) z * 132897987541L
                        + seed
                        + salt);
    }

    private static int floorDiv(long value, int divisor) {
        return (int) Math.floorDiv(value, (long) divisor);
    }

    private static boolean getRwgFlag(String fieldName) {
        try {
            Class<?> config = Class.forName("rwg.config.ConfigRWG");
            Field field = config.getField(fieldName);
            return field.getBoolean(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot read RWG setting " + fieldName, e);
        }
    }
}
