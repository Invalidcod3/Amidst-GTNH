package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import com.google.gson.Gson;
import net.minecraft.world.biome.BiomeGenBase;

public class ThaumcraftStructurePredictorTest {
    public static class Config {
        public static boolean genAura = true, genStructure = true, genTrees = true;
        public static boolean genCinnibar, genAmber, genInfusedStone;
        public static int nodeRarity = 5;
    }
    public static class Generator {
        public static BiomeGenBase biomeMagicalForest, biomeTaint;
        public static int getDimBlacklist(int id) { return -1; }
        public static int getBiomeBlacklist(int id) { return -1; }
    }
    public static class Handler {
        public static float getBiomeSupportsGreatwood(int id) { return 0; }
    }

    @Test public void interleavedJobsPreserveRandomStreamsAndResultOrder() throws Exception {
        ThaumcraftStructurePredictor predictor = new ThaumcraftStructurePredictor(Config.class, Generator.class, Handler.class);
        SurfaceBiomeSampler sampler = SurfaceSamplerFixture.current(new SurfaceSamplerFixture.Manager(1234L, false));
        String[] expected = new String[2];
        ThaumcraftStructurePredictor.Job[] jobs = new ThaumcraftStructurePredictor.Job[2];
        Gson gson = new Gson();
        for (int i = 0; i < 2; i++) {
            expected[i] = gson.toJson(predictor.predict(1234L + i, 0, -513 + i * 512, -129, 512, 256, sampler));
            assertNotEquals("[]", expected[i]);
            jobs[i] = predictor.begin(1234L + i, 0, -513 + i * 512, -129, 512, 256, sampler);
            assertFalse(jobs[i].advance(0, () -> 0));
            assertTrue(jobs[i].result.isEmpty());
        }
        boolean[] done = new boolean[2];
        int slices = 0;
        while (!done[0] || !done[1]) {
            for (int i = 0; i < 2; i++) if (!done[i]) {
                AtomicLong clock = new AtomicLong();
                done[i] = jobs[i].advance(3, clock::getAndIncrement);
                slices++;
            }
            assertTrue("Must make bounded progress", slices < 1000);
        }
        assertTrue(slices > 2);
        for (int i = 0; i < 2; i++) {
            assertEquals(expected[i], gson.toJson(jobs[i].result));
            assertTrue(jobs[i].advance(0, () -> 0));
            assertEquals("Completed job cannot append duplicates", expected[i], gson.toJson(jobs[i].result));
        }
    }
}
