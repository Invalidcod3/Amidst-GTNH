package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Random;

public class Rwg2MountainInfluenceTest {
    @Test
    public void nearestLookupMatchesEveryNativeFloatBitAcrossChangingMasks() {
        Rwg2MountainInfluence fast = new Rwg2MountainInfluence();
        Random random = new Random(79123);
        boolean[] mountainTypes = new boolean[] {false, true};
        int[] biomes = new int[441];
        for (int round = 0; round < 80; round++) {
            for (int i = 0; i < biomes.length; i++)
                biomes[i] =
                        round == 0 ? 0 : round == 1 ? 1 : random.nextInt(16) < round % 16 ? 1 : 0;
            fast.begin(biomes, mountainTypes);
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++) {
                    float expected = 0;
                    for (int sx = 0; sx < 21; sx++)
                        for (int sz = 0; sz < 21; sz++) {
                            if (biomes[sx * 21 + sz] == 0) continue;
                            float dx = (sx - 8) * 8 - 8 - x, dz = (sz - 8) * 8 - 8 - z;
                            float distance = dx * dx + dz * dz;
                            if (distance < 48f * 48f)
                                expected =
                                        Math.max(expected, 1f - (float) Math.sqrt(distance) / 48f);
                        }
                    assertEquals(
                            Float.floatToRawIntBits(expected),
                            Float.floatToRawIntBits(fast.at(x, z)));
                }
        }
        assertTrue(
                "Avoid repeated scans of the entire blend grid",
                fast.lookups < 80L * 256 * 441 / 5);
    }
}
