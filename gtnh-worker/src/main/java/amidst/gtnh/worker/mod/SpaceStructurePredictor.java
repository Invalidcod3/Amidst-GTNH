package amidst.gtnh.worker.mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/**
 * Seed-only replay of Galacticraft/GalaxySpace structure start selection.
 */
final class SpaceStructurePredictor {

    private static final int BLOCKS_PER_CHUNK = 16;
    private static final int CHUNK_CENTER = 8;
    private static final int DUNGEON_SPACING = 44;
    private static final int ABANDONED_BASE_SPACING = 36;
    private static final long X_SEED_MULTIPLIER = 341873128712L;
    private static final long Z_SEED_MULTIPLIER = 132897987541L;
    private static final long GALACTICRAFT_DUNGEON_SALT = 4291754L;
    private static final long GALAXYSPACE_DUNGEON_SALT = 4291726L;
    private static final long ABANDONED_BASE_SALT = 10387340L;
    private static final int MAX_CANDIDATES = 4194304;

    private long densitySeed = Long.MIN_VALUE;
    private Object asteroidDensity;
    private Method asteroidNoiseMethod;

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(
            long seed,
            int dimension,
            int minX,
            int minZ,
            int width,
            int height) {
        long maxX = (long) minX + width;
        long maxZ = (long) minZ + height;
        List<RoguelikeDungeonPredictor.StructureDescriptor> result =
                new ArrayList<RoguelikeDungeonPredictor.StructureDescriptor>();

        if (dimension == SpaceDimensionSampler.marsDimensionId()) {
            addCaverns(seed, minX, minZ, maxX, maxZ, result);
            addDungeons(
                    seed,
                    dimension,
                    "MARS_DUNGEON",
                    false,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.asteroidsDimensionId()) {
            addHollowAsteroids(seed, minX, minZ, maxX, maxZ, result);
            addAbandonedBases(
                    seed,
                    dimension,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.ceresDimensionId()) {
            addDungeons(
                    seed,
                    dimension,
                    "CERES_DUNGEON",
                    true,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.ioDimensionId()) {
            addDungeons(
                    seed,
                    dimension,
                    "IO_DUNGEON",
                    true,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.enceladusDimensionId()) {
            addDungeons(
                    seed,
                    dimension,
                    "ENCELADUS_DUNGEON",
                    false,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.proteusDimensionId()) {
            addDungeons(
                    seed,
                    dimension,
                    "PROTEUS_DUNGEON",
                    false,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.plutoDimensionId()) {
            addDungeons(
                    seed,
                    dimension,
                    "PLUTO_DUNGEON",
                    false,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        } else if (dimension == SpaceDimensionSampler.mehenBeltDimensionId()) {
            addDarkMatterAsteroids(seed, minX, minZ, maxX, maxZ, result);
        } else if (dimension == SpaceDimensionSampler.ross128bDimensionId()) {
            addRoss128bRuins(seed, minX, minZ, maxX, maxZ, result);
        } else {
            throw new IllegalArgumentException(
                    "structure prediction does not support space dimension " + dimension);
        }
        return result;
    }

    private static void addDungeons(
            long seed,
            int dimension,
            String kind,
            boolean galaxySpaceAlgorithm,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        RegionBounds regions =
                markerRegionBounds(minX, minZ, maxX, maxZ, DUNGEON_SPACING, CHUNK_CENTER);
        validateCount(regions, "space dungeon regions");
        for (int regionZ = regions.minZ; regionZ <= regions.maxZ; regionZ++) {
            for (int regionX = regions.minX; regionX <= regions.maxX; regionX++) {
                long randomSeed = regionX * X_SEED_MULTIPLIER
                        + regionZ * Z_SEED_MULTIPLIER
                        + seed
                        + (galaxySpaceAlgorithm
                                ? GALAXYSPACE_DUNGEON_SALT
                                : GALACTICRAFT_DUNGEON_SALT + dimension);
                Random random = new Random(randomSeed);
                int chunkX =
                        regionX * DUNGEON_SPACING + random.nextInt(DUNGEON_SPACING);
                int chunkZ =
                        regionZ * DUNGEON_SPACING + random.nextInt(DUNGEON_SPACING);
                addChunkCenterIfInside(
                        kind,
                        "",
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

    private void addDarkMatterAsteroids(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        long firstX = evenCeiling(minX);
        long firstZ = evenCeiling(minZ);
        long xCount = maxX <= firstX ? 0L : (maxX - firstX + 1L) / 2L;
        long zCount = maxZ <= firstZ ? 0L : (maxZ - firstZ + 1L) / 2L;
        if (xCount * zCount > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "structure query covers too many Mehen asteroid candidate points");
        }
        prepareAsteroidDensity(seed);
        Random random = new Random();
        for (long zValue = firstZ; zValue < maxZ; zValue += 2L) {
            int z = (int) zValue;
            for (long xValue = firstX; xValue < maxX; xValue += 2L) {
                int x = (int) xValue;
                if (!isAsteroidCenter(x, z)) {
                    continue;
                }
                random.setSeed((long) (x + z * 3067));
                random.nextInt(160);
                int size = random.nextInt(20) + 5;

                // Amun Ra appends dark matter as the final one-point entry in
                // Mehen's 110-point core table.
                int coreIndex = (int) (110.0D
                        * Math.pow(random.nextDouble(), (size + 5) * 0.05D));
                if (coreIndex == 109) {
                    addIfInside(
                            "MEHEN_DARK_MATTER_ASTEROID",
                            "",
                            x,
                            z,
                            minX,
                            minZ,
                            maxX,
                            maxZ,
                            result);
                }
            }
        }
    }

    private static void addRoss128bRuins(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        int minChunkX = checkedInt(Math.floorDiv((long) minX - 18L, BLOCKS_PER_CHUNK));
        int maxChunkX = checkedInt(Math.floorDiv(maxX - 1L - 3L, BLOCKS_PER_CHUNK));
        int minChunkZ = checkedInt(Math.floorDiv((long) minZ - 18L, BLOCKS_PER_CHUNK));
        int maxChunkZ = checkedInt(Math.floorDiv(maxZ - 1L - 3L, BLOCKS_PER_CHUNK));
        RegionBounds chunks = new RegionBounds(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        validateCount(chunks, "Ross 128b ruin chunks");
        int chance = ross128bRuinChance();
        WorldChunkManager manager = new WorldChunkManager(seed, WorldType.DEFAULT);
        Set<Long> markers = new HashSet<Long>();
        try {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    BiomeGenBase biome = manager.getBiomeGenAt(
                            chunkX * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK,
                            chunkZ * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK);
                    if (isRossRuinExcludedBiome(biome)) {
                        continue;
                    }

                    if (biome == BiomeGenBase.desert
                            || biome == BiomeGenBase.desertHills) {
                        addRossRuinCandidate(
                                rossChunkRandom(seed, chunkX, chunkZ),
                                chance,
                                chunkX,
                                chunkZ,
                                minX,
                                minZ,
                                maxX,
                                maxZ,
                                markers,
                                result);
                    } else {
                        GtnhXstrRandom lakeRandom = rossChunkRandom(seed, chunkX, chunkZ);
                        lakeRandom.nextInt(16);
                        int lakeY = lakeRandom.nextInt(256);
                        lakeRandom.nextInt(16);
                        int lakeRoll = lakeRandom.nextInt(8);
                        if ((lakeRoll != 0 && lakeRoll != 4) || lakeY <= 4) {
                            addRossRuinCandidate(
                                    lakeRandom,
                                    chance,
                                    chunkX,
                                    chunkZ,
                                    minX,
                                    minZ,
                                    maxX,
                                    maxZ,
                                    markers,
                                    result);
                        }
                    }
                }
            }
        } finally {
            manager.cleanupCache();
        }
    }

    private static GtnhXstrRandom rossChunkRandom(long seed, int chunkX, int chunkZ) {
        GtnhXstrRandom random = new GtnhXstrRandom(seed);
        if (chunkX % 4 == 0 || chunkZ % 4 == 0) {
            long xMultiplier = random.nextLong() / 2L * 2L + 1L;
            long zMultiplier = random.nextLong() / 2L * 2L + 1L;
            random.setSeed(chunkX * xMultiplier + chunkZ * zMultiplier ^ seed);
        }
        return random;
    }

    private static void addRossRuinCandidate(
            GtnhXstrRandom random,
            int chance,
            int chunkX,
            int chunkZ,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            Set<Long> markers,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        if (random.nextInt(chance) != 0) {
            return;
        }
        int x = chunkX * BLOCKS_PER_CHUNK + random.nextInt(16) + 3;
        random.nextInt(256);
        int z = chunkZ * BLOCKS_PER_CHUNK + random.nextInt(16) + 3;
        if (x < minX || x >= maxX || z < minZ || z >= maxZ) {
            return;
        }
        long key = ((long) x << 32) ^ (z & 0xffffffffL);
        if (markers.add(Long.valueOf(key))) {
            result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                    "ROSS_128B_RUIN",
                    "Terrain-dependent ruin attempt",
                    x,
                    z,
                    "POSSIBLE"));
        }
    }

    private static boolean isRossRuinExcludedBiome(BiomeGenBase biome) {
        return biome == BiomeGenBase.ocean
                || biome == BiomeGenBase.deepOcean
                || biome == BiomeGenBase.river
                || biome == BiomeGenBase.frozenOcean
                || biome == BiomeGenBase.frozenRiver;
    }

    private static int ross128bRuinChance() {
        try {
            Object config = Class.forName("bartworks.common.configs.Configuration")
                    .getField("crossModInteractions")
                    .get(null);
            int chance = config.getClass().getField("ross128bRuinChance").getInt(config);
            if (chance <= 0) {
                throw new IllegalStateException("Ross 128b ruin chance is not positive");
            }
            return chance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Ross 128b ruin configuration is unavailable",
                    e);
        }
    }

    private static void addCaverns(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        int minChunkX = blockChunk(minX);
        int maxChunkX = blockChunk(maxX - 1L);
        int minChunkZ = blockChunk(minZ);
        int maxChunkZ = blockChunk(maxZ - 1L);
        validateCount(
                new RegionBounds(minChunkX, maxChunkX, minChunkZ, maxChunkZ),
                "Mars cavern chunks");
        Random worldRandom = new Random(seed);
        long xMultiplier = worldRandom.nextLong();
        long zMultiplier = worldRandom.nextLong();
        Random random = new Random();
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                random.setSeed(chunkX * xMultiplier ^ chunkZ * zMultiplier ^ seed);
                if (random.nextInt(100) == 0) {
                    int x = chunkX * BLOCKS_PER_CHUNK + random.nextInt(BLOCKS_PER_CHUNK);
                    int z = chunkZ * BLOCKS_PER_CHUNK + random.nextInt(BLOCKS_PER_CHUNK);
                    addIfInside(
                            "MARS_CAVERN",
                            "",
                            x,
                            z,
                            minX,
                            minZ,
                            maxX,
                            maxZ,
                            result);
                }
            }
        }
    }

    private void addHollowAsteroids(
            long seed,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        long firstX = evenCeiling(minX);
        long firstZ = evenCeiling(minZ);
        long xCount = maxX <= firstX ? 0L : (maxX - firstX + 1L) / 2L;
        long zCount = maxZ <= firstZ ? 0L : (maxZ - firstZ + 1L) / 2L;
        if (xCount * zCount > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "structure query covers too many asteroid candidate points");
        }
        prepareAsteroidDensity(seed);
        Random random = new Random();
        for (long zValue = firstZ; zValue < maxZ; zValue += 2L) {
            int z = (int) zValue;
            for (long xValue = firstX; xValue < maxX; xValue += 2L) {
                int x = (int) xValue;
                if (!isAsteroidCenter(x, z)) {
                    continue;
                }
                random.setSeed((long) (x + z * 3067));
                random.nextInt(160);
                int size = random.nextInt(20) + 5;

                // SpecialAsteroidBlockHandler#getBlock for the core.
                random.nextDouble();
                // A selected shell consumes one bounded integer.
                if (random.nextInt(2) == 0) {
                    random.nextInt(6);
                }
                random.nextFloat();
                if (random.nextInt(10) == 0 && size >= 15) {
                    addIfInside(
                            "HOLLOW_ASTEROID",
                            "",
                            x,
                            z,
                            minX,
                            minZ,
                            maxX,
                            maxZ,
                            result);
                }
            }
        }
    }

    /**
     * Galacticraft 4's abandoned-base start selection is replayed because the
     * current 1.7.10 provider contains no base generator. Markers denote the
     * deterministic logical start chunk; this also keeps fragmented map queries
     * stable when the later provider would snap a base to a loaded asteroid.
     */
    private static void addAbandonedBases(
            long seed,
            int dimension,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        RegionBounds regions = markerRegionBounds(
                minX,
                minZ,
                maxX,
                maxZ,
                ABANDONED_BASE_SPACING,
                CHUNK_CENTER);
        validateCount(regions, "abandoned-base regions");
        Random structureRandom = new Random(seed);
        long xMultiplier = structureRandom.nextLong();
        long zMultiplier = structureRandom.nextLong();
        for (int regionZ = regions.minZ; regionZ <= regions.maxZ; regionZ++) {
            for (int regionX = regions.minX; regionX <= regions.maxX; regionX++) {
                Random candidateRandom = new Random(
                        regionX * X_SEED_MULTIPLIER
                                + regionZ * Z_SEED_MULTIPLIER
                                + seed
                                + ABANDONED_BASE_SALT
                                + dimension);
                int chunkX = regionX * ABANDONED_BASE_SPACING
                        + candidateRandom.nextInt(ABANDONED_BASE_SPACING);
                int chunkZ = regionZ * ABANDONED_BASE_SPACING
                        + candidateRandom.nextInt(ABANDONED_BASE_SPACING);

                Random typeRandom = new Random(
                        chunkX * xMultiplier ^ chunkZ * zMultiplier ^ seed);
                typeRandom.nextInt(5);
                int type = typeRandom.nextInt(3);
                String kind = type == 0
                        ? "ASTEROID_BASE_HUMAN"
                        : type == 1
                                ? "ASTEROID_BASE_BIRD"
                                : "ASTEROID_BASE_MECHANICAL";
                addChunkCenterIfInside(
                        kind,
                        "",
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

    private void prepareAsteroidDensity(long seed) {
        if (asteroidDensity != null && densitySeed == seed) {
            return;
        }
        try {
            Class<?> billowedClass = Class.forName(
                    "micdoodle8.mods.galacticraft.core.perlin.generator.Billowed");
            Constructor<?> constructor =
                    billowedClass.getConstructor(Long.TYPE, Integer.TYPE, Float.TYPE);
            Random worldRandom = new Random(seed);
            asteroidDensity =
                    constructor.newInstance(Long.valueOf(worldRandom.nextLong()), 2, 0.25F);
            Method setFrequency =
                    billowedClass.getMethod("setFrequency", Float.TYPE);
            setFrequency.invoke(asteroidDensity, 0.009F);
            Field amplitude = billowedClass.getField("amplitude");
            amplitude.setFloat(asteroidDensity, 0.6F);
            asteroidNoiseMethod =
                    billowedClass.getMethod("getNoise", Float.TYPE, Float.TYPE);
            densitySeed = seed;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Galacticraft asteroid density generator is unavailable",
                    e);
        }
    }

    private boolean isAsteroidCenter(int x, int z) {
        try {
            float noise = ((Float) asteroidNoiseMethod.invoke(
                    asteroidDensity,
                    Float.valueOf(x),
                    Float.valueOf(z))).floatValue();
            return randFromPointPos(x, z) < (noise + 0.4F) / 800.0F;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("unable to sample asteroid density", e);
        }
    }

    private static float randFromPointPos(int x, int z) {
        int n = x + z * 57;
        n ^= n << 13;
        n = n * (n * n * 15731 + 789221) + 1376312589 & 0x3fffffff;
        return 1.0F - n / 1073741824.0F;
    }

    private static long evenCeiling(int value) {
        return (value & 1) == 0 ? value : (long) value + 1L;
    }

    private static RegionBounds markerRegionBounds(
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            int spacing,
            int markerOffset) {
        int minChunkX = markerChunk(minX, markerOffset, true);
        int maxChunkX = markerChunk(maxX - 1L, markerOffset, false);
        int minChunkZ = markerChunk(minZ, markerOffset, true);
        int maxChunkZ = markerChunk(maxZ - 1L, markerOffset, false);
        return new RegionBounds(
                Math.floorDiv(minChunkX, spacing),
                Math.floorDiv(maxChunkX, spacing),
                Math.floorDiv(minChunkZ, spacing),
                Math.floorDiv(maxChunkZ, spacing));
    }

    private static int markerChunk(
            long blockCoordinate,
            int markerOffset,
            boolean lowerBound) {
        long shifted = blockCoordinate - markerOffset;
        long chunk = lowerBound
                ? ceilingDiv(shifted, BLOCKS_PER_CHUNK)
                : Math.floorDiv(shifted, (long) BLOCKS_PER_CHUNK);
        return checkedInt(chunk);
    }

    private static int blockChunk(long blockCoordinate) {
        return checkedInt(Math.floorDiv(blockCoordinate, (long) BLOCKS_PER_CHUNK));
    }

    private static int checkedInt(long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "space structure query coordinate is outside supported range");
        }
        return (int) value;
    }

    private static long ceilingDiv(long value, long divisor) {
        return -Math.floorDiv(-value, divisor);
    }

    private static void validateCount(RegionBounds bounds, String description) {
        long count = ((long) bounds.maxX - bounds.minX + 1L)
                * ((long) bounds.maxZ - bounds.minZ + 1L);
        if (count > MAX_CANDIDATES) {
            throw new IllegalArgumentException(
                    "structure query covers too many " + description);
        }
    }

    private static void addChunkCenterIfInside(
            String kind,
            String subtype,
            int chunkX,
            int chunkZ,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        long x = (long) chunkX * BLOCKS_PER_CHUNK + CHUNK_CENTER;
        long z = (long) chunkZ * BLOCKS_PER_CHUNK + CHUNK_CENTER;
        if (x >= Integer.MIN_VALUE
                && x <= Integer.MAX_VALUE
                && z >= Integer.MIN_VALUE
                && z <= Integer.MAX_VALUE) {
            addIfInside(
                    kind,
                    subtype,
                    (int) x,
                    (int) z,
                    minX,
                    minZ,
                    maxX,
                    maxZ,
                    result);
        }
    }

    private static void addIfInside(
            String kind,
            String subtype,
            int x,
            int z,
            int minX,
            int minZ,
            long maxX,
            long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
            result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                    kind,
                    subtype,
                    x,
                    z,
                    "EXPECTED"));
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
