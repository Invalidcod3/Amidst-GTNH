package amidst.gtnh.worker;

import java.lang.reflect.Method;
import java.util.Random;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

/**
 * Replays RWG's pre-cave, pre-population chunk stages on disposable arrays.
 * Every registered realistic biome receives its real generateMapGen/rReplace
 * calls. There are no biome names, surface-class rules or assumed registry ids.
 *
 * The caller owns the exact blended heights and caches only the resulting 256
 * biome references. Scratch blocks are reused, never installed in a world.
 * Forge ReplaceBiomeBlocks events and population are deliberately not dispatched:
 * third-party listeners may have save/world side effects. This is a prediction,
 * not a claim to reproduce arbitrary post-generation modifications.
 */
final class RwgChunkBiomeReplay {
    private final World previewWorld;
    private final long seed;
    private final Object manager;
    private final Object perlin;
    private final Object cell;
    private final Method mapGen;
    private final Method replace;
    private final Method noise2;
    private final Block[] blocks = new Block[65536];
    private final byte[] metadata = new byte[65536];
    // Terrain initialization has only 257 possible columns (0..256 stone
    // blocks). Immutable templates avoid branching and individual reference
    // writes 65,536 times for every predicted chunk. Callbacks get copies.
    private final Block[][] terrainColumns = new Block[257][];
    private final Random random = new Random();
    private final Random mapRandom = new Random();

    RwgChunkBiomeReplay(World previewWorld, long seed, Object manager, Object perlin, Object cell,
            Class<?> biomeType, Class<?> managerType, Class<?> noiseType, Class<?> cellType)
            throws ReflectiveOperationException {
        this.previewWorld = previewWorld;
        this.seed = seed;
        this.manager = manager;
        this.perlin = perlin;
        this.cell = cell;
        Method mapMethod = null;
        for (Method candidate : biomeType.getMethods()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (candidate.getName().equals("generateMapGen") && parameters.length == 11
                    && parameters[0] == Block[].class && parameters[4].isInstance(manager)) {
                mapMethod = candidate;
                break;
            }
        }
        if (mapMethod == null) throw new NoSuchMethodException(biomeType.getName() + ".generateMapGen");
        mapGen = mapMethod;
        mapGen.setAccessible(true);
        replace = biomeType.getMethod("rReplace", Block[].class, byte[].class,
                int.class, int.class, int.class, int.class, int.class, World.class, Random.class,
                noiseType, cellType, float[].class, float.class, BiomeGenBase[].class);
        replace.setAccessible(true);
        noise2 = perlin.getClass().getMethod("noise2", float.class, float.class);
        noise2.setAccessible(true);
    }

    BiomeGenBase[] replay(int chunkX, int chunkZ, Object[] realistic, BiomeGenBase[] base,
            BiomeGenBase[] rivers, float[] heights, float[] rawRivers, Object[] mapBiomes)
            throws ReflectiveOperationException {
        // RWG uses X-major terrain columns but Z-major biome arrays.
        java.util.Arrays.fill(metadata, (byte) 0);
        for (int column = 0; column < 256; column++) {
            int stoneCount = Math.max(0, Math.min(255, (int) heights[column]) + 1);
            Block[] template = terrainColumns[stoneCount];
            if (template == null) {
                template = new Block[256];
                java.util.Arrays.fill(template, 0, stoneCount, Blocks.stone);
                if (stoneCount < 63) java.util.Arrays.fill(template, stoneCount, 63, Blocks.water);
                java.util.Arrays.fill(template, Math.max(stoneCount, 63), 256, Blocks.air);
                terrainColumns[stoneCount] = template;
            }
            System.arraycopy(template, 0, blocks, column * 256, 256);
        }
        random.setSeed((long) chunkX * 341873128712L + (long) chunkZ * 132897987541L);
        // getNewNoise records the nonzero weights at local (8,8). Ascending
        // realistic id order and the original per-biome map RNG are significant.
        for (Object biome : mapBiomes) {
            mapGen.invoke(biome, blocks, metadata, Long.valueOf(seed), previewWorld, manager,
                    mapRandom, chunkX, chunkZ, perlin, cell, heights);
        }
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int index = z * 16 + x;
                int bx = chunkX * 16 + x, bz = chunkZ * 16 + z;
                float river = -rawRivers[x * 16 + z];
                if (river > 0.05F && river + (Float) noise2.invoke(perlin, bx / 10.0F, bz / 10.0F) * 0.15F > 0.8F) {
                    base[index] = rivers[index];
                }
                // Dispatch to the actual registered object's override. BOP's
                // support class invokes all of its painters in their own order.
                replace.invoke(realistic[index], blocks, metadata, bx, bz, z, x, -1,
                        previewWorld, random, perlin, cell, heights, river, base);
                int bottom = (x * 16 + z) * 256;
                blocks[bottom] = Blocks.bedrock;
                for (int bound = 2; bound <= 5; bound++) {
                    blocks[bottom + random.nextInt(bound)] = Blocks.bedrock;
                }
            }
        }
        for (int index = 0; index < base.length; index++) {
            if (base[index] == null) {
                throw new IllegalStateException("RWG native replay returned null biome at "
                        + (chunkX * 16 + index % 16) + "," + (chunkZ * 16 + index / 16));
            }
        }
        return base;
    }
}
