package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import net.minecraft.entity.Entity;
import net.minecraft.init.Blocks;
import net.minecraft.profiler.Profiler;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.IChunkProvider;

/** Frozen v0.2/v28 implementation, retained only to check exact outputs and benchmark changes. */
final class ReferenceRwgSurfaceBiomeSampler implements SurfaceBiomeSampler {

    private static final int SAMPLE_SIZE = 8;
    private static final int SAMPLE_ARRAY_SIZE = SAMPLE_SIZE * 2 + 5;
    private static final int HUGE_SIZE = 9;
    private static final int SMALL_SIZE = 25;
    private static final int MAX_CACHED_RAW_BIOMES = 65_536;
    private static final int MAX_CACHED_BLEND_CHUNKS = 2_048;
    private static final float[] PARABOLIC_FIELD = createParabolicField();

    private final WorldServer world;
    private final WorldChunkManager manager;
    private final Method getBiomeDataAt;
    private final Method getRiverStrength;
    private final Method getNoiseAt;
    private final Field realisticBiomeId;
    private final Field baseBiome;
    private final Field riverBiome;
    private final Object perlin;
    private final Method noise2;
    private final Map<Long, Object> rawBiomeCache =
            new LinkedHashMap<Long, Object>(1_024, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Object> eldest) {
                    return size() > MAX_CACHED_RAW_BIOMES;
                }
            };
    private final Map<Long, BlendChunk> blendChunkCache =
            new LinkedHashMap<Long, BlendChunk>(256, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, BlendChunk> eldest) {
                    return size() > MAX_CACHED_BLEND_CHUNKS;
                }
            };

    ReferenceRwgSurfaceBiomeSampler(WorldServer world, WorldChunkManager manager)
            throws ReflectiveOperationException {
        this.world = world;
        this.manager = manager;

        Class<?> managerClass = manager.getClass();
        getBiomeDataAt = managerClass.getMethod("getBiomeDataAt", Integer.TYPE, Integer.TYPE);
        getRiverStrength = managerClass.getMethod("getRiverStrength", Integer.TYPE, Integer.TYPE);
        getNoiseAt = managerClass.getMethod("getNoiseAt", Integer.TYPE, Integer.TYPE);

        Class<?> realisticBiomeClass = getBiomeDataAt.getReturnType();
        realisticBiomeId = realisticBiomeClass.getField("biomeID");
        baseBiome = realisticBiomeClass.getField("baseBiome");
        riverBiome = realisticBiomeClass.getField("riverBiome");

        Field perlinField = findField(managerClass, "perlin");
        perlinField.setAccessible(true);
        perlin = perlinField.get(manager);
        if (perlin == null) {
            throw new IllegalStateException("RWG chunk manager has no initialized noise generator");
        }
        noise2 = perlin.getClass().getMethod("noise2", Float.TYPE, Float.TYPE);
        noise2.setAccessible(true);
    }

    @Override
    public BiomeGenBase getBiomeAt(int x, int z) {
        /*
         * A loaded chunk already contains RWG's authoritative final biome
         * array. blockExists only checks the loaded chunk provider and does
         * not generate or load a new chunk.
         */
        if (world != null && world.blockExists(x, 0, z)) {
            return world.getBiomeGenForCoords(x, z);
        }

        try {
            Object realisticBiome = getBlendChunk(
                    Math.floorDiv(x, 16),
                    Math.floorDiv(z, 16))
                    .getRealisticBiome(Math.floorMod(x, 16), Math.floorMod(z, 16));
            BiomeGenBase base = getBaseBiome(realisticBiome);

            float rawStrength = ((Float) getRiverStrength.invoke(
                    manager,
                    Integer.valueOf(x),
                    Integer.valueOf(z))).floatValue();
            float strength = -rawStrength;
            if (!isRiver(strength, getRiverNoise(x, z))) {
                return base;
            }

            BiomeGenBase river = (BiomeGenBase) riverBiome.get(realisticBiome);
            return river == null ? base : river;
        } catch (IllegalAccessException e) {
            throw reflectionFailure(x, z, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw reflectionFailure(x, z, cause);
        }
    }

    private BlendChunk getBlendChunk(int chunkX, int chunkZ)
            throws IllegalAccessException, InvocationTargetException {
        long key = coordinateKey(chunkX, chunkZ);
        BlendChunk cached = blendChunkCache.get(Long.valueOf(key));
        if (cached != null) {
            return cached;
        }
        BlendChunk created = createBlendChunk(chunkX, chunkZ);
        blendChunkCache.put(Long.valueOf(key), created);
        return created;
    }

    /**
     * Reproduces RWG's biome-only portion of ChunkGeneratorRealistic#getNewNoise.
     * Terrain height and block generation are deliberately omitted. Candidate
     * biomes are cached on RWG's eight-block sampling grid, while interpolation
     * arrays contain only the biome ids present around this chunk.
     */
    private BlendChunk createBlendChunk(int chunkX, int chunkZ)
            throws IllegalAccessException, InvocationTargetException {
        int originX = chunkX << 4;
        int originZ = chunkZ << 4;
        int[] sampledIds = new int[SAMPLE_ARRAY_SIZE * SAMPLE_ARRAY_SIZE];
        TreeMap<Integer, Object> biomesById = new TreeMap<Integer, Object>();

        for (int sampleX = -SAMPLE_SIZE; sampleX < SAMPLE_SIZE + 5; sampleX++) {
            for (int sampleZ = -SAMPLE_SIZE; sampleZ < SAMPLE_SIZE + 5; sampleZ++) {
                int index = (sampleX + SAMPLE_SIZE) * SAMPLE_ARRAY_SIZE
                        + sampleZ + SAMPLE_SIZE;
                Object biome = getRawBiome(
                        originX + sampleX * 8 - 8,
                        originZ + sampleZ * 8 - 8);
                int biomeId = realisticBiomeId.getInt(biome);
                if (biomeId < 0 || biomeId > 255) {
                    throw new IllegalStateException(
                            "RWG realistic biome id is outside the supported range: " + biomeId);
                }
                sampledIds[index] = biomeId;
                biomesById.put(Integer.valueOf(biomeId), biome);
            }
        }

        int biomeCount = biomesById.size();
        int[] biomeIds = new int[biomeCount];
        Object[] realisticBiomes = new Object[biomeCount];
        int[] idToIndex = new int[256];
        Arrays.fill(idToIndex, -1);
        int biomeIndex = 0;
        for (Map.Entry<Integer, Object> entry : biomesById.entrySet()) {
            int biomeId = entry.getKey().intValue();
            biomeIds[biomeIndex] = biomeId;
            realisticBiomes[biomeIndex] = entry.getValue();
            idToIndex[biomeId] = biomeIndex;
            biomeIndex++;
        }

        float[] huge = new float[HUGE_SIZE * HUGE_SIZE * biomeCount];
        for (int gridX = -1; gridX < 4; gridX++) {
            for (int gridZ = -1; gridZ < 4; gridZ++) {
                int resultOffset =
                        ((gridX * 2 + 2) * HUGE_SIZE + gridZ * 2 + 2) * biomeCount;
                for (int offsetX = -SAMPLE_SIZE; offsetX <= SAMPLE_SIZE; offsetX++) {
                    for (int offsetZ = -SAMPLE_SIZE; offsetZ <= SAMPLE_SIZE; offsetZ++) {
                        int sampleIndex =
                                (gridX + offsetX + SAMPLE_SIZE + 1) * SAMPLE_ARRAY_SIZE
                                        + gridZ + offsetZ + SAMPLE_SIZE + 1;
                        int compactIndex = idToIndex[sampledIds[sampleIndex]];
                        huge[resultOffset + compactIndex] += PARABOLIC_FIELD[
                                offsetX + SAMPLE_SIZE
                                        + (offsetZ + SAMPLE_SIZE) * (SAMPLE_SIZE * 2 + 1)];
                    }
                }
            }
        }

        for (int index = 0; index < biomeCount; index++) {
            if (huge[(4 * HUGE_SIZE + 4) * biomeCount + index] > 0.95F) {
                return new BlendChunk(
                        originX,
                        originZ,
                        new int[0],
                        new Object[] { realisticBiomes[index] },
                        null);
            }
        }

        for (int gridX = 0; gridX < 4; gridX++) {
            for (int gridZ = 0; gridZ < 4; gridZ++) {
                mix4(
                        huge,
                        (gridX * 2) * HUGE_SIZE + gridZ * 2,
                        (gridX * 2 + 2) * HUGE_SIZE + gridZ * 2,
                        (gridX * 2) * HUGE_SIZE + gridZ * 2 + 2,
                        (gridX * 2 + 2) * HUGE_SIZE + gridZ * 2 + 2,
                        huge,
                        (gridX * 2 + 1) * HUGE_SIZE + gridZ * 2 + 1,
                        biomeCount);
            }
        }
        float[] small = new float[SMALL_SIZE * SMALL_SIZE * biomeCount];
        for (int gridX = 0; gridX < 7; gridX++) {
            for (int gridZ = 0; gridZ < 7; gridZ++) {
                int targetCell = (gridX * 4) * SMALL_SIZE + gridZ * 4;
                if (!bothEvenOrBothOdd(gridX, gridZ)) {
                    mix4(
                            huge,
                            gridX * HUGE_SIZE + gridZ + 1,
                            (gridX + 1) * HUGE_SIZE + gridZ,
                            (gridX + 1) * HUGE_SIZE + gridZ + 2,
                            (gridX + 2) * HUGE_SIZE + gridZ + 1,
                            small,
                            targetCell,
                            biomeCount);
                } else {
                    System.arraycopy(
                            huge,
                            ((gridX + 1) * HUGE_SIZE + gridZ + 1) * biomeCount,
                            small,
                            targetCell * biomeCount,
                            biomeCount);
                }
            }
        }
        for (int gridX = 0; gridX < 6; gridX++) {
            for (int gridZ = 0; gridZ < 6; gridZ++) {
                mix4(
                        small,
                        (gridX * 4) * SMALL_SIZE + gridZ * 4,
                        (gridX * 4 + 4) * SMALL_SIZE + gridZ * 4,
                        (gridX * 4) * SMALL_SIZE + gridZ * 4 + 4,
                        (gridX * 4 + 4) * SMALL_SIZE + gridZ * 4 + 4,
                        small,
                        (gridX * 4 + 2) * SMALL_SIZE + gridZ * 4 + 2,
                        biomeCount);
            }
        }
        for (int gridX = 0; gridX < 11; gridX++) {
            for (int gridZ = 0; gridZ < 11; gridZ++) {
                if (!bothEvenOrBothOdd(gridX, gridZ)) {
                    mix4(
                            small,
                            (gridX * 2) * SMALL_SIZE + gridZ * 2 + 2,
                            (gridX * 2 + 2) * SMALL_SIZE + gridZ * 2,
                            (gridX * 2 + 2) * SMALL_SIZE + gridZ * 2 + 4,
                            (gridX * 2 + 4) * SMALL_SIZE + gridZ * 2 + 2,
                            small,
                            (gridX * 2 + 2) * SMALL_SIZE + gridZ * 2 + 2,
                            biomeCount);
                }
            }
        }
        for (int gridX = 0; gridX < 9; gridX++) {
            for (int gridZ = 0; gridZ < 9; gridZ++) {
                mix4(
                        small,
                        (gridX * 2 + 2) * SMALL_SIZE + gridZ * 2 + 2,
                        (gridX * 2 + 4) * SMALL_SIZE + gridZ * 2 + 2,
                        (gridX * 2 + 2) * SMALL_SIZE + gridZ * 2 + 4,
                        (gridX * 2 + 4) * SMALL_SIZE + gridZ * 2 + 4,
                        small,
                        (gridX * 2 + 3) * SMALL_SIZE + gridZ * 2 + 3,
                        biomeCount);
            }
        }
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                if (!bothEvenOrBothOdd(localX, localZ)) {
                    mix4(
                            small,
                            (localX + 3) * SMALL_SIZE + localZ + 4,
                            (localX + 4) * SMALL_SIZE + localZ + 3,
                            (localX + 4) * SMALL_SIZE + localZ + 5,
                            (localX + 5) * SMALL_SIZE + localZ + 4,
                            small,
                            (localX + 4) * SMALL_SIZE + localZ + 4,
                            biomeCount);
                }
            }
        }
        return new BlendChunk(originX, originZ, biomeIds, realisticBiomes, small);
    }

    private Object getRawBiome(int x, int z)
            throws IllegalAccessException, InvocationTargetException {
        Long key = Long.valueOf(coordinateKey(x, z));
        Object cached = rawBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        Object biome = getBiomeDataAt.invoke(
                manager,
                Integer.valueOf(x),
                Integer.valueOf(z));
        rawBiomeCache.put(key, biome);
        return biome;
    }

    private BiomeGenBase getBaseBiome(Object realisticBiome) throws IllegalAccessException {
        return (BiomeGenBase) baseBiome.get(realisticBiome);
    }

    private static boolean bothEvenOrBothOdd(int first, int second) {
        return ((first ^ second) & 1) == 0;
    }

    private static void mix4(
            float[] source,
            int firstCell,
            int secondCell,
            int thirdCell,
            int fourthCell,
            float[] target,
            int resultCell,
            int biomeCount) {
        int firstOffset = firstCell * biomeCount;
        int secondOffset = secondCell * biomeCount;
        int thirdOffset = thirdCell * biomeCount;
        int fourthOffset = fourthCell * biomeCount;
        int resultOffset = resultCell * biomeCount;
        for (int index = 0; index < biomeCount; index++) {
            target[resultOffset + index] =
                    (source[firstOffset + index]
                            + source[secondOffset + index]
                            + source[thirdOffset + index]
                            + source[fourthOffset + index])
                            * 0.25F;
        }
    }

    private static float[] createParabolicField() {
        int size = SAMPLE_SIZE * 2 + 1;
        float[] result = new float[size * size];
        float total = 0.0F;
        for (int x = -SAMPLE_SIZE; x <= SAMPLE_SIZE; x++) {
            for (int z = -SAMPLE_SIZE; z <= SAMPLE_SIZE; z++) {
                float weight =
                        0.445F / (float) Math.sqrt(x * x + z * z + 0.3F);
                result[x + SAMPLE_SIZE + (z + SAMPLE_SIZE) * size] = weight;
                total += weight;
            }
        }
        for (int index = 0; index < result.length; index++) {
            result[index] /= total;
        }
        return result;
    }

    private static long coordinateKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    @Override
    public boolean isVillageLocationViable(int x, int z) {
        /*
         * RWG overrides this method with its terrain-height/flatness test
         * and intentionally ignores the vanilla biome list.
         */
        return manager.areBiomesViable(x, z, 0, Collections.emptyList());
    }

    @Override
    public float getApproximateSurfaceHeight(int x, int z) {
        try {
            return ((Float) getNoiseAt.invoke(
                    manager,
                    Integer.valueOf(x),
                    Integer.valueOf(z))).floatValue();
        } catch (IllegalAccessException e) {
            throw reflectionFailure(x, z, e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw reflectionFailure(x, z, cause);
        }
    }

    @Override
    public ChunkPosition findSpawnBiomePosition(Random random) {
        return manager.findBiomePosition(
                0,
                0,
                256,
                manager.getBiomesToSpawnIn(),
                random);
    }

    // Historical baseline only; the production sampler no longer exposes this heuristic.
    public boolean isLikelySpawnCoordinate(int x, int z) {
        BiomeGenBase biome = getBiomeAt(x, z);
        return biome != null && biome.topBlock == Blocks.grass;
    }

    private float getRiverNoise(int x, int z)
            throws IllegalAccessException, InvocationTargetException {
        return ((Float) noise2.invoke(
                perlin,
                Float.valueOf(x / 10.0F),
                Float.valueOf(z / 10.0F))).floatValue();
    }

    private static boolean isRiver(float strength, float noise) {
        return strength > 0.05F && strength + noise * 0.15F > 0.8F;
    }

    private static IllegalStateException reflectionFailure(int x, int z, Throwable cause) {
        return new IllegalStateException(
                "RWG final biome sampling failed at " + x + "," + z,
                cause);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private final class BlendChunk {

        private final int originX;
        private final int originZ;
        private final int[] biomeIds;
        private final Object[] realisticBiomes;
        private final float[] weights;
        private final Object[] selectedBiomes = new Object[16 * 16];

        private BlendChunk(
                int originX,
                int originZ,
                int[] biomeIds,
                Object[] realisticBiomes,
                float[] weights) {
            this.originX = originX;
            this.originZ = originZ;
            this.biomeIds = biomeIds;
            this.realisticBiomes = realisticBiomes;
            this.weights = weights;
        }

        private Object getRealisticBiome(int localX, int localZ)
                throws IllegalAccessException, InvocationTargetException {
            int selectedIndex = localZ * 16 + localX;
            Object cached = selectedBiomes[selectedIndex];
            if (cached != null) {
                return cached;
            }

            Object selected;
            if (weights == null) {
                selected = realisticBiomes[0];
            } else {
                float threshold = 0.5F + getSelectionNoise(originX + localX, originZ + localZ);
                threshold = threshold < 0.0F
                        ? 0.0F
                        : threshold > 0.99999F ? 0.99999F : threshold;
                float cumulative = 0.0F;
                selected = realisticBiomes[realisticBiomes.length - 1];
                int cellOffset =
                        ((localX + 4) * SMALL_SIZE + localZ + 4) * biomeIds.length;
                for (int index = 0; index < biomeIds.length; index++) {
                    float weight = weights[cellOffset + index];
                    if (weight > 0.0F) {
                        cumulative += weight;
                        if (cumulative > threshold) {
                            selected = realisticBiomes[index];
                            break;
                        }
                    }
                }
            }
            selectedBiomes[selectedIndex] = selected;
            return selected;
        }

    }

    private float getSelectionNoise(int x, int z)
            throws IllegalAccessException, InvocationTargetException {
        return ((Float) noise2.invoke(
                perlin,
                Float.valueOf(x / 15.0F),
                Float.valueOf(z / 15.0F))).floatValue();
    }
}

