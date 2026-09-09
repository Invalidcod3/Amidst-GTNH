package amidst.gtnh.worker;

import java.lang.reflect.Constructor;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/** Deterministic RWG-shaped inputs; no Minecraft launch, world or save files. */
final class SurfaceSamplerFixture {
    static final java.util.List<Integer> mapOrder = new java.util.ArrayList<>();
    static SurfaceBiomeSampler current(Manager manager) throws Exception {
        Class<?> type = Class.forName("amidst.gtnh.worker.SurfaceBiomeSamplers$RwgSurfaceBiomeSampler");
        Constructor<?> constructor = type.getDeclaredConstructor(WorldServer.class, WorldChunkManager.class);
        constructor.setAccessible(true);
        SurfaceBiomeSampler sampler = (SurfaceBiomeSampler) constructor.newInstance(null, manager);
        enableAdaptive(sampler);
        return sampler;
    }

    static void enableAdaptive(Object sampler) throws Exception {
        java.lang.reflect.Field effects = sampler.getClass().getDeclaredField("surfaceEffects");
        effects.setAccessible(true);
        java.lang.reflect.Constructor<?> constructor = effects.getType().getDeclaredConstructor(java.util.function.Function.class);
        constructor.setAccessible(true);
        effects.set(sampler, constructor.newInstance((java.util.function.Function<Class<?>, byte[]>) SurfaceSamplerFixture::classBytes));
    }

    static byte[] classBytes(Class<?> type) {
        try (java.io.InputStream in = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    static SurfaceBiomeSampler reference(Manager manager) throws Exception {
        return new ReferenceRwgSurfaceBiomeSampler(null, manager);
    }

    public static class RealisticBiome {
        private static final RealisticBiome[] REGISTRY = new RealisticBiome[256];
        public final int biomeID;
        public final BiomeGenBase baseBiome;
        public final BiomeGenBase riverBiome = BiomeGenBase.river;

        RealisticBiome(int id, BiomeGenBase base) {
            biomeID = id;
            baseBiome = base;
            REGISTRY[id] = this;
        }

        public static RealisticBiome getBiome(int id) { return REGISTRY[id]; }

        public float rNoise(Noise perlin, Object cell, int x, int z, float ocean, float border, float river) {
            return 60.0F + biomeID * 2.3F + perlin.noise2(x / 80.0F, z / 80.0F) * 15.0F;
        }

        public void generateMapGen(Block[] blocks, byte[] metadata, Long seed, World world, Manager manager,
                Random random, int cx, int cz, Noise noise, Object cell, float[] heights) {
            mapOrder.add(biomeID);
        }

        public void rReplace(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, Noise noise, Object cell, float[] heights, float river,
                BiomeGenBase[] biomes) {}

        // The installed-bytecode adapter substitutes block input types, while
        // preserving dispatch to arbitrary test biome callbacks.
        public void rReplace(InstalledRwgReferenceTest.ReferenceBlock[] blocks, byte[] metadata,
                int bx, int bz, int z, int x, int depth, World world, Random random, Noise noise,
                Object cell, float[] heights, float river, BiomeGenBase[] biomes) {
            rReplace((Block[]) null, metadata, bx, bz, z, x, depth, world, random, noise, cell, heights, river, biomes);
        }
    }

    public static class Manager extends WorldChunkManager {
        private static final BiomeGenBase[] BASES = { BiomeGenBase.plains, BiomeGenBase.desert,
                BiomeGenBase.forest, BiomeGenBase.taiga, BiomeGenBase.swampland,
                BiomeGenBase.extremeHills, BiomeGenBase.icePlains, BiomeGenBase.jungle };
        private final RealisticBiome[] biomes = new RealisticBiome[BASES.length];
        private final long seed;
        private final boolean uniform;
        public final Noise perlin;
        public final Object cell = new Object();
        long rawCalls;
        long riverCalls;

        Manager(long seed, boolean uniform) {
            this.seed = seed;
            this.uniform = uniform;
            this.perlin = new Noise(seed);
            for (int i = 0; i < biomes.length; i++) biomes[i] = new RealisticBiome(i * 3 + 1, BASES[i]);
        }

        public RealisticBiome getBiomeDataAt(int x, int z) {
            rawCalls++;
            if (uniform) return biomes[0];
            long region = mix(seed ^ ((long) Math.floorDiv(x, 96) << 32) ^ Math.floorDiv(z, 96));
            return biomes[(int) region & 7];
        }

        public float getRiverStrength(int x, int z) {
            riverCalls++;
            return (mix(seed ^ ((long) (x >> 4) << 32) ^ (z >> 4)) & 7) == 0 ? -0.9F : -0.02F;
        }

        public float getNoiseAt(int x, int z) { return 64.0F; }
        public float getOceanValue(int x, int z) { return 1.0F; }
        public float calculateRiver(int x, int z, float river, float height) {
            return river < 0.0F && height > 59.0F ? height * (river + 1.0F) + 59.0F * -river : height;
        }
    }

    public static final class Noise {
        private final long seed;
        long calls;
        Noise(long seed) { this.seed = seed; }

        public float noise2(float x, float z) {
            calls++;
            long value = mix(seed ^ ((long) Float.floatToIntBits(x) << 32) ^ Float.floatToIntBits(z));
            return ((int) value & 65535) / 32767.5F - 1.0F;
        }
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 33)) * 0xff51afd7ed558ccdL;
        value = (value ^ (value >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return value ^ (value >>> 33);
    }
}
