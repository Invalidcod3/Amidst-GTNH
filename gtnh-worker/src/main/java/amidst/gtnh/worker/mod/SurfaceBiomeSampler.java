package amidst.gtnh.worker.mod;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
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
                        "GTNH biome worker is using the RWG final surface sampler (river replacement enabled)");
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
            return new RwgSurfaceBiomeSampler(null, manager);
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

        private final WorldServer world;
        private final WorldChunkManager manager;
        private final Method getBiomeDataAt;
        private final Method getRiverStrength;
        private final Method getNoiseAt;
        private final Field baseBiome;
        private final Field riverBiome;
        private final Object perlin;
        private final Method noise2;

        private RwgSurfaceBiomeSampler(WorldServer world, WorldChunkManager manager)
                throws ReflectiveOperationException {
            this.world = world;
            this.manager = manager;

            Class<?> managerClass = manager.getClass();
            getBiomeDataAt = managerClass.getMethod("getBiomeDataAt", Integer.TYPE, Integer.TYPE);
            getRiverStrength = managerClass.getMethod("getRiverStrength", Integer.TYPE, Integer.TYPE);
            getNoiseAt = managerClass.getMethod("getNoiseAt", Integer.TYPE, Integer.TYPE);

            Class<?> realisticBiomeClass = getBiomeDataAt.getReturnType();
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
                Object realisticBiome = getBiomeDataAt.invoke(
                        manager,
                        Integer.valueOf(x),
                        Integer.valueOf(z));
                BiomeGenBase base = (BiomeGenBase) baseBiome.get(realisticBiome);

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
    }

    /**
     * Lightweight, save-free world used by RWG's chunk manager and terrain
     * generators. It deliberately has no chunk provider; biome lookups must
     * therefore bypass World#getChunkFromBlockCoords and go straight to the
     * registered WorldChunkManager.
     */
    static final class SeedOnlyWorld extends World {

        private final long seed;

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
