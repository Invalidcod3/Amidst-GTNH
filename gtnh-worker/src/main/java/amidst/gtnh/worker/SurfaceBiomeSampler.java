package amidst.gtnh.worker;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

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

interface SurfaceBiomeSampler {

    BiomeGenBase getBiomeAt(int x, int z);

    /** Prediction alone, bypassing loaded chunk data, for accuracy diagnostics. */
    default BiomeGenBase getPredictedBiomeAt(int x, int z) {
        return getBiomeAt(x, z);
    }

    default Prediction describePrediction(int x, int z) {
        return new Prediction(null, -1, -1, -1, getPredictedBiomeAt(x, z).biomeID);
    }

    default String predictionStats() { return "chunk-manager"; }

    /** Diagnostic stages only; omitted from normal tile responses. */
    final class Prediction {
        final String realisticType;
        final int realisticId, baseId, riverId, predictedId;
        Prediction(String type, int realisticId, int baseId, int riverId, int predictedId) {
            this.realisticType = type;
            this.realisticId = realisticId;
            this.baseId = baseId;
            this.riverId = riverId;
            this.predictedId = predictedId;
        }
    }

    boolean isVillageLocationViable(int x, int z);

    float getApproximateSurfaceHeight(int x, int z);

    ChunkPosition findSpawnBiomePosition(Random random);

    boolean isLikelySpawnCoordinate(int x, int z);
}

final class SurfaceBiomeSamplers {

    static final String RWG_CHUNK_MANAGER = "rwg.world.ChunkManagerRealistic";
    static final String OVERWORLD_PROVIDER = "net.minecraft.world.WorldProviderSurface";

    private SurfaceBiomeSamplers() {}

