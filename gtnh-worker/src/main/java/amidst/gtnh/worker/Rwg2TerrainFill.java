package amidst.gtnh.worker;

import net.minecraft.block.Block;

import java.util.Arrays;

/** Exact equivalent of RWG2's per-block height/water checks, using contiguous fills. */
final class Rwg2TerrainFill {
    static void fill(Block[] blocks, float[] heights, Block stone, Block water, Block air) {
        for (int column = 0; column < 256; column++) {
            int base = column * 256;
            int solidEnd = (int) Math.max(0L, Math.min(256L, (long) (int) heights[column] + 1L));
            int waterEnd = Math.max(63, solidEnd);
            Arrays.fill(blocks, base, base + solidEnd, stone);
            Arrays.fill(blocks, base + solidEnd, base + waterEnd, water);
            Arrays.fill(blocks, base + waterEnd, base + 256, air);
        }
    }
}
