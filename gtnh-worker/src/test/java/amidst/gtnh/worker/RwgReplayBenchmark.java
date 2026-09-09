package amidst.gtnh.worker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

import net.minecraft.world.WorldServer;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;

/** Optional frozen developer JAR comparison. Never ship the baseline or use it at runtime. */
public final class RwgReplayBenchmark {
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args[0].isEmpty()) {
            throw new IllegalArgumentException("Pass -PbaselineWorkerJar=<frozen developer JAR>");
        }
        URL baseline = Paths.get(args[0]).toUri().toURL();
        StringBuilder json = new StringBuilder("{\"scope\":\"frozen developer JAR vs current adaptive; controlled RWG-shaped noise and callbacks, not real game timing\",\"cases\":[");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {baseline}, RwgReplayBenchmark.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("amidst.gtnh.worker.SurfaceBiomeSampler")
                        || name.equals("amidst.gtnh.worker.RwgChunkBiomeReplay")
                        || name.equals("amidst.gtnh.worker.RwgTerrainAccess")
                        || name.startsWith("amidst.gtnh.worker.RwgSurfaceEffects")
                        || name.startsWith("amidst.gtnh.worker.RwgRuntimeClasses")
                        || name.equals("amidst.gtnh.worker.AmidstGtnhWorkerLog")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name);
                        if (resolve) resolveClass(type);
                        return type;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Class<?> oldType = loader.loadClass("amidst.gtnh.worker.SurfaceBiomeSamplers$RwgSurfaceBiomeSampler");
            Class<?> newType = Class.forName("amidst.gtnh.worker.SurfaceBiomeSamplers$RwgSurfaceBiomeSampler");
            for (int scenario = 0; scenario < 3; scenario++) {
                measure(oldType, scenario); measure(newType, scenario);
                double[] oldTimes = new double[5], newTimes = new double[5];
                for (int trial = 0; trial < 5; trial++) {
                    Measurement oldResult, newResult;
                    if ((trial & 1) == 0) { oldResult = measure(oldType, scenario); newResult = measure(newType, scenario); }
                    else { newResult = measure(newType, scenario); oldResult = measure(oldType, scenario); }
                    if (oldResult.checksum != newResult.checksum) throw new AssertionError("baseline final biome mismatch");
                    oldTimes[trial] = oldResult.millis; newTimes[trial] = newResult.millis;
                }
                Arrays.sort(oldTimes); Arrays.sort(newTimes);
                String result = String.format(Locale.ROOT,
                        "{\"terrain\":\"%s\",\"tiles\":4,\"samplesPerTile\":16384,\"baselineMedianMs\":%.3f,\"currentMedianMs\":%.3f,\"speedup\":%.3f}",
                        scenario == 0 ? "uniform" : scenario == 1 ? "boundaries" : "mixed-sensitive-surfaces", oldTimes[2], newTimes[2], oldTimes[2] / newTimes[2]);
                if (scenario > 0) json.append(',');
                json.append(result);
                System.out.println(result);
            }
        }
        json.append("]}");
        Files.createDirectories(Paths.get(args[1]).toAbsolutePath().getParent());
        Files.write(Paths.get(args[1]), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Measurement measure(Class<?> samplerType, int scenario) throws Exception {
        Constructor<?> constructor = samplerType.getDeclaredConstructor(WorldServer.class, WorldChunkManager.class);
        constructor.setAccessible(true);
        SurfaceSamplerFixture.Manager manager = new BenchmarkManager(scenario);
        Object sampler = constructor.newInstance(null, manager);
        // Both versions must use the same bytecode dependency proof, including a frozen v36 baseline.
        SurfaceSamplerFixture.enableAdaptive(sampler);
        Method sample = samplerType.getMethod("getBiomeAt", int.class, int.class);
        sample.setAccessible(true);
        long checksum = 0, start = System.nanoTime();
        for (int tile = 0; tile < 4; tile++) for (int z = 0; z < 128; z++) for (int x = 0; x < 128; x++) {
            BiomeGenBase biome = (BiomeGenBase) sample.invoke(sampler, tile * 512 - 1024 + x * 4 + 2, -256 + z * 4 + 2);
            checksum = checksum * 31 + biome.biomeID;
        }
        return new Measurement((System.nanoTime() - start) / 1_000_000.0, checksum);
    }

    public static final class BenchmarkManager extends SurfaceSamplerFixture.Manager {
        private final int scenario;
        private final SurfaceSamplerFixture.RealisticBiome sensitive = new RwgSurfaceEffectsTest.Support(
                new RwgSurfaceEffectsTest.Painter(), new RwgSurfaceEffectsTest.WritingPainter());
        BenchmarkManager(int scenario) { super(123456789L, scenario == 0); this.scenario = scenario; }
        @Override public SurfaceSamplerFixture.RealisticBiome getBiomeDataAt(int x, int z) {
            return scenario == 2 && (Math.floorDiv(x, 128) & 1) == 0 ? sensitive : super.getBiomeDataAt(x, z);
        }
    }

    private static final class Measurement {
        final double millis; final long checksum;
        Measurement(double millis, long checksum) { this.millis = millis; this.checksum = checksum; }
    }
}