    static SurfaceBiomeSampler create(WorldServer world) {
        WorldChunkManager manager = world.getWorldChunkManager();
        if (isRwg(manager)) {
            try {
                SurfaceBiomeSampler sampler = new RwgSurfaceBiomeSampler(world, manager);
                AmidstGtnhWorkerLog.LOG.info(
                        "GTNH biome worker is using RWG adaptive biome dependencies with native chunk replay for sensitive/unknown surfaces");
                return sampler;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "RWG is active, but its final river biome API could not be initialized",
                        e);
            }
        }
        return new ChunkManagerSurfaceBiomeSampler(manager);
    }

    static SurfaceBiomeSampler createRwg(long seed) {
        WorldType worldType = WorldType.parseWorldType("RWG");
        if (worldType == null) {
            throw new IllegalStateException("RWG world type is not registered");
        }

        SeedOnlyWorld world = new SeedOnlyWorld(seed, worldType);
        WorldChunkManager manager = world.getWorldChunkManager();
        if (!isRwg(manager)) {
            throw new IllegalStateException(
                    "RWG world type returned unsupported chunk manager " + manager.getClass().getName());
        }
        try {
            return new RwgSurfaceBiomeSampler(null, manager, world, seed);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "RWG final river biome API could not be initialized for seed " + seed,
                    e);
        }
    }

    static boolean isRwg(WorldChunkManager manager) {
        return manager != null && hasTypeNamed(manager.getClass(), RWG_CHUNK_MANAGER);
    }

    private static boolean hasTypeNamed(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            if (name.equals(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static final class ChunkManagerSurfaceBiomeSampler implements SurfaceBiomeSampler {

        private final WorldChunkManager manager;

        private ChunkManagerSurfaceBiomeSampler(WorldChunkManager manager) {
            this.manager = manager;
        }

        @Override
        public BiomeGenBase getBiomeAt(int x, int z) {
            return manager.getBiomeGenAt(x, z);
        }

        @Override
        public boolean isVillageLocationViable(int x, int z) {
            return manager.areBiomesViable(x, z, 0, Collections.emptyList());
        }

        @Override
        public float getApproximateSurfaceHeight(int x, int z) {
            return 64.0F;
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

        @Override
        public boolean isLikelySpawnCoordinate(int x, int z) {
            BiomeGenBase biome = getBiomeAt(x, z);
            return biome != null && biome.topBlock == Blocks.grass;
        }
    }

    private static final class RwgSurfaceBiomeSampler implements SurfaceBiomeSampler {

        private static final int SAMPLE_SIZE = 8;
        private static final int SAMPLE_ARRAY_SIZE = SAMPLE_SIZE * 2 + 5;
        private static final int HUGE_SIZE = 9;
        private static final int SMALL_SIZE = 25;
        private static final int MAX_CACHED_RAW_BIOMES = 65_536;
        private static final int MAX_CACHED_BLEND_CHUNKS = 2_048;
        private static final int MAX_CACHED_BLEND_WEIGHTS = 16_384;
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
        private final Class<?> noiseType;
        private final World previewWorld;
        private final long seed;
        private RwgChunkBiomeReplay replay;
        private RwgSurfaceEffects surfaceEffects = new RwgSurfaceEffects(RwgRuntimeClasses::get);
        private final boolean forceFullReplay = Boolean.getBoolean("gtnh.amidst.worker.fullReplay");
        private long biomeOnlyChunks, nativeReplayChunks;
        // Resolve registered terrain and surface signatures once, when first needed.
        private Object cell;
        private Method biomeNoise;
        private RwgTerrainAccess terrainAccess;
        private final RwgBiomeGrid rawGrid = new RwgBiomeGrid(MAX_CACHED_RAW_BIOMES / 256);
        private final RwgBiomeGrid.Source rawGridSource = this::sampleGridPoint;
        private final Map<Long, BlendChunk> blendChunkCache =
                new LinkedHashMap<Long, BlendChunk>(256, 0.75F, true) {

                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, BlendChunk> eldest) {
                        return size() > MAX_CACHED_BLEND_CHUNKS;
                    }
                };
        // Neighbouring chunks share their eight-block-grid convolution points.
        // Keep exact sparse weights, not a selected biome, so interpolation and
        // stochastic selection retain the same floating-point operations.
        private final Map<Long, BlendWeights> blendWeightCache =
                new LinkedHashMap<Long, BlendWeights>(1_024, 0.75F, true) {

                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Long, BlendWeights> eldest) {
                        return size() > MAX_CACHED_BLEND_WEIGHTS;
                    }
                };

        private RwgSurfaceBiomeSampler(WorldServer world, WorldChunkManager manager)
                throws ReflectiveOperationException {
            this(world, manager, world == null ? null : new SeedOnlyWorld(world.getSeed(),
                    world.getWorldInfo().getTerrainType(), manager), world == null ? 0 : world.getSeed());
        }

        private RwgSurfaceBiomeSampler(WorldServer world, WorldChunkManager manager, World previewWorld, long seed)
                throws ReflectiveOperationException {
            this.world = world;
            this.manager = manager;
            this.previewWorld = previewWorld;
            this.seed = seed;

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
            noiseType = perlinField.getType();
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

            return getPredictedBiomeAt(x, z);
        }

        @Override
        public BiomeGenBase getPredictedBiomeAt(int x, int z) {
            try {
                int chunkX = Math.floorDiv(x, 16), chunkZ = Math.floorDiv(z, 16);
                BlendChunk chunk = getBlendChunk(chunkX, chunkZ);
                if (chunk.needsReplay == null) {
                    chunk.needsReplay = forceFullReplay || requiresSurfaceReplay(chunk);
                    if (chunk.needsReplay) nativeReplayChunks++; else biomeOnlyChunks++;
                }
                if (chunk.finalBiomes == null) {
                    if (chunk.needsReplay) {
                        chunk.finalBiomes = replayChunk(chunkX, chunkZ, chunk);
                        chunk.terrainBlend = null;
                    } else {
                        chunk.finalBiomes = new BiomeGenBase[256];
                    }
                }
                int index = Math.floorMod(z, 16) * 16 + Math.floorMod(x, 16);
                if (chunk.finalBiomes[index] == null) {
                    // Exact RWG selection and river equation, computed only at
                    // requested pixels. No terrain is needed when NO callback
                    // in the entire chunk can access its final biome array.
                    Object selected = chunk.getRealisticBiome(Math.floorMod(x, 16), Math.floorMod(z, 16));
                    float river = -(Float) getRiverStrength.invoke(manager, x, z);
                    chunk.finalBiomes[index] = river > 0.05F && isRiver(river, getRiverNoise(x, z))
                            ? (BiomeGenBase) riverBiome.get(selected) : getBaseBiome(selected);
                }
                return chunk.finalBiomes[index];
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                throw reflectionFailure(x, z, cause);
            } catch (ReflectiveOperationException e) {
                throw reflectionFailure(x, z, e);
            }
        }

        private boolean requiresSurfaceReplay(BlendChunk chunk) throws ReflectiveOperationException {
            // Prove the candidate superset first: a map usually needs only 16
            // pixels, so selecting all 256 columns would itself waste noise work.
            boolean allCandidatesSafe = true;
            for (Object candidate : chunk.realisticBiomes) {
                if (!surfaceEffects.leavesBiomesUntouched(candidate)) { allCandidatesSafe = false; break; }
            }
            if (allCandidatesSafe) return false;
            // Check every column, including unsampled pixels: a callback may
            // change another column. Keep whole-chunk RNG/map/surface order on
            // ANY uncertain or biome-writing callback, even far from a border.
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!surfaceEffects.leavesBiomesUntouched(chunk.getRealisticBiome(x, z))) return true;
            }
            return false;
        }

        @Override public String predictionStats() {
            return "biomeOnlyChunks=" + biomeOnlyChunks + ", nativeReplayChunks=" + nativeReplayChunks
                    + ", forceFullReplay=" + forceFullReplay;
        }

        @Override
        public Prediction describePrediction(int x, int z) {
            int predicted = getPredictedBiomeAt(x, z).biomeID;
            try {
                Object selected = getBlendChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16))
                        .getRealisticBiome(Math.floorMod(x, 16), Math.floorMod(z, 16));
                int base = getBaseBiome(selected).biomeID;
                float strength = -(Float) getRiverStrength.invoke(manager, x, z);
                int river = isRiver(strength, getRiverNoise(x, z))
                        ? ((BiomeGenBase) riverBiome.get(selected)).biomeID : base;
                return new Prediction(selected.getClass().getName(), realisticBiomeId.getInt(selected), base, river, predicted);
            } catch (ReflectiveOperationException e) {
                throw reflectionFailure(x, z, e);
            }
        }

        private BiomeGenBase[] replayChunk(int chunkX, int chunkZ, BlendChunk chunk)
                throws ReflectiveOperationException {
            initializeTerrainAccess();
            Object[] realistic = new Object[256];
            BiomeGenBase[] base = new BiomeGenBase[256], rivers = new BiomeGenBase[256];
            float[] heights = new float[256], rawRivers = new float[256];
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    int bx = chunkX * 16 + x, bz = chunkZ * 16 + z;
                    int index = z * 16 + x, column = x * 16 + z;
                    realistic[index] = chunk.getRealisticBiome(x, z);
                    base[index] = getBaseBiome(realistic[index]);
                    rivers[index] = (BiomeGenBase) riverBiome.get(realistic[index]);
                    rawRivers[column] = terrainAccess.river(bx, bz);
                    heights[column] = getBlendedTerrainHeight(bx, bz, rawRivers[column]);
                }
            }
            if (replay == null) {
                Field cellField = findField(manager.getClass(), "cell");
                replay = new RwgChunkBiomeReplay(previewWorld, seed, manager, perlin, cell,
                        getBiomeDataAt.getReturnType(), getBiomeDataAt.getDeclaringClass(), noiseType, cellField.getType());
            }
            BlendChunk terrain = chunk.weights == null ? chunk.terrainBlend : chunk;
            ArrayList<Object> mapBiomes = new ArrayList<>();
            int center = (12 * SMALL_SIZE + 12) * terrain.biomeIds.length;
            for (int i = 0; i < terrain.biomeIds.length; i++) {
                if (terrain.weights[center + i] > 0.0F) mapBiomes.add(terrain.realisticBiomes[i]);
            }
            return replay.replay(chunkX, chunkZ, realistic, base, rivers, heights, rawRivers, mapBiomes.toArray());
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
            return createBlendChunk(chunkX, chunkZ, false);
        }

        private BlendChunk createBlendChunk(int chunkX, int chunkZ, boolean requireTerrainWeights)
                throws IllegalAccessException, InvocationTargetException {
            int originX = chunkX << 4;
            int originZ = chunkZ << 4;
            int[] sampledIds = new int[SAMPLE_ARRAY_SIZE * SAMPLE_ARRAY_SIZE];
            Object[] biomesById = new Object[256];
            int biomeCount = 0;

            for (int sampleX = -SAMPLE_SIZE; sampleX < SAMPLE_SIZE + 5; sampleX++) {
                for (int sampleZ = -SAMPLE_SIZE; sampleZ < SAMPLE_SIZE + 5; sampleZ++) {
                    int index = (sampleX + SAMPLE_SIZE) * SAMPLE_ARRAY_SIZE
                            + sampleZ + SAMPLE_SIZE;
                    RwgBiomeGrid.Entry entry = rawGrid.get(
                            originX + sampleX * 8 - 8, originZ + sampleZ * 8 - 8, rawGridSource);
                    int biomeId = entry.id;
                    sampledIds[index] = biomeId;
                    if (biomesById[biomeId] == null) biomeCount++;
                    // Preserve TreeMap.put's last-value semantics and ascending ID order.
                    biomesById[biomeId] = entry.biome;
                }
            }


            int[] biomeIds = new int[biomeCount];
            Object[] realisticBiomes = new Object[biomeCount];
            int[] idToIndex = new int[256];
            Arrays.fill(idToIndex, -1);
            int biomeIndex = 0;
            for (int biomeId = 0; biomeId < biomesById.length; biomeId++) {
                if (biomesById[biomeId] == null) continue;
                biomeIds[biomeIndex] = biomeId;
                realisticBiomes[biomeIndex] = biomesById[biomeId];
                idToIndex[biomeId] = biomeIndex;
                biomeIndex++;
            }

            float[] huge = new float[HUGE_SIZE * HUGE_SIZE * biomeCount];
            // RWG's >95% rule depends only on the centre. Evaluate it before
            // the other 24 convolution points or any interpolation arrays.
            fillBlendWeights(huge, 1, 1, originX, originZ, sampledIds, biomeIds, idToIndex);

            for (int index = 0; index < biomeCount; index++) {
                if (!requireTerrainWeights && huge[(4 * HUGE_SIZE + 4) * biomeCount + index] > 0.95F) {
                    return new BlendChunk(
                            originX,
                            originZ,
                            new int[0],
                            new Object[] { realisticBiomes[index] },
                            null);
                }
            }

            for (int gridX = -1; gridX < 4; gridX++) {
                for (int gridZ = -1; gridZ < 4; gridZ++) {
                    if (gridX != 1 || gridZ != 1) {
                        fillBlendWeights(huge, gridX, gridZ, originX, originZ, sampledIds, biomeIds, idToIndex);
                    }
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

        private void initializeTerrainAccess() throws ReflectiveOperationException {
            if (terrainAccess == null) {
                Field cellField = findField(manager.getClass(), "cell");
                cellField.setAccessible(true);
                cell = cellField.get(manager);
                biomeNoise = getBiomeDataAt.getReturnType().getMethod("rNoise", noiseType,
                        cellField.getType(), int.class, int.class, float.class, float.class, float.class);
                terrainAccess = new RwgTerrainAccess(manager, perlin, cell, biomeNoise);
            }
        }

        /** The generator's blended height, NOT ChunkManagerRealistic.getNoiseAt's raw-biome approximation. */
        private float getBlendedTerrainHeight(int x, int z, float rawRiver) throws ReflectiveOperationException {
            initializeTerrainAccess();
            BlendChunk blend = getBlendChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            if (blend.weights == null) {
                // >95% selects one biome, but RWG STILL blends all neighbours
                // for height. Expand when replay needs complete terrain inputs.
                if (blend.terrainBlend == null) {
                    blend.terrainBlend = createBlendChunk(Math.floorDiv(x, 16), Math.floorDiv(z, 16), true);
                }
                blend = blend.terrainBlend;
            }
            int localX = Math.floorMod(x, 16);
            int localZ = Math.floorMod(z, 16);
            int offset = ((localX + 4) * SMALL_SIZE + localZ + 4) * blend.biomeIds.length;
            float ocean = terrainAccess.ocean(x, z);
            float height = 0.0F;
            for (int index = 0; index < blend.biomeIds.length; index++) {
                float weight = blend.weights[offset + index];
                if (weight > 0.0F) {
                    float rawHeight = terrainAccess.terrain(blend.realisticBiomes[index],
                            x, z, ocean, weight, rawRiver + 1.0F);
                    height += terrainAccess.calculateRiver(x, z, rawRiver, rawHeight) * weight;
                }
            }
            return height;
        }

        /** Reuses a global convolution point while translating its sparse ids into this chunk's compact array. */
        private void fillBlendWeights(
                float[] target,
                int gridX,
                int gridZ,
                int originX,
                int originZ,
                int[] sampledIds,
                int[] biomeIds,
                int[] idToIndex) {
            Long key = Long.valueOf(coordinateKey(originX + gridX * 8, originZ + gridZ * 8));
            BlendWeights cached = blendWeightCache.get(key);
            if (cached == null) {
                float[] weights = new float[biomeIds.length];
                // Retain the original X-then-Z accumulation order bit for bit.
                for (int offsetX = -SAMPLE_SIZE; offsetX <= SAMPLE_SIZE; offsetX++) {
                    for (int offsetZ = -SAMPLE_SIZE; offsetZ <= SAMPLE_SIZE; offsetZ++) {
                        int sampleIndex = (gridX + offsetX + SAMPLE_SIZE + 1) * SAMPLE_ARRAY_SIZE
                                + gridZ + offsetZ + SAMPLE_SIZE + 1;
                        weights[idToIndex[sampledIds[sampleIndex]]] += PARABOLIC_FIELD[
                                offsetX + SAMPLE_SIZE + (offsetZ + SAMPLE_SIZE) * (SAMPLE_SIZE * 2 + 1)];
                    }
                }
                cached = new BlendWeights(biomeIds, weights);
                blendWeightCache.put(key, cached);
            }
            int resultOffset = ((gridX * 2 + 2) * HUGE_SIZE + gridZ * 2 + 2) * biomeIds.length;
            for (int index = 0; index < cached.biomeIds.length; index++) {
                float weight = cached.weights[index];
                if (weight > 0.0F) {
                    // Zero-weight ids may come from a previous chunk's wider
                    // neighbourhood and need not occur in the current chunk.
                    target[resultOffset + idToIndex[cached.biomeIds[index]]] = weight;
                }
            }
        }

        private static final class BlendWeights {
            private final int[] biomeIds;
            private final float[] weights;

            private BlendWeights(int[] biomeIds, float[] weights) {
                this.biomeIds = biomeIds;
                this.weights = weights;
            }
        }

        private RwgBiomeGrid.Entry sampleGridPoint(int x, int z)
                throws IllegalAccessException, InvocationTargetException {
            Object biome = getBiomeDataAt.invoke(manager, x, z);
            int id = realisticBiomeId.getInt(biome);
            if (id < 0 || id > 255) throw new IllegalStateException("Unsupported RWG realistic biome id: " + id);
            return new RwgBiomeGrid.Entry(biome, id);
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
            // Long.hashCode on a plain packed pair is x ^ z. On RWG's grid
            // thousands of coordinates otherwise land in a few hash buckets.
            // This reversible 64-bit mix preserves key uniqueness and spreads
            // the low/high bits before LinkedHashMap applies Long.hashCode.
            long key = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
            key = (key ^ (key >>> 33)) * 0xff51afd7ed558ccdL;
            key = (key ^ (key >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return key ^ (key >>> 33);
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

        @Override
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
            private BlendChunk terrainBlend;
            private BiomeGenBase[] finalBiomes;
            private Boolean needsReplay;
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

    /**
     * Lightweight, save-free world used by RWG's chunk manager and terrain
     * generators. It deliberately has no chunk provider; biome lookups must
     * therefore bypass World#getChunkFromBlockCoords and go straight to the
     * registered WorldChunkManager.
     */
    static final class SeedOnlyWorld extends World {

        private final long seed;
        private WorldChunkManager predictionManager;

        SeedOnlyWorld(long seed, WorldType worldType) {
            super(
                    null,
                    "Amidst seed preview",
                    new SeedOnlyWorldProvider(),
                    new WorldSettings(seed, WorldSettings.GameType.CREATIVE, false, false, worldType),
                    new Profiler());
            this.seed = seed;
            provider.registerWorld(this);
        }

        SeedOnlyWorld(long seed, WorldType worldType, WorldChunkManager manager) {
            this(seed, worldType);
            // Preserve the active world's noise selection and manager settings.
            predictionManager = manager;
        }

        @Override
        public WorldChunkManager getWorldChunkManager() {
            return predictionManager == null ? super.getWorldChunkManager() : predictionManager;
        }

        @Override
        public long getSeed() {
            return seed;
        }

        @Override
        public BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return getWorldChunkManager().getBiomeGenAt(x, z);
        }

        @Override
        protected IChunkProvider createChunkProvider() {
            return null;
        }

        @Override
        protected int func_152379_p() {
            return 0;
        }

        @Override
        public Entity getEntityByID(int entityId) {
            return null;
        }
    }

    private static final class SeedOnlyWorldProvider extends WorldProvider {

        @Override
        public String getDimensionName() {
            return "Amidst seed preview";
        }
    }
}
