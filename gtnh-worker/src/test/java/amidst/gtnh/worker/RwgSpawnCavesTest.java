package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;

import org.junit.Test;

import java.util.Arrays;
import java.util.BitSet;

public class RwgSpawnCavesTest {
    private static final Block SOLID = new Block(Material.rock) {};

    @Test
    public void disabledCavesDoNotInvokeGeneratorOrAlterSurface() {
        Block[] blocks = new Block[65536];
        Arrays.fill(blocks, SOLID);
        new RwgSpawnCaves(
                        null,
                        new MapGenBase() {
                            @Override
                            public void func_151539_a(
                                    IChunkProvider provider,
                                    World world,
                                    int x,
                                    int z,
                                    Block[] data) {
                                fail("Disabled cave stage must not run");
                            }
                        },
                        () -> false)
                .carve(-4, -12, blocks);
        for (Block block : blocks) assertSame(SOLID, block);
    }

    /** Native tunnel geometry only: no assumption that this solid fixture is actual RWG terrain. */
    @Test
    public void reportedSeedVanillaCavesDoNotReachTheSavedSpawnSurface() throws Exception {
        CaveWorld world = world(-8138049151491905853L);
        GeometryProbe probe = new GeometryProbe();
        Block[] blocks = new Block[65536];
        Arrays.fill(blocks, SOLID);
        new RwgSpawnCaves(world, probe, () -> true).carve(-4, -12, blocks);
        BitSet column = new BitSet(256);
        int offset = (6 * 16 + 12) * 256; // Global (-58, -180).
        for (int y = 0; y < 256; y++) if (probe.dug.get(offset + y)) column.set(y);
        BitSet expected = new BitSet(256);
        expected.set(25, 28);
        expected.set(35, 38);
        assertEquals(expected, column);
        // Actual water can suppress carving, but cannot add tunnels above this all-solid
        // envelope. This excludes vanilla caves as the cause of the saved Y=65 surface.
        assertTrue(column.get(63, 256).isEmpty());
        BitSet first = (BitSet) probe.dug.clone();
        probe.dug.clear();
        new RwgSpawnCaves(world, probe, () -> true).carve(7, 3, blocks);
        probe.dug.clear();
        new RwgSpawnCaves(world, probe, () -> true).carve(-4, -12, blocks);
        assertEquals("Chunk call order must not consume spawn-search randomness", first, probe.dug);
    }

    private static class GeometryProbe extends MapGenCaves {
        final BitSet dug = new BitSet(65536);

        @Override
        protected boolean isOceanBlock(
                Block[] data, int index, int x, int y, int z, int cx, int cz) {
            return false; // The fixture is entirely solid.
        }

        @Override
        protected void digBlock(
                Block[] data, int index, int x, int y, int z, int cx, int cz, boolean top) {
            dug.set(index); // Preserve native geometry, avoid Forge block-registry bootstrap in
            // unit JVM.
        }
    }

    public static class CaveWorld extends WorldServer {
        long seed;

        private CaveWorld() {
            super(null, null, "unused", 0, null, null);
        }

        @Override
        public long getSeed() {
            return seed;
        }

        @Override
        public BiomeGenBase getBiomeGenForCoords(int x, int z) {
            return BiomeGenBase.plains;
        }

        @Override
        public net.minecraft.world.chunk.Chunk getChunkFromChunkCoords(int x, int z) {
            throw new AssertionError("Cave replay must not load/save chunks");
        }
    }

    private static CaveWorld world(long seed) throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        CaveWorld world =
                (CaveWorld) ((sun.misc.Unsafe) field.get(null)).allocateInstance(CaveWorld.class);
        world.seed = seed;
        return world;
    }
}
