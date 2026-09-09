package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.minecraft.world.biome.BiomeGenBase;
import rwg.surface.SurfaceDuneValley;

public class RwgSurfaceBiomeOverridesTest {
    @Test
    public void bopSurfaceArrayCanReplaceARegisteredBiomeAndEvenRiverWithDesert() throws Exception {
        RwgSurfaceBiomeOverrides rules = rules(new ConstantNoise());
        Object bop = new SupportBiome(300.0F, true);
        // The registry id belongs to the base biome; the realistic id is unrelated.
        assertSame(BiomeGenBase.desert, rules.apply(bop, BiomeGenBase.forest, -31, 18, () -> 100.0F));
        assertSame(BiomeGenBase.desert, rules.apply(bop, BiomeGenBase.river, -31, 18, () -> 100.0F));
        assertSame(BiomeGenBase.forest, rules.apply(bop, BiomeGenBase.forest, -31, 18, () -> 60.0F));
    }

    @Test
    public void nativePrivateSurfaceAndActualValleyParametersAreRespected() throws Exception {
        RwgSurfaceBiomeOverrides rules = rules(new ConstantNoise());
        assertSame(BiomeGenBase.desert, rules.apply(new NativeBiome(), BiomeGenBase.savanna, 0, 0, () -> 100));
        assertSame(BiomeGenBase.savanna, rules.apply(new NativeBiome(), BiomeGenBase.savanna, 0, 0, () -> -2));
    }

    @Test
    public void noTerrainWorkIsAddedToOrdinaryBiomes() throws Exception {
        assertSame(BiomeGenBase.forest, rules(new ConstantNoise()).apply(new Object(), BiomeGenBase.forest,
                1, 2, () -> { throw new AssertionError("Unnecessary height calculation"); }));
    }

    @Test
    public void preservesHeightTruncationAndMixThresholdBoundaries() throws Exception {
        // valley noise 0 => h=16.25, no detail => ground cutoff 73.75.
        RwgSurfaceBiomeOverrides rules = rules(new ConstantNoise());
        Object biome = new SupportBiome(300, false);
        assertSame(BiomeGenBase.forest, rules.apply(biome, BiomeGenBase.forest, 0, 0, () -> 73.99F));
        assertSame(BiomeGenBase.desert, rules.apply(biome, BiomeGenBase.forest, 0, 0, () -> 74.0F));
        assertFalse(RwgSurfaceBiomeOverrides.isDesert(60, 0, -0.28F, 0, true));
        assertTrue(RwgSurfaceBiomeOverrides.isDesert(60, 0, Math.nextDown(-0.28F), 0, true));
        assertFalse(RwgSurfaceBiomeOverrides.isDesert(60, 0, -1, 0, false));
    }

    private static RwgSurfaceBiomeOverrides rules(Object noise) throws Exception {
        return new RwgSurfaceBiomeOverrides(noise, noise.getClass().getMethod("noise2", float.class, float.class));
    }

    public static final class ConstantNoise {
        public float noise2(float x, float z) { return 0; }
    }

    public static class SupportBiome extends SurfaceSamplerFixture.RealisticBiome {
        public final Object[] surfaces;
        public SupportBiome(float valley, boolean mix) {
            super(201, BiomeGenBase.forest);
            surfaces = new Object[] { new SurfaceDuneValley(valley, mix), new Object() };
        }
        @Override public float rNoise(SurfaceSamplerFixture.Noise noise, Object cell, int x, int z,
                float ocean, float border, float river) { return 200.0F; }
    }

    public static class NativeBiome extends SurfaceSamplerFixture.RealisticBiome {
        private final Object surface = new SurfaceDuneValley(220, true);
        public NativeBiome() { super(197, BiomeGenBase.savanna); }
        @Override public float rNoise(SurfaceSamplerFixture.Noise noise, Object cell, int x, int z,
                float ocean, float border, float river) { return 200.0F; }
    }
}
