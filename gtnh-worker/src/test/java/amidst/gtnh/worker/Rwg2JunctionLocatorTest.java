package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

public class Rwg2JunctionLocatorTest {
    @Test
    public void neighboringChunksConvergeToSamePeakWithoutChangingOwnership() {
        Rwg2JunctionLocator locator =
                new Rwg2JunctionLocator(
                        new Rwg2JunctionLocator.Terrain() {
                            public float strength(int x, int z) {
                                return Math.max(
                                        0, 1f - ((x - 17) * (x - 17) + (z + 3) * (z + 3)) / 1600f);
                            }

                            public boolean mountain(int x, int z) {
                                return true;
                            }

                            public boolean lava(int x, int z) {
                                return false;
                            }
                        });
        assertArrayEquals(new int[] {17, -3}, locator.find(0, -16));
        assertArrayEquals(new int[] {17, -3}, locator.find(16, -16));
        assertArrayEquals(new int[] {17, -3}, locator.find(0, -16));
    }

    @Test
    public void narrowPeakIsNotSkippedAndLowOrExcludedCentersStayAbsent() {
        for (int mode = 0; mode < 4; mode++) {
            final int variant = mode;
            Rwg2JunctionLocator locator =
                    new Rwg2JunctionLocator(
                            new Rwg2JunctionLocator.Terrain() {
                                public float strength(int x, int z) {
                                    return x == 2 && z == 6 ? (variant == 1 ? .34f : 1f) : 0f;
                                }

                                public boolean mountain(int x, int z) {
                                    return variant != 2;
                                }

                                public boolean lava(int x, int z) {
                                    return variant == 3;
                                }
                            });
            if (mode == 0) assertArrayEquals(new int[] {2, 6}, locator.find(0, 0));
            else assertNull(locator.find(0, 0));
        }
    }
}
