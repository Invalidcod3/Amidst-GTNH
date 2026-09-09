package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.minecraft.init.Blocks;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraftforge.common.BiomeDictionary;

/**
 * Replays the seed-only portion of Thaumcraft 4.2.3.5 surface world generation.
 *
 * Tree generators can consume a terrain-dependent amount of randomness. The
 * predictor deliberately omits those rare ambiguous chunks instead of
 * publishing coordinates that are known to be unstable.
 */
final class ThaumcraftStructurePredictor {

    private static final int OVERWORLD = 0;
    private static final int TEMPLE_DISTANCE = 32;
    private static final int TEMPLE_SEPARATION = 8;
    private static final int TEMPLE_SALT = 14357617;

    private final Class<?> configClass;
    private final Field genAura;
    private final Field genStructure;
    private final Field genCinnibar;
    private final Field genAmber;
    private final Field genInfusedStone;
    private final Field genTrees;
    private final Field nodeRarity;
    private final Method getDimBlacklist;
    private final Method getBiomeBlacklist;
    private final Method getBiomeSupportsGreatwood;
    private final BiomeGenBase magicalForest;
    private final BiomeGenBase taint;

    ThaumcraftStructurePredictor() {
        this(loadApi("thaumcraft.common.config.Config"),
                loadApi("thaumcraft.common.lib.world.ThaumcraftWorldGenerator"),
                loadApi("thaumcraft.common.lib.world.biomes.BiomeHandler"));
    }

