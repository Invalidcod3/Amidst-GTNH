package amidst.gtnh.worker;

import static org.junit.Assert.assertArrayEquals;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;

import org.junit.Test;

public class Rwg2TerrainFillTest {
    @Test
    public void contiguousFillMatchesNativeLoopIncludingHeightExtremes() {
        Block stone = new Block(Material.rock) {};
        Block water = new Block(Material.water) {};
        Block air = new Block(Material.air) {};
        float[] heights = new float[256];
        float[] extremes = {
            Float.NEGATIVE_INFINITY,
            -1000,
            -1.9f,
            -1,
            -.9f,
            0,
            1.9f,
            61.9f,
            62,
            63,
            64,
            254.9f,
            255,
            256,
            1000,
            Float.POSITIVE_INFINITY,
            Float.NaN
        };
        for (int column = 0; column < heights.length; column++)
            heights[column] = column < extremes.length ? extremes[column] : column - 20.3f;
        Block[] expected = new Block[65536], actual = new Block[65536];
        for (int column = 0; column < 256; column++)
            for (int y = 0; y < 256; y++)
                expected[column * 256 + y] =
                        y > (int) heights[column] ? (y < 63 ? water : air) : stone;
        Rwg2TerrainFill.fill(actual, heights, stone, water, air);
        assertArrayEquals(expected, actual);
    }
}
