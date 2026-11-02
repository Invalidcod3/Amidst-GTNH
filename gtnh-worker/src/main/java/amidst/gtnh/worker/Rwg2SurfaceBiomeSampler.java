package amidst.gtnh.worker;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

import java.lang.reflect.*;
import java.util.*;

/**
 * Detached replay of RWG2 2.0.6's final biome assignment, including rivers and lava cave markers.
 */
final class Rwg2SurfaceBiomeSampler implements SurfaceBiomeSampler {
    private final Object generator;
    private final WorldChunkManager manager;
    private final World previewWorld;
    private final long seed;
    private final Block[] blocks;
    private final byte[] metadata;
    private final Object[] realistic;
    private final BiomeGenBase[] base;
    private final float[] heights, mapGenBiomes;
    private final Random random, mapRandom;
    private final Object perlin, cell;
    private final Method terrain, noise, carve, replace, marker, getBiome, mapGen;
    private final boolean fastTerrain;
    private final Field baseBiome;
    private final Rwg2BiomeCache biomeSamples;
    private final Field lavaCaveMarker;
    private final Class<?> oceanClass;
    private final Method continentAt, volcanoAt;
    private final boolean oceanShortcut;
    private long oceanChunks;
    private boolean profile = Boolean.getBoolean("gtnh.amidst.worker.profileTerrain");
    private final long[] phaseNanos = new long[6];
    private Rwg2StructurePredictor structures;
    private Object villageGenerator;
    private RwgSpawnCaves caves;
    private Method lavaTerrain, magmaTerrain;
    private Class<?> volcanoClass;
    private Field volcanoIsland;
    private final Map<Long, SpawnSearch.Surface[]> spawnSurfaces =
            new LinkedHashMap<Long, SpawnSearch.Surface[]>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, SpawnSearch.Surface[]> entry) {
                    return size() > 256;
                }
            };
    private final Map<Long, Tile> tiles =
            new LinkedHashMap<Long, Tile>(64, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, Tile> entry) {
                    return size() > 1024;
                }
            };

    Rwg2SurfaceBiomeSampler(long seed, boolean simplex) {
        this(seed, simplex, true);
    }

    Rwg2SurfaceBiomeSampler(long seed, boolean simplex, boolean cacheBiomeSamples) {
        this(seed, simplex, cacheBiomeSamples, true);
    }

    Rwg2SurfaceBiomeSampler(
            long seed, boolean simplex, boolean cacheBiomeSamples, boolean optimized) {
        this.fastTerrain = optimized;
        this.seed = seed;
        try {
            manager = Rwg2Runtime.manager(seed, simplex);
            biomeSamples = new Rwg2BiomeCache(manager, cacheBiomeSamples, optimized);
            oceanShortcut = cacheBiomeSamples;
            oceanClass = Class.forName("rwg2.biomes.realistic.ocean.RealisticBiomeOcean");
            Rwg2Runtime.verify(
                    Rwg2Runtime.bytes(oceanClass),
                    "3ef2e11ccb643348e66a54c3af0fe0b7efe5881b730b57ac6ac2d4279bd54715");
            continentAt = manager.getClass().getMethod("getContinentValue", int.class, int.class);
            volcanoAt =
                    manager.getClass()
                            .getMethod("getVolcanoVicinityCoordinates", int.class, int.class);
            lavaCaveMarker = Class.forName("rwg2.support.Support").getField("lavaCaveMarkerBiome");
            generator = Rwg2Runtime.generator(manager, seed, simplex);
            // DEFAULT prevents WorldProvider from constructing a second, stateful RWG2 manager.
            previewWorld = new SurfaceBiomeSamplers.SeedOnlyWorld(seed, WorldType.DEFAULT, manager);
            Rwg2Runtime.set(generator, "worldObj", previewWorld);
            blocks = (Block[]) Rwg2Runtime.get(generator, "chunkBlocks");
            metadata = (byte[]) Rwg2Runtime.get(generator, "chunkMetadata");
            realistic = (Object[]) Rwg2Runtime.get(generator, "biomesForGeneration");
            base = (BiomeGenBase[]) Rwg2Runtime.get(generator, "baseBiomesList");
            heights = (float[]) Rwg2Runtime.get(generator, "testHeight");
            mapGenBiomes = (float[]) Rwg2Runtime.get(generator, "mapGenBiomes");
            random = (Random) Rwg2Runtime.get(generator, "rand");
            mapRandom = (Random) Rwg2Runtime.get(generator, "mapRand");
            perlin = Rwg2Runtime.get(generator, "perlin");
            cell = Rwg2Runtime.get(generator, "cell");
            Class<?> biome = realistic.getClass().getComponentType();
            baseBiome = biome.getField("baseBiome");
            getBiome = biome.getMethod("getBiome", int.class);
            mapGen =
                    biome.getMethod(
                            "generateMapGen",
                            Block[].class,
                            byte[].class,
                            Long.class,
                            World.class,
                            manager.getClass(),
                            Random.class,
                            int.class,
                            int.class,
                            Class.forName("rwg2.util.NoiseGenerator"),
                            cell.getClass(),
                            float[].class);
            terrain =
                    method(
                            "generateTerrain",
                            manager.getClass(),
                            int.class,
                            int.class,
                            Block[].class,
                            byte[].class,
                            realistic.getClass());
            noise =
                    method(
                            "getNewNoise",
                            manager.getClass(),
                            int.class,
                            int.class,
                            realistic.getClass());
            carve =
                    method(
                            "carveMountainChainRivers",
                            int.class,
                            int.class,
                            Block[].class,
                            byte[].class);
            replace =
                    method(
                            "replaceBlocksForBiome",
                            int.class,
                            int.class,
                            Block[].class,
                            byte[].class,
                            realistic.getClass(),
                            BiomeGenBase[].class,
                            float[].class);
            marker = method("markLavaCaveOpeningBiome", int.class, int.class, manager.getClass());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cannot initialize RWG2 Smooth (requires RWG2 2.0.6)", e);
        }
    }

    /** Replays underground terrain into scratch storage; never generates or loads a world chunk. */
    synchronized Block[] dungeonTerrain(int chunkX, int chunkZ) {
        replayUnderground(chunkX, chunkZ);
        return blocks.clone();
    }

    private void replayUnderground(int chunkX, int chunkZ) {
        tile(chunkX * 16, chunkZ * 16, true, true);
        try {
            prepareUnderground();
            caves.carve(chunkX, chunkZ, blocks);
            Object volcano = volcanoIsland.get(null);
            if (volcanoClass.isInstance(volcano))
                magmaTerrain.invoke(volcano, blocks, metadata, chunkX, chunkZ, manager);
            lavaTerrain.invoke(null, blocks, metadata, chunkX, chunkZ, manager, perlin, cell);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot replay RWG2 underground terrain", e);
        }
    }

    // Called on the game thread before handing this exclusive context to the POI worker.
    synchronized boolean prepareUnderground() {
        try {
            if (caves == null) {
                Class<?> lava = Class.forName("rwg2.map.LavaCaveLandmark");
                volcanoClass =
                        Class.forName("rwg2.biomes.realistic.ocean.RealisticBiomeIslandVolcano");
                Rwg2Runtime.verify(
                        Rwg2Runtime.bytes(lava),
                        "f24532b0c274d79be5a3f905736eaa6af08e86b1079a6ce6d1c93ad7dab4736a");
                Rwg2Runtime.verify(
                        Rwg2Runtime.bytes(volcanoClass),
                        "c59524f7e1d926522617d5e595c72649ed7dc5a5ee470d5e2d73191708ddca8a");
                volcanoIsland = Class.forName("rwg2.support.Support").getField("volcanoIsland");
                magmaTerrain =
                        volcanoClass.getMethod(
                                "generateMagmaChamber",
                                Block[].class,
                                byte[].class,
                                int.class,
                                int.class,
                                manager.getClass());
                lavaTerrain =
                        lava.getMethod(
                                "generate",
                                Block[].class,
                                byte[].class,
                                int.class,
                                int.class,
                                manager.getClass(),
                                Class.forName("rwg2.util.NoiseGenerator"),
                                cell.getClass());
                caves = RwgSpawnCaves.create(previewWorld, "rwg2.config.ConfigRWG");
            }
            return caves.supportsBackground();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot prepare RWG2 cave replay", e);
        }
    }

    private Method method(String name, Class<?>... parameters) throws NoSuchMethodException {
        Method m = generator.getClass().getDeclaredMethod(name, parameters);
        m.setAccessible(true);
        return m;
    }

    private synchronized Tile tile(int x, int z) {
        return tile(x, z, false);
    }

    private synchronized Tile tile(int x, int z, boolean requireHeights) {
        return tile(x, z, requireHeights, false);
    }

    private Tile tile(int x, int z, boolean requireHeights, boolean requireBlocks) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
        Tile cached = tiles.get(key);
        if (!requireBlocks && cached != null && (!requireHeights || cached.heights != null))
            return cached;
        Rwg2BiomeCache previous = Rwg2Runtime.sampling(biomeSamples);
        try {
            long phaseStart = profile ? System.nanoTime() : 0;
            boolean ocean = oceanShortcut && !requireHeights && homogeneousOcean(cx, cz);
            phaseStart = recordPhase(0, phaseStart);
            if (ocean) {
                if (lavaCaveMarker.get(null) != null) marker.invoke(generator, cx, cz, manager);
                recordPhase(5, phaseStart);
                Tile result = new Tile(base.clone(), null);
                tiles.put(key, result);
                oceanChunks++;
                return result;
            }
            random.setSeed((long) cx * 0x4f9939f508L + (long) cz * 0x1ef1565bd5L);
            // The contiguous fill overwrites every block, so clearing it first is redundant.
            if (!fastTerrain) Arrays.fill(blocks, null);
            Arrays.fill(metadata, (byte) 0);
            Arrays.fill(realistic, null);
            if (fastTerrain) {
                float[] columns =
                        (float[]) noise.invoke(generator, manager, cx * 16, cz * 16, realistic);
                Rwg2TerrainFill.fill(blocks, columns, Blocks.stone, Blocks.water, Blocks.air);
            } else terrain.invoke(generator, manager, cx, cz, blocks, metadata, realistic);
            phaseStart = recordPhase(1, phaseStart);
            carve.invoke(generator, cx, cz, blocks, metadata);
            phaseStart = recordPhase(2, phaseStart);
            for (int i = 0; i < 256; i++) {
                if (mapGenBiomes[i] > 0) {
                    mapGen.invoke(
                            getBiome.invoke(null, i),
                            blocks,
                            metadata,
                            seed,
                            previewWorld,
                            manager,
                            mapRandom,
                            cx,
                            cz,
                            perlin,
                            cell,
                            heights);
                    mapGenBiomes[i] = 0;
                }
                base[i] = (BiomeGenBase) baseBiome.get(realistic[i]);
            }
            phaseStart = recordPhase(3, phaseStart);
            replace.invoke(generator, cx, cz, blocks, metadata, realistic, base, heights);
            phaseStart = recordPhase(4, phaseStart);
            // Caves/structures/magma change blocks but not the final biome array. The last
            // biome-changing pass is independent of those blocks and must still be replayed.
            if (lavaCaveMarker.get(null) != null) marker.invoke(generator, cx, cz, manager);
            recordPhase(5, phaseStart);
            Tile result = new Tile(base.clone(), heights.clone());
            tiles.put(key, result);
            return result;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "RWG2 terrain replay failed at " + cx + "," + cz,
                    e instanceof InvocationTargetException
                            ? ((InvocationTargetException) e).getCause()
                            : e);
        } finally {
            Rwg2Runtime.sampling(previous);
        }
    }

    private long recordPhase(int phase, long start) {
        if (!profile) return 0;
        long now = System.nanoTime();
        phaseNanos[phase] += now - start;
        return now;
    }

    private boolean homogeneousOcean(int cx, int cz) throws ReflectiveOperationException {
        int x = cx * 16, z = cz * 16;
        Object center = biomeSamples.sample(manager, x + 8, z + 8);
        if (center.getClass() != oceanClass) return false;
        BiomeGenBase expected = (BiomeGenBase) baseBiome.get(center);
        // These are ALL of native getNewNoise's blend inputs, not a sparse guess.
        // A coast, island or different ocean base biome falls back to the full replay.
        for (int dx = -72; dx <= 88; dx += 8)
            for (int dz = -72; dz <= 88; dz += 8) {
                Object biome = biomeSamples.sample(manager, x + dx, z + dz);
                if (biome.getClass() != oceanClass || baseBiome.get(biome) != expected)
                    return false;
            }
        for (int dx = 0; dx < 16; dx++)
            for (int dz = 0; dz < 16; dz++) {
                // Inland river reassignment and volcanic surface overlays are the remaining
                // native ways to change this base biome. Keep every column's original checks.
                if ((Float) continentAt.invoke(manager, x + dx, z + dz) >= 24f
                        || (Long) volcanoAt.invoke(manager, x + dx, z + dz) != Long.MIN_VALUE)
                    return false;
            }
        Arrays.fill(base, expected);
        return true;
    }

    @Override
    public BiomeGenBase getBiomeAt(int x, int z) {
        return getPredictedBiomeAt(x, z);
    }

    @Override
    public BiomeGenBase getPredictedBiomeAt(int x, int z) {
        return tile(x, z).biomes[(z & 15) * 16 + (x & 15)];
    }

    @Override
    public float getApproximateSurfaceHeight(int x, int z) {
        return tile(x, z, true).heights[(x & 15) * 16 + (z & 15)];
    }

    @Override
    public boolean isVillageLocationViable(int x, int z) {
        return manager.areBiomesViable(x, z, 0, Collections.emptyList());
    }

    @Override
    public synchronized Object getVillageGenerator() {
        if (villageGenerator == null) {
            Map<String, String> parameters = new HashMap<>();
            parameters.put("size", "0");
            parameters.put("distance", "24");
            villageGenerator =
                    net.minecraftforge.event.terraingen.TerrainGen.getModdedMapGen(
                            new net.minecraft.world.gen.structure.MapGenVillage(parameters),
                            net.minecraftforge.event.terraingen.InitMapGenEvent.EventType.VILLAGE);
        }
        return villageGenerator;
    }

    @Override
    public ChunkPosition findSpawnBiomePosition(Random random) {
        // Native RWG2 returns null here and consumes no randomness. Keep the native call
        // rather than choosing a nearby biome, which would shift the entire random walk.
        return manager.findBiomePosition(0, 0, 256, manager.getBiomesToSpawnIn(), random);
    }

    @Override
    public synchronized SpawnSearch.Surface getSpawnSurface(int x, int z) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        long key = (long) cx << 32 ^ cz & 0xffffffffL;
        SpawnSearch.Surface[] surfaces = spawnSurfaces.get(key);
        if (surfaces == null) {
            replayUnderground(cx, cz);
            surfaces = new SpawnSearch.Surface[256];
            List<?> spawnBiomes = manager.getBiomesToSpawnIn();
            for (int dx = 0; dx < 16; dx++)
                for (int dz = 0; dz < 16; dz++) {
                    int offset = (dx * 16 + dz) * 256, y = 63;
                    // World.getTopBlock scans upward from sea level, not down from the sky.
                    while (y < 255
                            && blocks[offset + y + 1] != null
                            && blocks[offset + y + 1] != Blocks.air) y++;
                    BiomeGenBase biome = base[dz * 16 + dx];
                    surfaces[dz * 16 + dx] =
                            new SpawnSearch.Surface(
                                    blocks[offset + y], biome, y, spawnBiomes.contains(biome));
                }
            spawnSurfaces.put(key, surfaces);
        }
        return surfaces[(z & 15) * 16 + (x & 15)];
    }

    @Override
    public String predictionStats() {
        return "rwg2-2.0.6-detached-surface-replay; blendHits="
                + biomeSamples.hits
                + "; blendMisses="
                + biomeSamples.misses
                + "; chunks="
                + tiles.size()
                + "; oceanFastChunks="
                + oceanChunks
                + (profile
                        ? "; phaseNanos[ocean,terrain,riverCarve,mapGen,surface,marker]="
                                + Arrays.toString(phaseNanos)
                        : "");
    }

    WorldChunkManager manager() {
        return manager;
    }

    Rwg2StructurePredictor structures() {
        if (structures == null) structures = new Rwg2StructurePredictor(this, seed);
        return structures;
    }

    private static final class Tile {
        final BiomeGenBase[] biomes;
        final float[] heights;

        Tile(BiomeGenBase[] biomes, float[] heights) {
            this.biomes = biomes;
            this.heights = heights;
        }
    }
}