    // Explicit API dependencies let sliced jobs be checked without a game or global mock mod classes.
    ThaumcraftStructurePredictor(Class<?> config, Class<?> generator, Class<?> biomeHandler) {
        try {
            configClass = config;
            genAura = configClass.getField("genAura");
            genStructure = configClass.getField("genStructure");
            genCinnibar = configClass.getField("genCinnibar");
            genAmber = configClass.getField("genAmber");
            genInfusedStone = configClass.getField("genInfusedStone");
            genTrees = configClass.getField("genTrees");
            nodeRarity = configClass.getField("nodeRarity");

            getDimBlacklist = generator.getMethod("getDimBlacklist", Integer.TYPE);
            getBiomeBlacklist = generator.getMethod("getBiomeBlacklist", Integer.TYPE);
            magicalForest = (BiomeGenBase) generator.getField("biomeMagicalForest").get(null);
            taint = (BiomeGenBase) generator.getField("biomeTaint").get(null);

            getBiomeSupportsGreatwood =
                    biomeHandler.getMethod("getBiomeSupportsGreatwood", Integer.TYPE);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Thaumcraft 4.2.3.5 structure prediction API is unavailable",
                    e);
        }
    }

    private static Class<?> loadApi(String name) {
        try { return Class.forName(name); }
        catch (ClassNotFoundException e) { throw new IllegalStateException("Thaumcraft API unavailable: " + name, e); }
    }

    List<RoguelikeDungeonPredictor.StructureDescriptor> predict(long seed, int dimension,
            int minX, int minZ, int width, int height, SurfaceBiomeSampler sampler) {
        Job job = begin(seed, dimension, minX, minZ, width, height, sampler);
        job.advance(Long.MAX_VALUE);
        return job.result;
    }

    Job begin(long seed, int dimension, int minX, int minZ, int width, int height, SurfaceBiomeSampler sampler) {
        return new Job(seed, dimension, minX, minZ, width, height, sampler);
    }

    /** Keep each chunk's original RNG intact; only yield between chunks. */
    final class Job {
        final List<RoguelikeDungeonPredictor.StructureDescriptor> result = new ArrayList<>();
        private final long seed, maxX, maxZ;
        private final int minX, minZ, minChunkX, maxChunkX, maxChunkZ, rarity, blacklist;
        private final SurfaceBiomeSampler sampler;
        private int chunkX, chunkZ;
        Job(long seed, int dimension, int minX, int minZ, int width, int height, SurfaceBiomeSampler sampler) {
            if (dimension != OVERWORLD) throw new IllegalArgumentException("Thaumcraft prediction requires overworld");
            this.seed = seed; this.minX = minX; this.minZ = minZ; this.sampler = sampler;
            maxX = (long) minX + width; maxZ = (long) minZ + height;
            minChunkX = floorDiv((long) minX - 15L, 16);
            maxChunkX = floorDiv(maxX - 1, 16); maxChunkZ = floorDiv(maxZ - 1, 16);
            chunkX = minChunkX; chunkZ = floorDiv((long) minZ - 15L, 16);
            rarity = getInt(nodeRarity);
            if (rarity < 1) throw new IllegalStateException("Thaumcraft returned invalid node rarity " + rarity);
            blacklist = invokeInt(getDimBlacklist, dimension);
            if (oresConsumeRandomness()) {
                AmidstGtnhWorkerLog.LOG.warn("Thaumcraft ores alter the random stream; node/altar prediction disabled");
                chunkZ = maxChunkZ + 1;
            }
        }
        boolean advance(long deadline) {
            return advance(deadline, System::nanoTime);
        }
        boolean advance(long deadline, java.util.function.LongSupplier clock) {
            while (chunkZ <= maxChunkZ) {
                if (clock.getAsLong() >= deadline) return false;
                predictChunk(chunkX, chunkZ, seed, blacklist, rarity, sampler, minX, minZ, maxX, maxZ, result);
                if (++chunkX > maxChunkX) { chunkX = minChunkX; chunkZ++; }
            }
            return true;
        }
    }

    private void predictChunk(int chunkX, int chunkZ, long seed, int blacklist, int rarity,
            SurfaceBiomeSampler sampler, int minX, int minZ, long maxX, long maxZ,
            List<RoguelikeDungeonPredictor.StructureDescriptor> result) {
        Random random = RoguelikeDungeonPredictor.forgeChunkRandom(seed, chunkX, chunkZ);
        if (!consumeVegetation(random, chunkX, chunkZ, sampler, blacklist)) {
            return;
        }

        PredictedPoint structureNode = predictScatteredFeatureNode(
                seed,
                chunkX,
                chunkZ,
                sampler);
        if (getBoolean(genAura) && blacklist != 0 && blacklist != 2) {
            if (structureNode != null) {
                addIfInside(
                        result,
                        "THAUMCRAFT_AURA_NODE",
                        "SCATTERED_FEATURE",
                        structureNode.x,
                        structureNode.z,
                        minX,
                        minZ,
                        maxX,
                        maxZ);
                // createRandomNodeAt consumes biome- and terrain-dependent
                // randomness, so the later altar stream is not stable.
                return;
            }
            if (random.nextInt(rarity) == 0) {
                int x = chunkX * 16 + random.nextInt(16);
                int z = chunkZ * 16 + random.nextInt(16);
                addIfInside(
                        result,
                        "THAUMCRAFT_AURA_NODE",
                        "WILD",
                        x,
                        z,
                        minX,
                        minZ,
                        maxX,
                        maxZ);
                return;
            }
        }

        if (blacklist == -1 && getBoolean(genStructure)) {
            int x = chunkX * 16 + random.nextInt(16);
            int z = chunkZ * 16 + random.nextInt(16);
            if (random.nextInt(150) != 0 && random.nextInt(66) == 0) {
                addIfInside(
                        result,
                        "THAUMCRAFT_ELDRITCH_ALTAR",
                        "",
                        x,
                        z,
                        minX,
                        minZ,
                        maxX,
                        maxZ);
            }
        }
    }

    /**
     * @return true when the later random stream is still deterministic.
     */
    private boolean consumeVegetation(
            Random random,
            int chunkX,
            int chunkZ,
            SurfaceBiomeSampler sampler,
            int dimensionBlacklist) {
        if (dimensionBlacklist != -1 || !getBoolean(genTrees)) {
            return true;
        }
        BiomeGenBase center = sampler.getBiomeAt(chunkX * 16 + 8, chunkZ * 16 + 8);
        if (center == null || invokeInt(getBiomeBlacklist, center.biomeID) != -1) {
            return true;
        }

        if (random.nextInt(60) == 3) {
            int x = chunkX * 16 + random.nextInt(16);
            int z = chunkZ * 16 + random.nextInt(16);
            BiomeGenBase biome = sampler.getBiomeAt(x, z);
            boolean canAttemptSilverwood = biome != null
                    && biome != magicalForest
                    && biome != taint
                    && (BiomeDictionary.isBiomeOfType(biome, BiomeDictionary.Type.MAGICAL)
                            || biome == BiomeGenBase.forestHills
                            || biome == BiomeGenBase.birchForestHills);
            if (canAttemptSilverwood) {
                return false;
            }
        }

        if (random.nextInt(25) == 7) {
            int x = chunkX * 16 + random.nextInt(16);
            int z = chunkZ * 16 + random.nextInt(16);
            BiomeGenBase biome = sampler.getBiomeAt(x, z);
            float threshold = biome == null
                    ? 0.0F
                    : invokeFloat(getBiomeSupportsGreatwood, biome.biomeID);
            if (threshold > random.nextFloat()) {
                return false;
            }
        }

        int flowerX = chunkX * 16 + random.nextInt(16);
        int flowerZ = chunkZ * 16 + random.nextInt(16);
        BiomeGenBase flowerBiome = sampler.getBiomeAt(flowerX, flowerZ);
        if (flowerBiome != null
                && flowerBiome.topBlock == Blocks.sand
                && flowerBiome.temperature > 1.0F
                && random.nextInt(30) == 0) {
            for (int i = 0; i < 18; i++) {
                random.nextInt(8);
                random.nextInt(8);
                random.nextInt(4);
                random.nextInt(4);
                random.nextInt(8);
                random.nextInt(8);
            }
        }
        return true;
    }

    private PredictedPoint predictScatteredFeatureNode(
            long seed,
            int chunkX,
            int chunkZ,
            SurfaceBiomeSampler sampler) {
        int regionX = chunkX;
        int regionZ = chunkZ;
        if (regionX < 0) regionX -= TEMPLE_DISTANCE - 1;
        if (regionZ < 0) regionZ -= TEMPLE_DISTANCE - 1;
        regionX /= TEMPLE_DISTANCE;
        regionZ /= TEMPLE_DISTANCE;
        Random placement = seededRandom(seed, regionX, regionZ, TEMPLE_SALT);
        int candidateX = regionX * TEMPLE_DISTANCE
                + placement.nextInt(TEMPLE_DISTANCE - TEMPLE_SEPARATION);
        int candidateZ = regionZ * TEMPLE_DISTANCE
                + placement.nextInt(TEMPLE_DISTANCE - TEMPLE_SEPARATION);
        if (chunkX != candidateX || chunkZ != candidateZ) {
            return null;
        }

        BiomeGenBase biome = sampler.getBiomeAt(chunkX * 16 + 8, chunkZ * 16 + 8);
        if (biome != BiomeGenBase.desert
                && biome != BiomeGenBase.desertHills
                && biome != BiomeGenBase.jungle
                && biome != BiomeGenBase.jungleHills
                && biome != BiomeGenBase.swampland) {
            return null;
        }

        Random structureRandom = new Random(seed);
        long xSeed = structureRandom.nextLong();
        long zSeed = structureRandom.nextLong();
        structureRandom.setSeed((long) chunkX * xSeed ^ (long) chunkZ * zSeed ^ seed);
        int orientation = structureRandom.nextInt(4);
        int offsetX;
        int offsetZ;
        if (biome == BiomeGenBase.jungle || biome == BiomeGenBase.jungleHills) {
            offsetX = orientation == 0 || orientation == 2 ? 5 : 7;
            offsetZ = orientation == 0 || orientation == 2 ? 7 : 5;
        } else if (biome == BiomeGenBase.swampland) {
            offsetX = orientation == 0 || orientation == 2 ? 3 : 4;
            offsetZ = orientation == 0 || orientation == 2 ? 4 : 3;
        } else {
            offsetX = 10;
            offsetZ = 10;
        }
        return new PredictedPoint(chunkX * 16 + offsetX, chunkZ * 16 + offsetZ);
    }

    private boolean oresConsumeRandomness() {
        return getBoolean(genCinnibar) || getBoolean(genAmber) || getBoolean(genInfusedStone);
    }

    private static void addIfInside(
            List<RoguelikeDungeonPredictor.StructureDescriptor> result,
            String kind,
            String subtype,
            int x,
            int z,
            int minX,
            int minZ,
            long maxX,
            long maxZ) {
        if (x >= minX && x < maxX && z >= minZ && z < maxZ) {
            result.add(new RoguelikeDungeonPredictor.StructureDescriptor(
                    kind,
                    subtype,
                    x,
                    z,
                    "POSSIBLE"));
        }
    }

    private boolean getBoolean(Field field) {
        try {
            return field.getBoolean(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read Thaumcraft boolean setting", e);
        }
    }

    private int getInt(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read Thaumcraft integer setting", e);
        }
    }

    private static int invokeInt(Method method, int value) {
        try {
            return ((Integer) method.invoke(null, Integer.valueOf(value))).intValue();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read Thaumcraft integer API", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Thaumcraft integer API failed", cause);
        }
    }

    private static float invokeFloat(Method method, int value) {
        try {
            return ((Float) method.invoke(null, Integer.valueOf(value))).floatValue();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot read Thaumcraft biome API", e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("Thaumcraft biome API failed", cause);
        }
    }

    private static Random seededRandom(long seed, int x, int z, int salt) {
        return new Random(
                (long) x * 341873128712L
                        + (long) z * 132897987541L
                        + seed
                        + (long) salt);
    }

    private static int floorDiv(long value, int divisor) {
        return (int) Math.floorDiv(value, (long) divisor);
    }

    private static final class PredictedPoint {

        private final int x;
        private final int z;

        private PredictedPoint(int x, int z) {
            this.x = x;
            this.z = z;
        }
    }
}
