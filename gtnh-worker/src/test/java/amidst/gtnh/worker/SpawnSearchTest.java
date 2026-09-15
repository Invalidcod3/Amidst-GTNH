package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.world.ChunkPosition;
import net.minecraft.world.biome.BiomeGenBase;

import org.junit.Test;

import java.util.Random;

public class SpawnSearchTest {
    private static final net.minecraft.block.Block GRASS = block(),
            STONE = block(),
            SAND = block(),
            SNOW = block(),
            WATER = block();

    private static net.minecraft.block.Block block() {
        return new net.minecraft.block.Block(net.minecraft.block.material.Material.rock) {};
    }

    private static SpawnSearch.Rules rules(String provider) {
        return new SpawnSearch.Rules(provider, GRASS, STONE, SAND, SNOW);
    }

    private static SpawnSearch.Surface surface(net.minecraft.block.Block block, boolean biome) {
        return new SpawnSearch.Surface(block, BiomeGenBase.plains, 70, biome);
    }

    @Test
    public void bopRejectsGrassEvenWhenBiomeDefaultIsGrass() {
        // The native block identities are explicit; biome defaults do not participate.
        assertFalse(rules(SpawnSearch.BOP).accepts(surface(GRASS, true)));
        assertTrue(rules(SpawnSearch.VANILLA).accepts(surface(GRASS, true)));
        assertTrue(rules(SpawnSearch.BOP).accepts(surface(STONE, false)));
        assertTrue(rules(SpawnSearch.BOP).accepts(surface(SAND, false)));
        assertFalse(rules(SpawnSearch.BOP).accepts(surface(SNOW, false)));
        assertTrue(rules(SpawnSearch.BOP).accepts(surface(SNOW, true)));
        assertFalse(rules(SpawnSearch.BOP).accepts(surface(WATER, true)));
    }

    @Test
    public void slicedSearchPreservesInitialSearchRandomnessAndStopsAtFirstValidBlock() {
        final int[] count = {0};
        Random expected = new Random(98765);
        expected.nextInt(117);
        int x = -300, z = 500;
        for (int i = 0; i < 17; i++) {
            x += expected.nextInt(64) - expected.nextInt(64);
            z += expected.nextInt(64) - expected.nextInt(64);
        }
        SpawnSearch.Job job =
                new SpawnSearch.Job(
                        98765,
                        rules(SpawnSearch.BOP),
                        new SpawnSearch.Terrain() {
                            public ChunkPosition initial(Random random) {
                                random.nextInt(117);
                                return new ChunkPosition(-300, 0, 500);
                            }

                            public SpawnSearch.Surface surface(int x, int z) {
                                return SpawnSearchTest.surface(
                                        count[0]++ < 17 ? GRASS : SAND, false);
                            }
                        });
        while (!job.advance(0)) {} // Exactly one attempt per slice.
        assertEquals(17, job.moves);
        assertEquals(18, count[0]);
        assertEquals(x, job.x);
        assertEquals(z, job.z);
        assertTrue(job.advance(0));
        assertEquals(18, count[0]);
    }

    @Test
    public void rwgNullInitialPositionWalksExactly1000TimesWhenNoSiteIsValid() {
        final int[] count = {0};
        SpawnSearch.Job job =
                new SpawnSearch.Job(
                        42,
                        rules(SpawnSearch.BOP),
                        new SpawnSearch.Terrain() {
                            public ChunkPosition initial(Random random) {
                                return null;
                            }

                            public SpawnSearch.Surface surface(int x, int z) {
                                count[0]++;
                                return SpawnSearchTest.surface(GRASS, true);
                            }
                        });
        assertTrue(job.advance(Long.MAX_VALUE));
        Random random = new Random(42);
        int x = 0, z = 0;
        for (int i = 0; i < 1000; i++) {
            x += random.nextInt(64) - random.nextInt(64);
            z += random.nextInt(64) - random.nextInt(64);
        }
        assertEquals(1000, count[0]);
        assertEquals(1000, job.moves);
        assertEquals(x, job.x);
        assertEquals(z, job.z);
        assertFalse(x == 0 && z == 0);
    }

    @Test
    public void validOriginIsNotArtificiallyMovedIntoADistanceBand() {
        SpawnSearch.Job job =
                new SpawnSearch.Job(
                        42,
                        rules(SpawnSearch.VANILLA),
                        new SpawnSearch.Terrain() {
                            public ChunkPosition initial(Random random) {
                                return null;
                            }

                            public SpawnSearch.Surface surface(int x, int z) {
                                return SpawnSearchTest.surface(GRASS, true);
                            }
                        });
        assertTrue(job.advance(0));
        assertEquals(0, job.moves);
        assertEquals(0, job.x);
        assertEquals(0, job.z);
        assertFalse(SpawnSearch.supported("custom.Provider"));
    }
}
