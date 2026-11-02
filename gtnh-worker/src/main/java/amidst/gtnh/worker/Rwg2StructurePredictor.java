package amidst.gtnh.worker;

import cpw.mods.fml.common.Loader;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.WorldServer;

import java.lang.reflect.Method;
import java.util.*;

/** RWG2 landmark centers and VillageNames monument candidates; never populates chunks. */
final class Rwg2StructurePredictor {
    interface Terrain {
        long lavaCenter(int x, int z);

        boolean volcano(int x, int z);

        int[] riverCenter(int chunkBlockX, int chunkBlockZ);

        String biomeName(int x, int z);
    }

    private final Terrain terrain;
    private final long seed;
    private final boolean decorations, loot, twilight, monuments;
    private final Block puzzleBlock, twilightBlock;

    private static final class ChunkCandidates {
        int computed;
        final List<RoguelikeDungeonPredictor.StructureDescriptor> result = new ArrayList<>();
    }

    private final Map<Long, ChunkCandidates> chunks =
            new LinkedHashMap<Long, ChunkCandidates>(256, .75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, ChunkCandidates> entry) {
                    return size() > 4096;
                }
            };

    Rwg2StructurePredictor(Rwg2SurfaceBiomeSampler sampler, long seed) {
        this(
                new NativeTerrain(sampler),
                seed,
                Loader.isModLoaded("etfuturum"),
                Loader.isModLoaded("lootgames"),
                Loader.isModLoaded("TwilightForest"),
                monumentsEnabled(),
                optionalBlock("ru.timeconqueror.lootgames.registry.LGBlocks", "PUZZLE_MASTER"),
                optionalBlock("twilightforest.block.TFBlocks", "portal"));
    }

    Rwg2StructurePredictor(
            Terrain terrain,
            long seed,
            boolean decorations,
            boolean loot,
            boolean twilight,
            boolean monuments,
            Block puzzleBlock,
            Block twilightBlock) {
        this.terrain = terrain;
        this.seed = seed;
        this.decorations = decorations;
        this.loot = loot;
        this.twilight = twilight;
        this.monuments = monuments;
        this.puzzleBlock = puzzleBlock;
        this.twilightBlock = twilightBlock;
    }

    private static Block optionalBlock(String owner, String field) {
        try {
            return (Block) Class.forName(owner).getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError missing) {
            return null;
        }
    }

    private static boolean monumentsEnabled() {
        try {
            Class<?> type =
                    Class.forName(
                            "astrotibs.villagenames.prismarine.monument.StructureOceanMonument");
            Rwg2Runtime.verify(
                    Rwg2Runtime.bytes(type),
                    "296204582ed522ec20b56c6c6afb2877b9d4c56d1e84dbc3227f699e35a955ba");
            return Class.forName("astrotibs.villagenames.config.GeneralConfig")
                    .getField("addOceanMonuments")
                    .getBoolean(null);
        } catch (ClassNotFoundException missing) {
            return false;
        } catch (Exception unsupported) {
            throw new IllegalStateException("Unsupported VillageNames monument rules", unsupported);
        }
    }

    Job begin(int x, int z, int width, int height, WorldServer world) {
        return begin(x, z, width, height, world, StructureSelection.ALL);
    }

    Job begin(
            int x, int z, int width, int height, WorldServer world, StructureSelection selection) {
        return new Job(x, z, width, height, world, selection);
    }

    final class Job {
        final List<RoguelikeDungeonPredictor.StructureDescriptor> result = new ArrayList<>();
        private final int minX, minZ, maxX, maxZ, firstChunkX, lastChunkX, lastChunkZ;
        private final WorldServer world;
        private final StructureSelection selection;
        private int chunkX, chunkZ;

        Job(int x, int z, int width, int height, WorldServer world, StructureSelection selection) {
            minX = x;
            minZ = z;
            maxX = Math.addExact(x, width);
            maxZ = Math.addExact(z, height);
            chunkX = firstChunkX = Math.floorDiv(x, 16);
            chunkZ = Math.floorDiv(z, 16);
            lastChunkX = Math.floorDiv(maxX - 1, 16);
            lastChunkZ = Math.floorDiv(maxZ - 1, 16);
            if ((long) (lastChunkX - chunkX + 1) * (lastChunkZ - chunkZ + 1) > 262144)
                throw new IllegalArgumentException("Reduce the RWG2 structure query area");
            this.world = world;
            this.selection = selection;
        }

        boolean advance(long deadline) {
            while (chunkZ <= lastChunkZ) {
                if (Thread.currentThread().isInterrupted())
                    throw new java.util.concurrent.CancellationException();
                if (System.nanoTime() >= deadline) return false;
                for (RoguelikeDungeonPredictor.StructureDescriptor candidate :
                        candidates(chunkX, chunkZ, selection)) {
                    if (!selection.accepts(candidate)) continue;
                    if (candidate.x < minX
                            || candidate.x >= maxX
                            || candidate.z < minZ
                            || candidate.z >= maxZ) continue;
                    // RWG2's live constructor always enables its landmarks. Only VillageNames'
                    // independent monument generator consults WorldInfo.mapFeaturesEnabled.
                    if ("OCEAN_MONUMENT".equals(candidate.kind)
                            && world != null
                            && !world.getWorldInfo().isMapFeaturesEnabled()) continue;
                    result.add(withEvidence(candidate, world));
                }
                if (++chunkX > lastChunkX) {
                    chunkX = firstChunkX;
                    chunkZ++;
                }
            }
            return true;
        }
    }

    private List<RoguelikeDungeonPredictor.StructureDescriptor> candidates(
            int chunkX, int chunkZ, StructureSelection selection) {
        long key = (long) chunkX << 32 ^ chunkZ & 0xffffffffL;
        ChunkCandidates cached = chunks.get(key);
        if (cached == null) cached = new ChunkCandidates();
        int requested =
                (decorations && selection.lava() ? 1 : 0)
                        | (decorations && (loot || twilight) && selection.river() ? 2 : 0)
                        | (monuments && selection.wants("OCEAN_MONUMENT") ? 4 : 0);
        int missing = requested & ~cached.computed;
        if (missing == 0) return cached.result;
        List<RoguelikeDungeonPredictor.StructureDescriptor> result = new ArrayList<>();
        int x = chunkX * 16, z = chunkZ * 16;
        if ((missing & 1) != 0) {
            long center = terrain.lavaCenter(x + 8, z + 8);
            if (center != Long.MIN_VALUE) {
                int cx = (int) (center >> 32), cz = (int) center;
                if (Math.floorDiv(cx, 16) == chunkX
                        && Math.floorDiv(cz, 16) == chunkZ
                        && !terrain.volcano(cx, cz)) {
                    if ((mix(seed ^ center ^ 0xD1B54A32D192ED03L) & 3L) == 0)
                        result.add(candidate("RWG2_NETHER_PORTAL", "RWG2_LAVA_CAVE", cx, cz, 39));
                    else if (loot)
                        result.add(candidate("LOOTGAMES_DUNGEON", "RWG2_LAVA_CAVE", cx, cz, 39));
                }
            }
        }
        if ((missing & 2) != 0) {
            int[] river = terrain.riverCenter(x, z);
            if (river != null
                    && Math.floorDiv(river[0], 16) == chunkX
                    && Math.floorDiv(river[1], 16) == chunkZ) {
                long position = (long) river[0] << 32 ^ river[1] & 0xffffffffL;
                long choice = mix(seed ^ position ^ 0x94D049BB133111EBL);
                if ((choice & 1L) == 0) {
                    if ((choice >>> 1 & 3L) == 0 && twilight)
                        result.add(
                                candidate(
                                        "RWG2_TWILIGHT_PORTAL",
                                        "RWG2_RIVER_CHAMBER",
                                        river[0],
                                        river[1],
                                        64));
                    else if (loot)
                        result.add(
                                candidate(
                                        "LOOTGAMES_DUNGEON",
                                        "RWG2_RIVER_CHAMBER",
                                        river[0],
                                        river[1],
                                        66));
                }
            }
        }
        if ((missing & 4) != 0) {
            int[] point =
                    monumentCandidate(seed, Math.floorDiv(chunkX, 32), Math.floorDiv(chunkZ, 32));
            if (point[0] == x + 8
                    && point[1] == z + 8
                    && waterBiomes(point[0], point[1], 4, true)
                    && waterBiomes(point[0], point[1], 7, false))
                result.add(candidate("OCEAN_MONUMENT", "VILLAGENAMES", point[0], point[1], null));
        }
        cached.result.addAll(result);
        cached.computed |= missing;
        chunks.put(key, cached);
        return cached.result;
    }

    static int[] monumentCandidate(long seed, int regionX, int regionZ) {
        Random random =
                new Random(
                        (long) regionX * 341873128712L
                                + (long) regionZ * 132897987541L
                                + seed
                                + 10387313L);
        int cx = regionX * 32 + (random.nextInt(27) + random.nextInt(27)) / 2;
        int cz = regionZ * 32 + (random.nextInt(27) + random.nextInt(27)) / 2;
        return new int[] {cx * 16 + 8, cz * 16 + 8};
    }

    private boolean waterBiomes(int x, int z, int radius, boolean deep) {
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                String name = terrain.biomeName(x + dx, z + dz).toLowerCase(Locale.ROOT);
                if (deep
                        ? !(name.contains("deepocean") || name.contains("deep ocean"))
                        : !(name.contains("ocean") || name.contains("river"))) return false;
            }
        return true;
    }

    private static RoguelikeDungeonPredictor.StructureDescriptor candidate(
            String kind, String subtype, int x, int z, Integer y) {
        return new RoguelikeDungeonPredictor.StructureDescriptor(
                kind, subtype, x, z, "TERRAIN_CANDIDATE", y);
    }

    private RoguelikeDungeonPredictor.StructureDescriptor withEvidence(
            RoguelikeDungeonPredictor.StructureDescriptor candidate, WorldServer world) {
        if (world == null
                || candidate.y == null
                || !world.blockExists(candidate.x, candidate.y, candidate.z)) return candidate;
        Block expected =
                candidate.kind.equals("LOOTGAMES_DUNGEON")
                        ? puzzleBlock
                        : candidate.kind.equals("RWG2_TWILIGHT_PORTAL")
                                ? twilightBlock
                                : candidate.kind.equals("RWG2_NETHER_PORTAL")
                                        ? Blocks.portal
                                        : null;
        if (expected != null && world.getBlock(candidate.x, candidate.y, candidate.z) == expected)
            return new RoguelikeDungeonPredictor.StructureDescriptor(
                    candidate.kind,
                    candidate.subtype,
                    candidate.x,
                    candidate.z,
                    "RECORDED",
                    candidate.y);
        // Missing/player-modified/unpopulated terrain is not proof that a seed candidate cannot
        // exist.
        return candidate;
    }

    List<RoguelikeDungeonPredictor.StructureDescriptor> withEvidence(
            List<RoguelikeDungeonPredictor.StructureDescriptor> candidates, WorldServer world) {
        List<RoguelikeDungeonPredictor.StructureDescriptor> result = new ArrayList<>();
        for (RoguelikeDungeonPredictor.StructureDescriptor candidate : candidates) {
            if ("OCEAN_MONUMENT".equals(candidate.kind)
                    && world != null
                    && !world.getWorldInfo().isMapFeaturesEnabled()) continue;
            result.add(withEvidence(candidate, world));
        }
        return result;
    }

    static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        return value ^ value >>> 31;
    }

    private static final class NativeTerrain implements Terrain {
        private final Rwg2SurfaceBiomeSampler sampler;
        private final Object manager;
        private final Method cave, volcano;
        private final Rwg2JunctionLocator junction;
        private final Rwg2JunctionStrength strength;

        NativeTerrain(Rwg2SurfaceBiomeSampler sampler) {
            this.sampler = sampler;
            manager = sampler.manager();
            try {
                cave =
                        manager.getClass()
                                .getMethod("getLavaCaveCenterCoordinates", int.class, int.class);
                volcano =
                        manager.getClass()
                                .getMethod("getVolcanoVicinityCoordinates", int.class, int.class);
                Class<?> type = Class.forName("rwg2.support.LandmarkDecorations");
                Rwg2Runtime.verify(
                        Rwg2Runtime.bytes(type),
                        "a2097e807e3b4901c73b587cb04f4228f1b847106159073e23f5e27e803a0b2b");
                strength = new Rwg2JunctionStrength(manager);
                Method biome = manager.getClass().getMethod("getBiomeDataAt", int.class, int.class);
                Method lava =
                        manager.getClass()
                                .getMethod("getLavaCaveCoordinates", int.class, int.class);
                Class<?> mountain =
                        Class.forName("rwg2.biomes.realistic.land.RealisticBiomeMountainChain");
                junction =
                        new Rwg2JunctionLocator(
                                new Rwg2JunctionLocator.Terrain() {
                                    public float strength(int x, int z) {
                                        return strength.at(x, z);
                                    }

                                    public boolean mountain(int x, int z) {
                                        return mountain.isInstance(call(biome, manager, x, z));
                                    }

                                    public boolean lava(int x, int z) {
                                        return (Long) call(lava, manager, x, z) != Long.MIN_VALUE;
                                    }
                                });
            } catch (Exception error) {
                throw new IllegalStateException("Cannot read RWG2 landmark rules", error);
            }
        }

        private Object call(Method method, Object owner, Object... arguments) {
            try {
                return method.invoke(owner, arguments);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("RWG2 landmark sampling failed", e);
            }
        }

        public long lavaCenter(int x, int z) {
            return (Long) call(cave, manager, x, z);
        }

        public boolean volcano(int x, int z) {
            return (Long) call(volcano, manager, x, z) != Long.MIN_VALUE;
        }

        public int[] riverCenter(int x, int z) {
            return junction.find(x, z);
        }

        public String biomeName(int x, int z) {
            return sampler.getPredictedBiomeAt(x, z).biomeName;
        }
    }
}
