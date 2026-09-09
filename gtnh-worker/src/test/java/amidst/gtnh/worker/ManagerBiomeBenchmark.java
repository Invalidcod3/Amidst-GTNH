package amidst.gtnh.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.WorldChunkManager;

/** Actual vanilla GenLayer used by Ross 128b and Deep Dark, without running a game. */
public final class ManagerBiomeBenchmark {
    public static void main(String[] args) throws Exception {
        StringBuilder json = new StringBuilder("{\"scope\":\"native WorldChunkManager final layer; v37 point loop vs paged and sliced sampling; no game/TCP timing\",\"cases\":[");
        for (int scenario = 0; scenario < 2; scenario++) {
            measure(false, scenario); measure(true, scenario);
            double[] oldTimes = new double[5], newTimes = new double[5];
            for (int trial = 0; trial < 5; trial++) {
                Measurement oldResult, newResult;
                if ((trial & 1) == 0) { oldResult = measure(false, scenario); newResult = measure(true, scenario); }
                else { newResult = measure(true, scenario); oldResult = measure(false, scenario); }
                if (!Arrays.equals(oldResult.ids, newResult.ids)) throw new AssertionError("Final biome mismatch");
                oldTimes[trial] = oldResult.millis; newTimes[trial] = newResult.millis;
            }
            Arrays.sort(oldTimes); Arrays.sort(newTimes);
            String result = String.format(Locale.ROOT,
                    "{\"case\":\"%s\",\"tiles\":4,\"baselineMedianMs\":%.3f,\"currentMedianMs\":%.3f,\"speedup\":%.3f,\"equalSamplesPerTrial\":65536}",
                    scenario == 0 ? "ross128b-negative-coordinates" : "deep-dark-far-coordinates",
                    oldTimes[2], newTimes[2], oldTimes[2] / newTimes[2]);
            if (scenario > 0) json.append(',');
            json.append(result); System.out.println(result);
        }
        json.append("]}");
        Files.createDirectories(Paths.get(args[0]).toAbsolutePath().getParent());
        Files.write(Paths.get(args[0]), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Measurement measure(boolean paged, int scenario) {
        WorldChunkManager manager = new WorldChunkManager(2777967236474336022L, WorldType.DEFAULT);
        ManagerBiomeCache cache = new ManagerBiomeCache(manager);
        int[] result = new int[4 * 128 * 128];
        long start = System.nanoTime();
        for (int tile = 0; tile < 4; tile++) {
            int x = (scenario == 0 ? -1536 : 64000) + tile * 512, z = scenario == 0 ? -512 : 32000;
            if (paged) {
                BiomeSamplingJob job = new BiomeSamplingJob(x, z, 128, 128, 4, (px, pz, w, h) -> {
                    int[] patch = new int[w * h];
                    for (int row = 0; row < h; row++) for (int col = 0; col < w; col++)
                        patch[row * w + col] = cache.getBiomeAt(px + col * 4, pz + row * 4).biomeID;
                    return patch;
                });
                job.advance(Long.MAX_VALUE);
                System.arraycopy(job.result, 0, result, tile * 16384, 16384);
            } else {
                for (int row = 0; row < 128; row++) for (int col = 0; col < 128; col++)
                    result[tile * 16384 + row * 128 + col] = manager.getBiomeGenAt(x + col * 4, z + row * 4).biomeID;
            }
        }
        return new Measurement((System.nanoTime() - start) / 1e6, result);
    }
    private static final class Measurement {
        final double millis;
        final int[] ids;
        Measurement(double millis, int[] ids) { this.millis = millis; this.ids = ids; }
    }
}
