package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;
import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;

public class RwgSurfaceEffectsTest {
    @Test public void unknownRuntimeBytesNeverEnableShortcuts() {
        assertFalse(new RwgSurfaceEffects(type -> null).leavesBiomesUntouched(new Wrapper(new Painter())));
    }

    @Test public void followsActualSurfaceFieldAndAllArrayDelegates() {
        RwgSurfaceEffects effects = new RwgSurfaceEffects(SurfaceSamplerFixture::classBytes);
        assertTrue(effects.leavesBiomesUntouched(new Wrapper(new Painter())));
        assertFalse(effects.leavesBiomesUntouched(new Wrapper(new WritingPainter())));
        assertTrue(effects.leavesBiomesUntouched(new Support(new Painter(), new Painter())));
        assertFalse(effects.leavesBiomesUntouched(new Support(new Painter(), new WritingPainter())));
    }

    @Test public void arrayReadOrEscapeAlsoRequiresFullReplay() {
        RwgSurfaceEffects effects = new RwgSurfaceEffects(SurfaceSamplerFixture::classBytes);
        assertFalse(effects.leavesBiomesUntouched(new Wrapper(new EscapingPainter())));
        assertFalse(effects.leavesBiomesUntouched(new Wrapper(new ReadingPainter())));
    }

    @Test public void adaptiveMatchesFullReplayIncludingCrossColumnWritesAndNegativeBoundaries() throws Exception {
        for (boolean writer : new boolean[] {false, true}) {
            SurfaceSamplerFixture.Manager manager = new SurfaceSamplerFixture.Manager(47, false) {
                private final SurfaceSamplerFixture.RealisticBiome special = new Support(new Painter(), new WritingPainter());
                @Override public SurfaceSamplerFixture.RealisticBiome getBiomeDataAt(int x, int z) {
                    return writer && x >= -8 && z <= 128 ? special : super.getBiomeDataAt(x, z);
                }
            };
            SurfaceBiomeSampler fast = SurfaceSamplerFixture.current(manager), full = SurfaceSamplerFixture.current(manager);
            java.lang.reflect.Field field = full.getClass().getDeclaredField("surfaceEffects");
            field.setAccessible(true);
            field.set(full, new RwgSurfaceEffects(type -> null));
            for (int z = -65; z < 80; z += 4) for (int x = -97; x < 80; x += 4)
                assertEquals(full.getPredictedBiomeAt(x, z).biomeID, fast.getPredictedBiomeAt(x, z).biomeID);
            assertTrue(fast.predictionStats(), fast.predictionStats().contains("biomeOnlyChunks="));
            if (writer) assertFalse(fast.predictionStats().contains("nativeReplayChunks=0,"));
            else assertTrue(fast.predictionStats().contains("nativeReplayChunks=0,"));
        }
    }

    public static class Painter {
        public Block topBlock, fillerBlock;
        public void paintTerrain(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) {
            if (blocks != null) blocks[(x * 16 + z) * 256] = null;
            random.nextInt(17);
        }
    }
    public static class WritingPainter extends Painter {
        @Override public void paintTerrain(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) {
            biomes[(z * 16 + x + random.nextInt(3)) % 256] = heights[x * 16 + z] > 63
                    ? BiomeGenBase.desert : BiomeGenBase.swampland;
        }
    }
    public static class EscapingPainter extends Painter {
        BiomeGenBase[] escaped;
        @Override public void paintTerrain(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) { escaped = biomes; }
    }
    public static class ReadingPainter extends Painter {
        BiomeGenBase read;
        @Override public void paintTerrain(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) { read = biomes[0]; }
    }
    public static class Wrapper extends SurfaceSamplerFixture.RealisticBiome {
        public Painter surface;
        Wrapper(Painter painter) { super(220, BiomeGenBase.forest); surface = painter; }
        @Override public void rReplace(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) {
            surface.paintTerrain(blocks, metadata, bx, bz, z, x, depth, world, random, noise, cell, heights, river, biomes);
        }
    }
    public static class Support extends SurfaceSamplerFixture.RealisticBiome {
        public Painter[] surfaces;
        public int surfacesLength;
        Support(Painter... painters) { super(221, BiomeGenBase.forest); surfaces = painters; surfacesLength = painters.length; }
        @Override public void rReplace(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x, int depth,
                World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) {
            for (int s = 0; s < surfacesLength; s++) surfaces[s].paintTerrain(blocks, metadata, bx, bz, z, x,
                    depth, world, random, noise, cell, heights, river, biomes);
        }
    }
}
