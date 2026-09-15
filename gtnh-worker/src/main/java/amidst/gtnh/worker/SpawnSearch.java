package amidst.gtnh.worker;

import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.biome.BiomeGenBase;

import java.util.Random;

/** Resumable WorldServer.createSpawnPosition random walk, without loading world chunks. */
final class SpawnSearch {
    static final String VANILLA = "net.minecraft.world.WorldProviderSurface";
    static final String BOP = "biomesoplenty.common.world.WorldProviderSurfaceBOP";

    static final class Surface {
        final Block block;
        final BiomeGenBase biome;
        final int y;
        final boolean spawnBiome;

        Surface(Block block, BiomeGenBase biome, int y, boolean spawnBiome) {
            this.block = block;
            this.biome = biome;
            this.y = y;
            this.spawnBiome = spawnBiome;
        }
    }

    interface Terrain {
        ChunkPosition initial(Random random);

        Surface surface(int x, int z);
    }

    static boolean supported(String provider) {
        return VANILLA.equals(provider) || BOP.equals(provider);
    }

    static Rules rules(String provider) {
        return new Rules(provider, Blocks.grass, Blocks.stone, Blocks.sand, Blocks.snow_layer);
    }

    /**
     * Capture the registered block identities once for this search. Tests can supply a detached
     * registry.
     */
    static final class Rules {
        final String provider;
        final Block grass, stone, sand, snow;

        Rules(String provider, Block grass, Block stone, Block sand, Block snow) {
            if (!supported(provider))
                throw new IllegalArgumentException("Unsupported spawn provider: " + provider);
            this.provider = provider;
            this.grass = grass;
            this.stone = stone;
            this.sand = sand;
            this.snow = snow;
        }

        boolean accepts(Surface surface) {
            if (surface.block == null) return false;
            if (VANILLA.equals(provider)) return surface.block == grass;
            // BOP 1.7.10 checks this same block repeatedly in its clearance loops.
            // Preserve precedence: only snow needs membership in the spawn-biome list.
            return surface.block == sand
                    || surface.block == stone
                    || surface.block == snow && surface.spawnBiome;
        }
    }

    static final class Job {
        final Random random;
        final Terrain terrain;
        final Rules rules;
        int x, z, moves;
        boolean done;

        Job(long seed, String provider, Terrain terrain) {
            this(seed, rules(provider), terrain);
        }

        Job(long seed, Rules rules, Terrain terrain) {
            this.rules = rules;
            this.terrain = terrain;
            random = new Random(seed);
            ChunkPosition initial = terrain.initial(random);
            if (initial != null) {
                x = initial.chunkPosX;
                z = initial.chunkPosZ;
            }
        }

        boolean advance(long deadline) {
            if (done) return true;
            do {
                if (rules.accepts(terrain.surface(x, z))) return done = true;
                x += random.nextInt(64) - random.nextInt(64);
                z += random.nextInt(64) - random.nextInt(64);
                // Vanilla stops immediately after the 1000th move, without testing that position.
                if (++moves == 1000) return done = true;
            } while (System.nanoTime() < deadline);
            return false;
        }
    }
}
