package amidst.gtnh.worker;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

/** Isolates the blend/cache algorithm using identical seeded inputs, excluding game ticks and network. */
public final class SurfaceBiomeSamplerBenchmark {
    private static volatile long checksumSink;

    public static void main(String[] args) throws Exception {
        StringBuilder report = new StringBuilder("{\n  \"scope\": \"Deterministic RWG-shaped inputs; excludes real RWG noise, network, game ticks and structures\",\n  \"cases\": [\n");
        for (int scenario = 0; scenario < 2; scenario++) {
            boolean uniform = scenario == 0;
            // Warm both implementations before comparing alternating measurements.
            for (int i = 0; i < 3; i++) { measure(false, uniform); measure(true, uniform); }
            double[] oldTimes = new double[7];
            double[] newTimes = new double[7];
            Measurement oldResult = null;
            Measurement newResult = null;
            for (int i = 0; i < oldTimes.length; i++) {
                if ((i & 1) == 0) {
                    oldResult = measure(false, uniform); newResult = measure(true, uniform);
                } else {
                    newResult = measure(true, uniform); oldResult = measure(false, uniform);
                }
                if (oldResult.checksum != newResult.checksum) throw new AssertionError("Biome result changed");
                oldTimes[i] = oldResult.millis; newTimes[i] = newResult.millis;
            }
            Arrays.sort(oldTimes); Arrays.sort(newTimes);
            String line = String.format(Locale.ROOT,
                    "    {\"terrain\":\"%s\",\"seed\":123456789,\"tiles\":4,\"samplesPerTile\":16384,\"step\":4,\"baselineMedianMs\":%.3f,\"currentMedianMs\":%.3f,\"speedup\":%.2f,\"baselineRawCalls\":%d,\"currentRawCalls\":%d,\"baselineNoiseCalls\":%d,\"currentNoiseCalls\":%d,\"checksum\":%d}",
                    uniform ? "uniform" : "biome-boundaries", oldTimes[3], newTimes[3], oldTimes[3] / newTimes[3],
                    oldResult.rawCalls, newResult.rawCalls, oldResult.noiseCalls, newResult.noiseCalls, newResult.checksum);
            report.append(line).append(scenario == 0 ? ",\n" : "\n");
            System.out.println(line);
        }
        report.append("  ]\n}\n");
        Path file = Paths.get(args[0]);
        Files.createDirectories(file.getParent());
        Files.write(file, report.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Measurement measure(boolean current, boolean uniform) throws Exception {
        SurfaceSamplerFixture.Manager manager = new SurfaceSamplerFixture.Manager(123456789L, uniform);
        SurfaceBiomeSampler sampler = current ? SurfaceSamplerFixture.current(manager) : SurfaceSamplerFixture.reference(manager);
        long checksum = 0;
        long started = System.nanoTime();
        for (int tile = 0; tile < 4; tile++) {
            for (int row = 0; row < 128; row++) {
                for (int column = 0; column < 128; column++) {
                    int x = tile * 512 - 1024 + column * 4 + 2;
                    int z = -256 + row * 4 + 2;
                    checksum = checksum * 31 + sampler.getBiomeAt(x, z).biomeID;
                }
            }
        }
        double millis = (System.nanoTime() - started) / 1_000_000.0;
        checksumSink = checksum;
        return new Measurement(millis, checksum, manager.rawCalls, manager.perlin.calls);
    }

    private static final class Measurement {
        final double millis;
        final long checksum;
        final long rawCalls;
        final long noiseCalls;
        Measurement(double millis, long checksum, long rawCalls, long noiseCalls) {
            this.millis = millis; this.checksum = checksum; this.rawCalls = rawCalls; this.noiseCalls = noiseCalls;
        }
    }
}
