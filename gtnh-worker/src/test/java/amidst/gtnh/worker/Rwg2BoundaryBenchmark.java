package amidst.gtnh.worker;

import static org.junit.Assert.*;

import com.google.gson.GsonBuilder;

import net.minecraft.world.biome.BiomeGenBase;

import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Region-classified installed-algorithm measurements, called after the native fixture is ready. */
final class Rwg2BoundaryBenchmark {
    static void installMountainBorders(ClassLoader loader) throws Exception {
        // The small test JVM does not boot BOP/EtFuturum. Populate representative border
        // pools with RWG2's own mountain wrappers, for both native and detached generators.
        Class<?> support = loader.loadClass("rwg2.support.Support");
        Class<?> base = loader.loadClass("rwg2.biomes.realistic.RealisticBiomeBase");
        Method wrap =
                loader.loadClass("rwg2.biomes.realistic.land.RealisticBiomeMountainChain")
                        .getMethod("forBiome", base);
        Object mountain = wrap.invoke(null, base.getField("woodmountains").get(null));
        for (String climate : new String[] {"snow", "cold", "hot", "wet"}) {
            Object lists = support.getField(climate).get(null);
            for (String edge : new String[] {"veryColdBorder", "veryHotBorder"})
                ((List) lists.getClass().getField(edge).get(lists)).add(mountain);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result;
    }

    static void run(Class<?> type, Method predict) throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructor(long.class, boolean.class);
        ctor.setAccessible(true);
        Method height = type.getDeclaredMethod("getApproximateSurfaceHeight", int.class, int.class);
        height.setAccessible(true);
        List<Object> results = new ArrayList<>();
        long seed = 9182741L;
        for (boolean simplex : new boolean[] {false, true}) {
            long cold = System.nanoTime();
            Object locator = ctor.newInstance(seed, simplex);
            double constructorMillis = (System.nanoTime() - cold) / 1e6;
            Object manager = field(type, "manager").get(locator);
            Method continent =
                    manager.getClass().getMethod("getContinentValue", int.class, int.class);
            Method river = manager.getClass().getMethod("getRiverStrength", int.class, int.class);
            Method junction =
                    manager.getClass().getMethod("getRiverJunctionStrength", int.class, int.class);
            Method biome = manager.getClass().getMethod("getBiomeDataAt", int.class, int.class);
            Map<String, int[]> regions = new LinkedHashMap<>();
            for (int x = -8192; x <= 8192 && regions.size() < 5; x += 32)
                for (int z = -8192; z <= 8192 && regions.size() < 5; z += 32) {
                    float c = (Float) continent.invoke(manager, x + 8, z + 8);
                    if (c < -150 && !regions.containsKey("ocean"))
                        regions.put("ocean", new int[] {x, z});
                    if (Math.abs(c) < 4 && !regions.containsKey("coast"))
                        regions.put("coast", new int[] {x, z});
                    if (c < 24) continue;
                    float r = (Float) river.invoke(manager, x + 8, z + 8);
                    float j = (Float) junction.invoke(manager, x + 8, z + 8);
                    boolean mountain =
                            biome.invoke(manager, x + 8, z + 8)
                                    .getClass()
                                    .getSimpleName()
                                    .equals("RealisticBiomeMountainChain");
                    if (c > 100 && r >= 0 && !mountain && !regions.containsKey("inland"))
                        regions.put("inland", new int[] {x, z});
                    if (r < -.7f && j < .1f && !regions.containsKey("river"))
                        regions.put("river", new int[] {x, z});
                    if (j > .55f && mountain && !regions.containsKey("mountainJunction"))
                        regions.put("mountainJunction", new int[] {x, z});
                }
            assertEquals(
                    "Must measure all representative landforms; found " + regions.keySet(),
                    5,
                    regions.size());
            for (Map.Entry<String, int[]> region : regions.entrySet()) {
                double[][] elapsed = new double[2][5];
                long[][] phases = new long[2][6];
                long[] oceanFast = new long[2];
                int[][] ids = new int[2][128 * 128];
                float[][] heights = new float[2][128 * 128];
                int x0 = region.getValue()[0] - 64, z0 = region.getValue()[1] - 64;
                for (int round = -2; round < 5; round++) {
                    for (int order = 0; order < 2; order++) {
                        int mode = (round + 2 + order) & 1;
                        Object sampler = ctor.newInstance(seed, simplex);
                        field(type, "profile").setBoolean(sampler, true);
                        Object cache = field(type, "biomeSamples").get(sampler);
                        field(cache.getClass(), "optimizeMountainInfluence")
                                .setBoolean(cache, mode == 1);
                        long start = System.nanoTime();
                        for (int z = 0; z < 8; z++)
                            for (int x = 0; x < 8; x++)
                                predict.invoke(sampler, x0 + x * 16, z0 + z * 16);
                        if (round >= 0) {
                            elapsed[mode][round] = (System.nanoTime() - start) / 1e6;
                            long[] measured = (long[]) field(type, "phaseNanos").get(sampler);
                            for (int p = 0; p < measured.length; p++)
                                phases[mode][p] += measured[p];
                        }
                        oceanFast[mode] = field(type, "oceanChunks").getLong(sampler);
                        if (round == 0)
                            for (int z = 0; z < 128; z++)
                                for (int x = 0; x < 128; x++)
                                    ids[mode][z * 128 + x] =
                                            ((BiomeGenBase) predict.invoke(sampler, x0 + x, z0 + z))
                                                    .biomeID;
                        if (round == 0)
                            for (int z = 0; z < 128; z++)
                                for (int x = 0; x < 128; x++)
                                    heights[mode][z * 128 + x] =
                                            (Float) height.invoke(sampler, x0 + x, z0 + z);
                    }
                    if (round == 0) {
                        assertArrayEquals(region.getKey(), ids[0], ids[1]);
                        assertArrayEquals(region.getKey() + " heights", heights[0], heights[1], 0f);
                    }
                }
                Arrays.sort(elapsed[0]);
                Arrays.sort(elapsed[1]);
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("noise", simplex ? "OpenSimplex" : "Perlin");
                record.put("region", region.getKey());
                record.put("origin", new int[] {x0, z0});
                record.put("chunks", 64);
                record.put("constructorMillis", constructorMillis);
                record.put("baselineMedianMillis", elapsed[0][2]);
                record.put("optimizedMedianMillis", elapsed[1][2]);
                record.put("phaseTotalNanos", phases);
                record.put("oceanFastChunks", oceanFast);
                record.put("equalColumns", 128 * 128);
                record.put("equalHeights", 128 * 128);
                results.add(record);
                System.out.println(
                        "Boundary benchmark: " + new GsonBuilder().create().toJson(record));
            }
        }
        Path report = Paths.get("build/reports/performance/rwg2-boundaries.json");
        Files.createDirectories(report.getParent());
        Files.write(
                report,
                new GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(results)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
