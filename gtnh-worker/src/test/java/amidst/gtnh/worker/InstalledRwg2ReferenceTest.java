package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.block.Block;
import net.minecraft.world.biome.BiomeGenBase;

import org.junit.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Runs the installed 2.0.6 algorithms; SRG member names alone are adapted to the test Minecraft.
 */
public class InstalledRwg2ReferenceTest {
    @Test
    public void detachedReplayMatchesNativeFinalBiomesAndPreservesGameStatics() throws Exception {
        String path = System.getProperty("rwg2ReferenceJar");
        String mappings = System.getProperty("mcpCsvDir");
        Assume.assumeNotNull(path, mappings);
        BiomeGenBase[] registry = BiomeGenBase.getBiomeGenArray().clone();
        try (NativeLoader loader = new NativeLoader(path, mappings)) {
            verifyReadOnlyCallbacks(loader);
            Class<?> referenceBlocks = loader.loadClass("net.minecraft.init.Blocks");
            assertNotSame(
                    referenceBlocks.getField("air").get(null),
                    referenceBlocks.getField("stone").get(null));
            int[] ids =
                    (int[])
                            loader.loadClass("rwg2.config.ConfigRWG")
                                    .getField("biomeIDs")
                                    .get(null);
            for (int i = 0; i < ids.length; i++) ids[i] = 200 + i;
            loader.loadClass("rwg2.biomes.base.BaseBiomes").getMethod("load").invoke(null);
            // This test JVM has vanilla's registry size. Keep real biome objects, but expose
            // the installed pack's extended IDs to the complete sampler/output path.
            Field id = BiomeGenBase.class.getField("biomeID");
            id.setAccessible(true);
            for (int i = 0; i < ids.length; i++)
                id.setInt(BiomeGenBase.getBiomeGenArray()[200 + i], 300 + i);
            Class<?> support = loader.loadClass("rwg2.support.Support");
            support.getMethod("init", boolean.class).invoke(null, false);
            Rwg2BoundaryBenchmark.installMountainBorders(loader);
            Object volcano =
                    loader.loadClass("rwg2.biomes.realistic.ocean.RealisticBiomeIslandVolcano")
                            .getConstructor(BiomeGenBase.class, Block.class, Block.class)
                            .newInstance(
                                    BiomeGenBase.hell,
                                    referenceBlocks.getField("sand").get(null),
                                    referenceBlocks.getField("stone").get(null));
            support.getField("volcanoIsland").set(null, volcano);
            support.getField("lavaCaveMarkerBiome").set(null, BiomeGenBase.hell);
            Class<?> runtime = loader.loadClass("amidst.gtnh.worker.Rwg2Runtime");
            byte[] raw = loader.raw("rwg2/world/ChunkGeneratorRealistic.class");
            Rwg2Runtime.verify(
                    raw, "1517b2306a0db3c220a0afb63569c4b5e95658ba5c9d593c950ae273ca675d55");
            Class<?> detached = loader.defineMapped(Rwg2Runtime.detach(raw));
            field(runtime, "generatorCopy").set(null, detached);
            Class<?> samplerClass = loader.loadClass("amidst.gtnh.worker.Rwg2SurfaceBiomeSampler");
            Constructor<?> samplerConstructor =
                    samplerClass.getDeclaredConstructor(long.class, boolean.class);
            samplerConstructor.setAccessible(true);
            Method predict =
                    samplerClass.getDeclaredMethod("getPredictedBiomeAt", int.class, int.class);
            predict.setAccessible(true);
            Method underground =
                    samplerClass.getDeclaredMethod("dungeonTerrain", int.class, int.class);
            underground.setAccessible(true);
            Method viable =
                    samplerClass.getDeclaredMethod("isVillageLocationViable", int.class, int.class);
            viable.setAccessible(true);
            // The independent reference removes ordinary cave/structure map-gen calls. Compare
            // all terrain blocks with caves disabled, including RWG2 lava and volcano chambers.
            loader.loadClass("rwg2.config.ConfigRWG")
                    .getField("generateCaves")
                    .setBoolean(null, false);
            Class<?> managerClass = loader.loadClass("rwg2.world.ChunkManagerRealistic");
            Class<?> nativeClass = loader.loadClass("rwg2.world.ChunkGeneratorRealistic");
            Class<?> dataClass = loader.loadClass("rwg2.world.RwgWorldSavedData");
            Object data = dataClass.getConstructor().newInstance();
            field(dataClass, "INSTANCE").set(null, data);
            Class<?> modeClass = loader.loadClass("rwg2.util.NoiseImplementation");
            Field mode = field(dataClass, "noiseImplementation");
            Field switchField =
                    loader.loadClass("rwg2.util.NoiseGeneratorWrapper").getField("useOpenSimplex");
            Field palette = field(loader.loadClass("rwg2.util.CanyonColor"), "claycolor");
            int points = 0, extended = 0;
            for (boolean simplex : new boolean[] {false, true})
                for (long seed : new long[] {0, -47}) {
                    mode.set(
                            data,
                            Enum.valueOf((Class) modeClass, simplex ? "OPENSIMPLEX" : "PERLIN"));
                    Object nativeManager =
                            managerClass
                                    .getConstructor(long.class, boolean.class)
                                    .newInstance(seed, true);
                    Object nativeGenerator =
                            nativeClass
                                    .getConstructor(managerClass, long.class, boolean.class)
                                    .newInstance(nativeManager, seed, true);
                    byte[] colors = ((byte[]) palette.get(null)).clone();
                    // A deliberately different global switch must not leak into explicit fixed
                    // noise modes.
                    switchField.setBoolean(null, !simplex);
                    ((net.minecraft.world.WorldSavedData) data).setDirty(false);
                    Object sampler = samplerConstructor.newInstance(seed, simplex);
                    field(nativeClass, "worldObj")
                            .set(nativeGenerator, field(samplerClass, "previewWorld").get(sampler));
                    Method provide = nativeClass.getMethod("provideChunk", int.class, int.class);
                    List<int[]> chunks =
                            new ArrayList<>(
                                    Arrays.asList(
                                            new int[][] {
                                                {0, 0},
                                                {-1, 2},
                                                {87, -143},
                                                {-192, 75},
                                                {250, 250},
                                                {-311, -223}
                                            }));
                    int[] ocean = findOcean(samplerClass, sampler);
                    chunks.add(
                            new int[] {Math.floorDiv(ocean[0], 16), Math.floorDiv(ocean[1], 16)});
                    Object continents = field(managerClass, "continents").get(nativeManager);
                    for (String landmark :
                            new String[] {
                                "getVolcanoCenterCoordinates", "getLavaCaveCenterCoordinates"
                            }) {
                        Method locate =
                                continents.getClass().getMethod(landmark, int.class, int.class);
                        long center = Long.MIN_VALUE;
                        for (int x = -12000; x <= 12000 && center == Long.MIN_VALUE; x += 192)
                            for (int z = -12000; z <= 12000 && center == Long.MIN_VALUE; z += 192)
                                center = (Long) locate.invoke(continents, x, z);
                        assertNotEquals("Must exercise " + landmark, Long.MIN_VALUE, center);
                        int cx = Math.floorDiv((int) (center >> 32), 16),
                                cz = Math.floorDiv((int) center, 16);
                        for (int dx : new int[] {-3, 0, 3})
                            for (int dz : new int[] {-3, 0, 3})
                                chunks.add(new int[] {cx + dx, cz + dz});
                    }
                    for (int[] chunk : chunks) {
                        int bx = chunk[0] * 16 + 8, bz = chunk[1] * 16 + 8;
                        assertEquals(
                                ((net.minecraft.world.biome.WorldChunkManager) nativeManager)
                                        .areBiomesViable(bx, bz, 0, Collections.emptyList()),
                                viable.invoke(sampler, bx, bz));
                        provide.invoke(nativeGenerator, chunk[0], chunk[1]);
                        BiomeGenBase[] expected =
                                ((BiomeGenBase[])
                                                field(nativeClass, "baseBiomesList")
                                                        .get(nativeGenerator))
                                        .clone();
                        Block[] expectedTerrain =
                                ((Block[]) field(nativeClass, "chunkBlocks").get(nativeGenerator))
                                        .clone();
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                BiomeGenBase actual =
                                        (BiomeGenBase)
                                                predict.invoke(
                                                        sampler,
                                                        chunk[0] * 16 + x,
                                                        chunk[1] * 16 + z);
                                assertSame(
                                        "seed="
                                                + seed
                                                + " simplex="
                                                + simplex
                                                + " chunk="
                                                + Arrays.toString(chunk)
                                                + " column="
                                                + x
                                                + ","
                                                + z,
                                        expected[z * 16 + x],
                                        actual);
                                points++;
                                if (actual.biomeID >= 300) extended++;
                            }
                        assertArrayEquals(
                                "Underground terrain at " + Arrays.toString(chunk),
                                expectedTerrain,
                                (Block[]) underground.invoke(sampler, chunk[0], chunk[1]));
                    }
                    assertArrayEquals(colors, (byte[]) palette.get(null));
                    assertEquals(!simplex, switchField.getBoolean(null));
                    assertFalse(((net.minecraft.world.WorldSavedData) data).isDirty());
                    assertTrue(field(samplerClass, "oceanChunks").getLong(sampler) > 0);
                    checkLandmarkAccess(loader, samplerClass, sampler, nativeManager);
                    checkSpawnWalk(
                            loader, samplerClass, sampler, nativeManager, nativeGenerator, seed);
                }
            assertTrue(extended > 0);
            compareCachedReplay(samplerClass, predict);
            compareExactReplay(samplerClass, predict);
            verifyBackgroundServer(loader, samplerClass, predict, 8);
            verifyBackgroundServer(loader, samplerClass, predict, 1);
            compareParallelReplay(samplerClass, predict);
            Rwg2BoundaryBenchmark.run(samplerClass, predict);
            benchmarkJunctions(loader, samplerClass);
            verifyPoiReplay(loader, samplerClass);
            Class<?> requestClass =
                    loader.loadClass("amidst.gtnh.worker.BiomeWorkerServer$Request");
            Constructor<?> requestCtor = requestClass.getDeclaredConstructor();
            requestCtor.setAccessible(true);
            Class<?> keyClass =
                    loader.loadClass("amidst.gtnh.worker.BiomeWorkerServer$BiomeTileKey");
            Constructor<?> keyCtor = keyClass.getDeclaredConstructor(requestClass);
            keyCtor.setAccessible(true);
            Object request = requestCtor.newInstance();
            field(requestClass, "generator").set(request, "RWG");
            Object alphaKey = keyCtor.newInstance(request);
            field(requestClass, "generator").set(request, "RWG2_CONTINENTS");
            Object smoothKey = keyCtor.newInstance(request);
            assertNotEquals(alphaKey, smoothKey);
            assertEquals(smoothKey, keyCtor.newInstance(request));
            mode.set(data, Enum.valueOf((Class) modeClass, "DYNAMICPERLIN"));
            switchField.setBoolean(null, false);
            Object perlinKey = keyCtor.newInstance(request);
            switchField.setBoolean(null, true);
            Object simplexKey = keyCtor.newInstance(request);
            assertNotEquals(perlinKey, simplexKey);
            System.out.println(
                    "Installed RWG2: "
                            + points
                            + " final biome columns matched, "
                            + extended
                            + " extended IDs; game statics unchanged");
        } finally {
            System.arraycopy(registry, 0, BiomeGenBase.getBiomeGenArray(), 0, registry.length);
        }
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static Block referenceBlock(Class<?> type, String name) throws Exception {
        // Terrain replay needs distinct block identities/materials, not a running Forge registry.
        // Keep this fixture inside the reference loader so other tests/game classes are untouched.
        sun.misc.Unsafe unsafe =
                (sun.misc.Unsafe) field(sun.misc.Unsafe.class, "theUnsafe").get(null);
        if (type == net.minecraft.block.BlockLiquid.class)
            type = net.minecraft.block.BlockStaticLiquid.class;
        if (type == net.minecraft.block.BlockLeaves.class)
            type = net.minecraft.block.BlockOldLeaf.class;
        if (type == net.minecraft.block.BlockSlab.class)
            type = net.minecraft.block.BlockStoneSlab.class;
        Block block = (Block) unsafe.allocateInstance(type);
        net.minecraft.block.material.Material material =
                name.equals("air")
                        ? net.minecraft.block.material.Material.air
                        : name.contains("water")
                                ? net.minecraft.block.material.Material.water
                                : name.contains("lava")
                                        ? net.minecraft.block.material.Material.lava
                                        : net.minecraft.block.material.Material.rock;
        field(Block.class, "blockMaterial").set(block, material);
        return block;
    }

    private static void verifyReadOnlyCallbacks(NativeLoader loader) throws Exception {
        Set<String> methods =
                new HashSet<>(
                        Arrays.asList(
                                "rNoise",
                                "rNoiseAt",
                                "rReplace",
                                "rReplaceAt",
                                "rMapGen",
                                "generateMapGen",
                                "paintTerrain",
                                "generateNoise"));
        int checked = 0;
        for (Enumeration<JarEntry> entries = loader.jar.entries(); entries.hasMoreElements(); ) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".class")
                    || !(name.startsWith("rwg2/biomes/realistic/")
                            || name.startsWith("rwg2/terrain/")
                            || name.startsWith("rwg2/surface/")
                            || name.equals("rwg2/support/RealisticBiomeSupport.class"))) continue;
            ClassNode type = new ClassNode();
            new ClassReader(loader.raw(name)).accept(type, 0);
            for (MethodNode method : type.methods)
                if (methods.contains(method.name)) {
                    checked++;
                    for (AbstractInsnNode instruction : method.instructions.toArray()) {
                        assertFalse(
                                name + "." + method.name + " must not modify shared fields",
                                instruction.getOpcode() == Opcodes.PUTFIELD
                                        || instruction.getOpcode() == Opcodes.PUTSTATIC);
                        if (instruction instanceof MethodInsnNode) {
                            MethodInsnNode call = (MethodInsnNode) instruction;
                            if (call.owner.equals("net/minecraft/world/World"))
                                assertTrue(
                                        "Callbacks may only read the detached world's seed",
                                        call.name.equals("func_72905_C")
                                                || call.name.equals("getSeed"));
                        }
                    }
                }
        }
        assertTrue("Cover native and ported biome callbacks", checked >= 160);
    }

    private static void verifyBackgroundServer(
            NativeLoader loader, Class<?> samplerClass, Method predict, int threads)
            throws Exception {
        Class<?> guard = loader.loadClass("amidst.gtnh.worker.Rwg2ParallelSupport");
        Method available = guard.getDeclaredMethod("available");
        available.setAccessible(true);
        assertEquals(
                "Installed 2.0.6 callbacks must pass the parallel audit",
                true,
                available.invoke(null));
        Class<?> serverClass = loader.loadClass("amidst.gtnh.worker.BiomeWorkerServer");
        Class<?> poolClass = loader.loadClass("amidst.gtnh.worker.Rwg2SamplingPool");
        Class<?> factoryClass = loader.loadClass("amidst.gtnh.worker.Rwg2SamplingPool$Factory");
        Class<?> samplerInterface = loader.loadClass("amidst.gtnh.worker.SurfaceBiomeSampler");
        Constructor<?> constructor = samplerClass.getDeclaredConstructor(long.class, boolean.class);
        constructor.setAccessible(true);
        Thread game = Thread.currentThread();
        java.util.concurrent.CountDownLatch entered =
                new java.util.concurrent.CountDownLatch(Math.min(4, threads));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Object factory =
                java.lang.reflect.Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {factoryClass},
                        (proxy, method, args) -> {
                            assertSame(
                                    "Context preparation belongs to the game thread",
                                    game,
                                    Thread.currentThread());
                            Object sampler = constructor.newInstance(args);
                            java.util.concurrent.atomic.AtomicBoolean first =
                                    new java.util.concurrent.atomic.AtomicBoolean(true);
                            return java.lang.reflect.Proxy.newProxyInstance(
                                    loader,
                                    new Class<?>[] {samplerInterface},
                                    (p, m, a) -> {
                                        assertNotSame(
                                                "Native biome calculation belongs to the background"
                                                        + " pool",
                                                game,
                                                Thread.currentThread());
                                        if (first.getAndSet(false)) {
                                            entered.countDown();
                                            assertTrue(
                                                    release.await(
                                                            5,
                                                            java.util.concurrent.TimeUnit.SECONDS));
                                        }
                                        return predict.invoke(sampler, a);
                                    });
                        });
        Constructor<?> poolConstructor = poolClass.getDeclaredConstructor(factoryClass, int.class);
        poolConstructor.setAccessible(true);
        Object pool = poolConstructor.newInstance(factory, threads);
        int port;
        try (java.net.ServerSocket reservation = new java.net.ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        Constructor<?> serverConstructor =
                serverClass.getDeclaredConstructor(int.class, String.class);
        serverConstructor.setAccessible(true);
        Object server = serverConstructor.newInstance(port, "");
        field(serverClass, "smoothSamplingPool").set(server, pool);
        Method start = serverClass.getDeclaredMethod("start"),
                tick = serverClass.getDeclaredMethod("executeQueuedQueries"),
                close = serverClass.getDeclaredMethod("close");
        start.setAccessible(true);
        tick.setAccessible(true);
        close.setAccessible(true);
        java.util.concurrent.ExecutorService readers =
                java.util.concurrent.Executors.newCachedThreadPool();
        List<java.net.Socket> sockets = new ArrayList<>();
        List<java.util.concurrent.Future<String>> responses = new ArrayList<>();
        try {
            assertEquals(true, start.invoke(server));
            for (int i = 0; i < 4; i++) {
                java.net.Socket socket = new java.net.Socket("127.0.0.1", port);
                sockets.add(socket);
                socket.setSoTimeout(5000);
                socket.getOutputStream()
                        .write(
                                ("{\"protocol\":31,\"command\":\"biomes\",\"generator\":\"RWG2_CONTINENTS\",\"seed\":"
                                                + (i == 2 ? -47 : 5)
                                                + ",\"x\":"
                                                + (i * 512 - 512)
                                                + ",\"width\":8,\"height\":8,\"step\":4,\"parallelism\":8,\"profile\":true}\n")
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                responses.add(
                        readers.submit(
                                () ->
                                        new BufferedReader(
                                                        new InputStreamReader(
                                                                socket.getInputStream(),
                                                                java.nio.charset.StandardCharsets
                                                                        .UTF_8))
                                                .readLine()));
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            int admitted = 0;
            while ((entered.getCount() != 0 || admitted < 4) && System.nanoTime() < deadline) {
                tick.invoke(server);
                synchronized (pool) {
                    admitted =
                            ((java.util.Set<?>) field(poolClass, "jobs").get(pool)).size()
                                    + ((java.util.Set<?>)
                                                    field(poolClass, "capacityWaiters").get(pool))
                                            .size();
                }
                Thread.sleep(2);
            }
            assertEquals(4, admitted);
            assertEquals(0, entered.getCount());
            assertTrue(
                    "Background waits must not spin in the game queue",
                    ((java.util.Queue<?>) field(serverClass, "serverThreadQueries").get(server))
                            .isEmpty());
            // Control traffic must work even while all four sampling threads are held.
            try (java.net.Socket status = new java.net.Socket("127.0.0.1", port)) {
                status.getOutputStream()
                        .write(
                                "{\"protocol\":31,\"command\":\"world_state\"}\n"
                                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.util.concurrent.Future<String> response =
                        readers.submit(
                                () ->
                                        new BufferedReader(
                                                        new InputStreamReader(
                                                                status.getInputStream()))
                                                .readLine());
                while (!response.isDone() && System.nanoTime() < deadline) {
                    tick.invoke(server);
                    Thread.sleep(2);
                }
                assertTrue(
                        response.get(1, java.util.concurrent.TimeUnit.SECONDS)
                                .contains("\"ok\":true"));
            }
            sockets.get(0).getOutputStream().write(0x18);
            sockets.get(0).getOutputStream().flush();
            assertNull(
                    "Cancelling a parked query needs no more game ticks",
                    responses.get(0).get(1, java.util.concurrent.TimeUnit.SECONDS));
            release.countDown();
            while (responses.stream().anyMatch(f -> !f.isDone()) && System.nanoTime() < deadline) {
                tick.invoke(server);
                Thread.sleep(2);
            }
            for (int i = 1; i < 4; i++) {
                com.google.gson.JsonObject response =
                        new com.google.gson.JsonParser()
                                .parse(
                                        responses
                                                .get(i)
                                                .get(1, java.util.concurrent.TimeUnit.SECONDS))
                                .getAsJsonObject();
                assertTrue(response.toString(), response.get("ok").getAsBoolean());
                assertTrue(response.get("backgroundMillis").getAsDouble() >= 0);
                assertTrue(
                        "Only preparation/overlay slices are allowed",
                        response.get("slices").getAsInt() <= 6);
                Object serial = constructor.newInstance(i == 2 ? -47L : 5L, true);
                for (int z = 0; z < 8; z++)
                    for (int x = 0; x < 8; x++)
                        assertEquals(
                                ((BiomeGenBase)
                                                predict.invoke(
                                                        serial,
                                                        i * 512 - 512 + x * 4 + 2,
                                                        z * 4 + 2))
                                        .biomeID,
                                response.getAsJsonArray("ids").get(z * 8 + x).getAsInt());
            }
        } finally {
            release.countDown();
            close.invoke(server);
            for (java.net.Socket socket : sockets) socket.close();
            readers.shutdownNow();
        }
    }

    private static void compareParallelReplay(Class<?> samplerClass, Method predict)
            throws Exception {
        Constructor<?> constructor = samplerClass.getDeclaredConstructor(long.class, boolean.class);
        constructor.setAccessible(true);
        java.util.concurrent.ExecutorService workers =
                java.util.concurrent.Executors.newFixedThreadPool(4);
        List<String> rounds = new ArrayList<>();
        try {
            for (int round = 0; round < 3; round++) {
                List<java.util.concurrent.Callable<int[]>> serial = new ArrayList<>(),
                        parallel = new ArrayList<>();
                long preparation = System.nanoTime();
                for (int i = 0; i < 4; i++) {
                    long seed = i % 2 == 0 ? 9182741L : -47L;
                    int x0 = -512 + round * 2048 + i * 512;
                    for (List<java.util.concurrent.Callable<int[]>> target :
                            Arrays.asList(serial, parallel)) {
                        Object sampler = constructor.newInstance(seed, (i & 2) != 0);
                        target.add(
                                () -> {
                                    int[] ids = new int[128 * 128];
                                    for (int z = 0; z < 128; z++)
                                        for (int x = 0; x < 128; x++)
                                            ids[z * 128 + x] =
                                                    ((BiomeGenBase)
                                                                    predict.invoke(
                                                                            sampler,
                                                                            x0 + x * 4 + 2,
                                                                            128 + z * 4 + 2))
                                                            .biomeID;
                                    return ids;
                                });
                    }
                }
                double preparationMillis = (System.nanoTime() - preparation) / 1e6;
                List<int[]> expected = new ArrayList<>();
                long start = System.nanoTime();
                // Alternate measured order as well; both groups use cold independent contexts.
                List<java.util.concurrent.Future<int[]>> actual;
                double serialMillis, parallelMillis;
                if (round % 2 == 0) {
                    for (java.util.concurrent.Callable<int[]> work : serial)
                        expected.add(work.call());
                    serialMillis = (System.nanoTime() - start) / 1e6;
                    start = System.nanoTime();
                    actual = workers.invokeAll(parallel);
                    parallelMillis = (System.nanoTime() - start) / 1e6;
                } else {
                    actual = workers.invokeAll(parallel);
                    parallelMillis = (System.nanoTime() - start) / 1e6;
                    start = System.nanoTime();
                    for (java.util.concurrent.Callable<int[]> work : serial)
                        expected.add(work.call());
                    serialMillis = (System.nanoTime() - start) / 1e6;
                }
                for (int i = 0; i < 4; i++) assertArrayEquals(expected.get(i), actual.get(i).get());
                rounds.add(
                        String.format(
                                Locale.ROOT,
                                "{\"serialMillis\":%.3f,\"parallelMillis\":%.3f,\"eightContextsPreparationMillis\":%.3f}",
                                serialMillis,
                                parallelMillis,
                                preparationMillis));
            }
        } finally {
            workers.shutdownNow();
        }
        Path report = Paths.get("build/reports/performance/rwg2-parallel.json");
        Files.createDirectories(report.getParent());
        Files.write(
                report,
                ("{\"threads\":4,\"tilesPerRound\":4,\"equalFineSamples\":196608,\"rounds\":["
                                + String.join(",", rounds)
                                + "]}")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void compareExactReplay(Class<?> samplerClass, Method predict) throws Exception {
        Constructor<?> constructor =
                samplerClass.getDeclaredConstructor(
                        long.class, boolean.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        double[][] timings = new double[2][6], cpuTimings = new double[2][6];
        int[][] ids = new int[2][512 * 512];
        java.lang.management.ThreadMXBean cpu =
                java.lang.management.ManagementFactory.getThreadMXBean();
        boolean cpuAvailable = cpu.isCurrentThreadCpuTimeSupported();
        if (cpuAvailable && !cpu.isThreadCpuTimeEnabled()) cpu.setThreadCpuTimeEnabled(true);
        // Warm both paths, then alternate execution order to reduce JIT/order bias.
        for (int round = -2; round < 6; round++) {
            int x0 = -512 + (round + 2) * 512, z0 = 128;
            for (int order = 0; order < 2; order++) {
                int mode = (round + 2 + order) & 1;
                Object sampler = constructor.newInstance(9182741L, false, true, mode == 1);
                long cpuStart = cpuAvailable ? cpu.getCurrentThreadCpuTime() : 0;
                long start = System.nanoTime();
                for (int z = 0; z < 32; z++)
                    for (int x = 0; x < 32; x++) predict.invoke(sampler, x0 + x * 16, z0 + z * 16);
                if (round >= 0) {
                    timings[mode][round] = (System.nanoTime() - start) / 1e6;
                    cpuTimings[mode][round] =
                            cpuAvailable ? (cpu.getCurrentThreadCpuTime() - cpuStart) / 1e6 : -1;
                }
                for (int z = 0; z < 512; z++)
                    for (int x = 0; x < 512; x++)
                        ids[mode][z * 512 + x] =
                                ((BiomeGenBase) predict.invoke(sampler, x0 + x, z0 + z)).biomeID;
            }
            assertArrayEquals("Exact optimization must preserve every column", ids[0], ids[1]);
        }
        String baseline = Arrays.toString(timings[0]), optimized = Arrays.toString(timings[1]);
        String baselineCpu = Arrays.toString(cpuTimings[0]),
                optimizedCpu = Arrays.toString(cpuTimings[1]);
        Arrays.sort(timings[0]);
        Arrays.sort(timings[1]);
        Arrays.sort(cpuTimings[0]);
        Arrays.sort(cpuTimings[1]);
        String json =
                String.format(
                        Locale.ROOT,
                        "{\"chunksPerRound\":1024,\"warmupRounds\":2,\"rounds\":6,\"baselineMillis\":%s,\"optimizedMillis\":%s,\"baselineMedianMillis\":%.3f,\"optimizedMedianMillis\":%.3f,\"baselineCpuMillis\":%s,\"optimizedCpuMillis\":%s,\"baselineCpuMedianMillis\":%.3f,\"optimizedCpuMedianMillis\":%.3f,\"equalColumns\":2097152}",
                        baseline,
                        optimized,
                        (timings[0][2] + timings[0][3]) / 2,
                        (timings[1][2] + timings[1][3]) / 2,
                        baselineCpu,
                        optimizedCpu,
                        (cpuTimings[0][2] + cpuTimings[0][3]) / 2,
                        (cpuTimings[1][2] + cpuTimings[1][3]) / 2);
        Path report = Paths.get("build/reports/performance/rwg2-exact.json");
        Files.createDirectories(report.getParent());
        Files.write(report, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("RWG2 exact replay benchmark: " + json);
    }

    private static void compareCachedReplay(Class<?> samplerClass, Method predict)
            throws Exception {
        Constructor<?> constructor =
                samplerClass.getDeclaredConstructor(long.class, boolean.class, boolean.class);
        constructor.setAccessible(true);
        Object locator = constructor.newInstance(9182741L, false, true);
        int[] ocean = findOcean(samplerClass, locator);
        String landResult = compareRegion(samplerClass, constructor, predict, -128, 128, false);
        String oceanResult =
                compareRegion(samplerClass, constructor, predict, ocean[0], ocean[1], true);
        Path report = Paths.get("build/reports/performance/rwg2-cache.json");
        Files.createDirectories(report.getParent());
        String json = "{\"land\":" + landResult + ",\"ocean\":" + oceanResult + "}";
        Files.write(report, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("RWG2 adjacent chunk benchmark: " + json);
    }

    private static String compareRegion(
            Class<?> samplerClass,
            Constructor<?> constructor,
            Method predict,
            int originX,
            int originZ,
            boolean ocean)
            throws Exception {
        int[][] ids = new int[2][64 * 256];
        long[] nanos = new long[2], misses = new long[2];
        Object[] samplers = new Object[2];
        long fast = 0;
        for (int mode = 0; mode < 2; mode++) {
            Object sampler = samplers[mode] = constructor.newInstance(9182741L, false, mode == 1);
            long start = System.nanoTime();
            // Time chunk generation, then compare every column outside the timing window.
            for (int z = 0; z < 8; z++)
                for (int x = 0; x < 8; x++)
                    predict.invoke(sampler, originX + x * 16, originZ + z * 16);
            nanos[mode] = System.nanoTime() - start;
            for (int z = 0; z < 128; z++)
                for (int x = 0; x < 128; x++)
                    ids[mode][z * 128 + x] =
                            ((BiomeGenBase) predict.invoke(sampler, originX + x, originZ + z))
                                    .biomeID;
            Object cache = field(samplerClass, "biomeSamples").get(sampler);
            misses[mode] = field(cache.getClass(), "misses").getLong(cache);
            if (mode == 1) fast = field(samplerClass, "oceanChunks").getLong(sampler);
        }
        assertArrayEquals("Caching must not change any sampled biome", ids[0], ids[1]);
        assertTrue("Adjacent chunks must reuse blend inputs", misses[1] < misses[0] / 5);
        if (ocean) assertTrue("Must exercise the pure-ocean shortcut", fast > 0);
        Method height =
                samplerClass.getDeclaredMethod("getApproximateSurfaceHeight", int.class, int.class);
        height.setAccessible(true);
        assertEquals(
                "A height query must upgrade a biome-only ocean tile to full replay",
                height.invoke(samplers[0], originX + 2, originZ + 3),
                height.invoke(samplers[1], originX + 2, originZ + 3));
        return String.format(
                Locale.ROOT,
                "{\"chunks\":64,\"x\":%d,\"z\":%d,\"fullReplayMillis\":%.3f,\"optimizedMillis\":%.3f,\"uncachedBiomeCalls\":%d,\"cachedBiomeCalls\":%d,\"oceanFastChunks\":%d}",
                originX,
                originZ,
                nanos[0] / 1e6,
                nanos[1] / 1e6,
                misses[0],
                misses[1],
                fast);
    }

    private static int[] findOcean(Class<?> samplerClass, Object sampler) throws Exception {
        Method homogeneous =
                samplerClass.getDeclaredMethod("homogeneousOcean", int.class, int.class);
        homogeneous.setAccessible(true);
        for (int x = -8192; x <= 8192; x += 256)
            for (int z = -8192; z <= 8192; z += 256)
                if ((Boolean) homogeneous.invoke(sampler, x / 16, z / 16)) return new int[] {x, z};
        throw new AssertionError("No pure ocean fixture found");
    }

    private static void checkLandmarkAccess(
            NativeLoader loader, Class<?> samplerClass, Object sampler, Object manager)
            throws Exception {
        Class<?> terrainClass =
                loader.loadClass("amidst.gtnh.worker.Rwg2StructurePredictor$NativeTerrain");
        Constructor<?> constructor = terrainClass.getDeclaredConstructor(samplerClass);
        constructor.setAccessible(true);
        Object terrain = constructor.newInstance(sampler);
        Method river = terrainClass.getDeclaredMethod("riverCenter", int.class, int.class);
        river.setAccessible(true);
        Class<?> landmarks = loader.loadClass("rwg2.support.LandmarkDecorations");
        Method junction =
                landmarks.getDeclaredMethod(
                        "findJunctionCenter", manager.getClass(), int.class, int.class);
        junction.setAccessible(true);
        assertArrayEquals(
                (int[]) junction.invoke(null, manager, -16, 0),
                (int[]) river.invoke(terrain, -16, 0));
        Method mix = landmarks.getDeclaredMethod("mix", long.class);
        mix.setAccessible(true);
        for (long seed : new long[] {0, 3, 6, 16, -47, Long.MIN_VALUE}) {
            long value = seed ^ ((long) -3 << 32 ^ 8L) ^ 0x94D049BB133111EBL;
            assertEquals(mix.invoke(null, value), Rwg2StructurePredictor.mix(value));
        }
    }

    private static void verifyPoiReplay(NativeLoader loader, Class<?> samplerClass)
            throws Exception {
        Constructor<?> samplerCtor = samplerClass.getDeclaredConstructor(long.class, boolean.class);
        samplerCtor.setAccessible(true);
        Method underground = samplerClass.getDeclaredMethod("dungeonTerrain", int.class, int.class);
        underground.setAccessible(true);
        Method prepare = samplerClass.getDeclaredMethod("prepareUnderground");
        prepare.setAccessible(true);
        Thread game = Thread.currentThread();
        try (Rwg2PoiPool<Object> pool =
                new Rwg2PoiPool<>(
                        (seed, simplex) -> {
                            assertSame(game, Thread.currentThread());
                            try {
                                Object context = samplerCtor.newInstance(seed, simplex);
                                prepare.invoke(context);
                                return context;
                            } catch (Exception e) {
                                throw new AssertionError(e);
                            }
                        },
                        4)) {
            for (boolean simplex : new boolean[] {false, true}) {
                Object serial = samplerCtor.newInstance(-47L, simplex);
                List<Block[]> expected = new ArrayList<>();
                for (int x = -2; x < 2; x++)
                    expected.add((Block[]) underground.invoke(serial, x, -1));
                java.util.concurrent.CountDownLatch entered =
                        new java.util.concurrent.CountDownLatch(4);
                List<Rwg2PoiPool<Object>.Job<List<Block[]>>> jobs = new ArrayList<>();
                for (int parallel = 0; parallel < 4; parallel++)
                    jobs.add(
                            pool.submit(
                                    -47L,
                                    simplex,
                                    4,
                                    context -> {
                                        assertNotSame(game, Thread.currentThread());
                                        try {
                                            entered.countDown();
                                            if (!entered.await(
                                                    5, java.util.concurrent.TimeUnit.SECONDS))
                                                throw new AssertionError("POIs did not overlap");
                                            List<Block[]> actual = new ArrayList<>();
                                            for (int x = -2; x < 2; x++)
                                                actual.add(
                                                        (Block[])
                                                                underground.invoke(context, x, -1));
                                            return actual;
                                        } catch (Exception e) {
                                            throw new AssertionError(e);
                                        }
                                    },
                                    () -> {}));
                for (Rwg2PoiPool<Object>.Job<List<Block[]>> job : jobs) {
                    List<Block[]> actual = job.result.get(5, java.util.concurrent.TimeUnit.SECONDS);
                    for (int i = 0; i < expected.size(); i++)
                        assertArrayEquals(expected.get(i), actual.get(i));
                }
            }
        }
    }

    private static void checkSpawnWalk(
            NativeLoader loader,
            Class<?> samplerClass,
            Object sampler,
            Object manager,
            Object nativeGenerator,
            long seed)
            throws Exception {
        Method initial = samplerClass.getDeclaredMethod("findSpawnBiomePosition", Random.class);
        Method surface = samplerClass.getDeclaredMethod("getSpawnSurface", int.class, int.class);
        initial.setAccessible(true);
        surface.setAccessible(true);
        Random unchanged = new Random(seed), actualRandom = new Random(seed);
        assertNull(initial.invoke(sampler, actualRandom));
        assertEquals(
                "RWG2 initial spawn consumes no random draws",
                unchanged.nextLong(),
                actualRandom.nextLong());
        Method provide = nativeGenerator.getClass().getMethod("provideChunk", int.class, int.class);
        Class<?> blocksClass = loader.loadClass("net.minecraft.init.Blocks");
        Block air = (Block) blocksClass.getField("air").get(null);
        Block grass = (Block) blocksClass.getField("grass").get(null);
        Block sand = (Block) blocksClass.getField("sand").get(null);
        Block stone = (Block) blocksClass.getField("stone").get(null);
        Block snow = (Block) blocksClass.getField("snow_layer").get(null);
        Class<?> terrain = loader.loadClass("amidst.gtnh.worker.SpawnSearch$Terrain");
        Object proxy =
                java.lang.reflect.Proxy.newProxyInstance(
                        loader,
                        new Class<?>[] {terrain},
                        (p, m, args) ->
                                m.getName().equals("initial")
                                        ? initial.invoke(sampler, args)
                                        : surface.invoke(sampler, args));
        Class<?> jobClass = loader.loadClass("amidst.gtnh.worker.SpawnSearch$Job");
        Constructor<?> ctor = jobClass.getDeclaredConstructor(long.class, String.class, terrain);
        ctor.setAccessible(true);
        Method advance = jobClass.getDeclaredMethod("advance", long.class);
        advance.setAccessible(true);
        Map<Long, Block[]> chunks = new HashMap<>();
        Map<Long, int[]> tops = new HashMap<>();
        Map<Long, BiomeGenBase[]> biomes = new HashMap<>();
        for (String provider : new String[] {SpawnSearch.VANILLA, SpawnSearch.BOP}) {
            Random random = new Random(seed);
            int x = 0, z = 0, moves = 0;
            while (moves < 1000) {
                int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
                long key = (long) cx << 32 ^ cz & 0xffffffffL;
                if (!chunks.containsKey(key)) {
                    provide.invoke(nativeGenerator, cx, cz);
                    Block[] nativeBlocks =
                            (Block[])
                                    field(nativeGenerator.getClass(), "chunkBlocks")
                                            .get(nativeGenerator);
                    Block[] columns = new Block[256];
                    int[] heights = new int[256];
                    for (int dx = 0; dx < 16; dx++)
                        for (int dz = 0; dz < 16; dz++) {
                            int offset = (dx * 16 + dz) * 256, y = 63;
                            while (y < 255
                                    && nativeBlocks[offset + y + 1] != null
                                    && nativeBlocks[offset + y + 1] != air) y++;
                            columns[dz * 16 + dx] = nativeBlocks[offset + y];
                            heights[dz * 16 + dx] = y;
                        }
                    chunks.put(key, columns);
                    tops.put(key, heights);
                    biomes.put(
                            key,
                            ((BiomeGenBase[])
                                            field(nativeGenerator.getClass(), "baseBiomesList")
                                                    .get(nativeGenerator))
                                    .clone());
                }
                int index = (z & 15) * 16 + (x & 15), y = tops.get(key)[index];
                Block block = chunks.get(key)[index];
                BiomeGenBase biome = biomes.get(key)[(z & 15) * 16 + (x & 15)];
                Object sampled = surface.invoke(sampler, x, z);
                assertSame(block, field(sampled.getClass(), "block").get(sampled));
                assertSame(biome, field(sampled.getClass(), "biome").get(sampled));
                assertEquals(y, field(sampled.getClass(), "y").getInt(sampled));
                boolean eligible =
                        provider.equals(SpawnSearch.VANILLA)
                                ? block == grass
                                : block == sand
                                        || block == stone
                                        || block == snow
                                                && ((net.minecraft.world.biome.WorldChunkManager)
                                                                manager)
                                                        .getBiomesToSpawnIn()
                                                        .contains(biome);
                if (eligible) break;
                x += random.nextInt(64) - random.nextInt(64);
                z += random.nextInt(64) - random.nextInt(64);
                moves++;
            }
            Object search = ctor.newInstance(seed, provider, proxy);
            assertTrue((Boolean) advance.invoke(search, Long.MAX_VALUE));
            assertEquals(x, field(jobClass, "x").getInt(search));
            assertEquals(z, field(jobClass, "z").getInt(search));
            assertEquals(moves, field(jobClass, "moves").getInt(search));
            System.out.println(
                    "RWG2 spawn reference: seed="
                            + seed
                            + " provider="
                            + provider
                            + " at="
                            + x
                            + ","
                            + z
                            + " moves="
                            + moves);
        }
    }

    private static void benchmarkJunctions(NativeLoader loader, Class<?> samplerClass)
            throws Exception {
        Constructor<?> ctor = samplerClass.getDeclaredConstructor(long.class, boolean.class);
        ctor.setAccessible(true);
        Class<?> terrainType =
                loader.loadClass("amidst.gtnh.worker.Rwg2StructurePredictor$NativeTerrain");
        Constructor<?> terrainCtor = terrainType.getDeclaredConstructor(samplerClass);
        terrainCtor.setAccessible(true);
        Method river = terrainType.getDeclaredMethod("riverCenter", int.class, int.class);
        river.setAccessible(true);
        List<Object> rows = new ArrayList<>();
        for (boolean simplex : new boolean[] {false, true}) {
            Object probeSampler = ctor.newInstance(9182741L, simplex);
            Object probeTerrain = terrainCtor.newInstance(probeSampler);
            Object strength = field(terrainType, "strength").get(probeTerrain);
            Method at = strength.getClass().getDeclaredMethod("at", int.class, int.class);
            at.setAccessible(true);
            Object probeManager = field(samplerClass, "manager").get(probeSampler);
            Method nativeStrength =
                    probeManager
                            .getClass()
                            .getMethod("getRiverJunctionStrength", int.class, int.class);
            Random probes = new Random(0x51C0FFEE);
            for (int i = 0; i < 30000; i++) {
                int x = i < 10000 ? i - 5000 : probes.nextInt(60000001) - 30000000;
                int z = i < 10000 ? (i % 65 - 32) * 1250 : probes.nextInt(60000001) - 30000000;
                assertEquals(
                        "Exact river strength at " + x + "," + z,
                        Float.floatToIntBits((Float) nativeStrength.invoke(probeManager, x, z)),
                        Float.floatToIntBits((Float) at.invoke(strength, x, z)));
            }
            int ox = simplex ? -7744 : -7136, oz = simplex ? 2688 : 4960;
            double[] nativeTimes = new double[5], cachedTimes = new double[5];
            long hits = 0, misses = 0;
            int centers = 0;
            for (int round = -1; round < 5; round++) {
                Object nativeSampler = ctor.newInstance(9182741L, simplex);
                Object manager = field(samplerClass, "manager").get(nativeSampler);
                Object cached = terrainCtor.newInstance(ctor.newInstance(9182741L, simplex));
                Method original =
                        loader.loadClass("rwg2.support.LandmarkDecorations")
                                .getDeclaredMethod(
                                        "findJunctionCenter",
                                        manager.getClass(),
                                        int.class,
                                        int.class);
                original.setAccessible(true);
                int[][] expected = new int[1024][], actual = new int[1024][];
                long n = 0, c = 0;
                for (int pass = 0; pass < 2; pass++) {
                    boolean nativePass = (pass == 0) == ((round & 1) == 0);
                    long started = System.nanoTime();
                    int index = 0;
                    for (int x = ox - 192; x < ox + 320; x += 16)
                        for (int z = oz - 192; z < oz + 320; z += 16) {
                            if (nativePass)
                                expected[index++] = (int[]) original.invoke(null, manager, x, z);
                            else actual[index++] = (int[]) river.invoke(cached, x, z);
                        }
                    if (nativePass) n = System.nanoTime() - started;
                    else c = System.nanoTime() - started;
                }
                centers = 0;
                for (int i = 0; i < expected.length; i++) {
                    assertArrayEquals(
                            "River chamber " + i + " mode=" + simplex, expected[i], actual[i]);
                    if (expected[i] != null) centers++;
                }
                Object junction = field(terrainType, "strength").get(cached);
                hits = field(junction.getClass(), "warpHits").getLong(junction);
                misses = field(junction.getClass(), "warpMisses").getLong(junction);
                if (round >= 0) {
                    nativeTimes[round] = n / 1e6;
                    cachedTimes[round] = c / 1e6;
                }
            }
            Arrays.sort(nativeTimes);
            Arrays.sort(cachedTimes);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("noise", simplex ? "OpenSimplex" : "Perlin");
            row.put("chunks", 1024);
            row.put("candidateChunks", centers);
            row.put("nativeMedianMillis", nativeTimes[2]);
            row.put("cachedMedianMillis", cachedTimes[2]);
            row.put("reusedAxisNoise", hits);
            row.put("evaluatedAxisNoise", misses);
            rows.add(row);
        }
        Path out = Paths.get("../build/rwg2-followup-audit/v410-junctions.json");
        Files.createDirectories(out.getParent());
        Files.write(
                out,
                new com.google.gson.GsonBuilder()
                        .setPrettyPrinting()
                        .create()
                        .toJson(rows)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        System.out.println("RWG2 junction reference: " + rows);
    }

    private static final class NativeLoader extends ClassLoader implements AutoCloseable {
        final JarFile jar;
        final Map<String, String> names = new HashMap<>();

        NativeLoader(String path, String mappings) throws Exception {
            super(InstalledRwg2ReferenceTest.class.getClassLoader());
            jar = new JarFile(path);
            for (String file : new String[] {"fields.csv", "methods.csv"})
                for (String line : Files.readAllLines(Paths.get(mappings, file))) {
                    String[] parts = line.split(",");
                    if (parts.length >= 2) names.put(parts[0], parts[1]);
                }
        }

        byte[] raw(String resource) throws IOException {
            JarEntry entry = jar.getJarEntry(resource);
            try (InputStream in =
                    entry == null
                            ? getParent().getResourceAsStream(resource)
                            : jar.getInputStream(entry)) {
                if (in == null) throw new FileNotFoundException(resource);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) out.write(b, 0, n);
                return out.toByteArray();
            }
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            if (jar.getJarEntry(name) != null)
                try {
                    return new ByteArrayInputStream(raw(name));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            return super.getResourceAsStream(name);
        }

        Class<?> defineMapped(byte[] code) {
            ClassWriter out = new ClassWriter(0);
            new ClassReader(code)
                    .accept(
                            new RemappingClassAdapter(
                                    out,
                                    new Remapper() {
                                        @Override
                                        public String mapFieldName(
                                                String owner, String name, String descriptor) {
                                            return names.getOrDefault(name, name);
                                        }

                                        @Override
                                        public String mapMethodName(
                                                String owner, String name, String descriptor) {
                                            return names.getOrDefault(name, name);
                                        }
                                    }),
                            ClassReader.EXPAND_FRAMES);
            byte[] mapped = out.toByteArray();
            return defineClass(null, mapped, 0, mapped.length);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (name.startsWith(InstalledRwg2ReferenceTest.class.getName()))
                return super.loadClass(name, resolve);
            if (!name.equals("net.minecraft.init.Blocks")
                    && !name.startsWith("rwg2.")
                    && !name.startsWith("amidst.gtnh.worker."))
                return super.loadClass(name, resolve);
            Class<?> found = findLoadedClass(name);
            if (found != null) return found;
            try {
                byte[] code = raw(name.replace('.', '/') + ".class");
                if (name.equals("net.minecraft.init.Blocks")) {
                    ClassNode node = new ClassNode();
                    new ClassReader(code).accept(node, 0);
                    node.methods.removeIf(m -> m.name.equals("<clinit>"));
                    MethodNode init =
                            new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                    for (FieldNode f : node.fields)
                        if ((f.access & Opcodes.ACC_STATIC) != 0
                                && f.desc.startsWith("Lnet/minecraft/block/")) {
                            init.instructions.add(new LdcInsnNode(Type.getType(f.desc)));
                            init.instructions.add(new LdcInsnNode(f.name));
                            init.instructions.add(
                                    new MethodInsnNode(
                                            Opcodes.INVOKESTATIC,
                                            Type.getInternalName(InstalledRwg2ReferenceTest.class),
                                            "referenceBlock",
                                            "(Ljava/lang/Class;Ljava/lang/String;)Lnet/minecraft/block/Block;",
                                            false));
                            init.instructions.add(
                                    new TypeInsnNode(
                                            Opcodes.CHECKCAST,
                                            Type.getType(f.desc).getInternalName()));
                            init.instructions.add(
                                    new FieldInsnNode(
                                            Opcodes.PUTSTATIC, node.name, f.name, f.desc));
                        }
                    init.instructions.add(new InsnNode(Opcodes.RETURN));
                    node.methods.add(init);
                    ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    node.accept(out);
                    code = out.toByteArray();
                }
                if (name.equals("rwg2.world.ChunkGeneratorRealistic"))
                    code = referenceWithoutChunks(code);
                if (name.equals("rwg2.support.LandmarkDecorations")) {
                    // The unit JVM lacks optional Natura/LootGames classes. Preserve the
                    // original pure sampling methods verbatim; remove block-placement methods
                    // whose verification would otherwise load those unrelated dependencies.
                    ClassNode node = new ClassNode();
                    new ClassReader(code).accept(node, 0);
                    node.methods.removeIf(
                            m ->
                                    !Arrays.asList(
                                                    "findJunctionCenter",
                                                    "hasMountainChainNearby",
                                                    "mix")
                                            .contains(m.name));
                    ClassWriter out = new ClassWriter(0);
                    node.accept(out);
                    code = out.toByteArray();
                }
                Class<?> defined =
                        name.startsWith("rwg2.")
                                ? defineMapped(code)
                                : defineClass(name, code, 0, code.length);
                if (resolve) resolveClass(defined);
                return defined;
            } catch (IOException e) {
                throw new ClassNotFoundException(name, e);
            }
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }

    /**
     * Keep the original provideChunk ordering through its LAST biome write. Drop only unrelated
     * caves/structures and the final allocation of a Minecraft Chunk.
     */
    private static byte[] referenceWithoutChunks(byte[] code) {
        ClassNode node = new ClassNode();
        new ClassReader(code).accept(node, 0);
        for (MethodNode m : node.methods)
            if (m.name.equals("func_73154_d") || m.name.equals("provideChunk")) {
                boolean tail = false;
                for (AbstractInsnNode instruction : m.instructions.toArray()) {
                    if (instruction instanceof TypeInsnNode
                            && instruction.getOpcode() == Opcodes.NEW
                            && ((TypeInsnNode) instruction)
                                    .desc.equals("net/minecraft/world/chunk/Chunk")) tail = true;
                    if (tail) {
                        m.instructions.remove(instruction);
                        continue;
                    }
                    if (instruction instanceof MethodInsnNode) {
                        MethodInsnNode call = (MethodInsnNode) instruction;
                        if (call.name.equals("func_151539_a")) {
                            InsnList discard = new InsnList();
                            Type[] arguments = Type.getArgumentTypes(call.desc);
                            for (int i = arguments.length - 1; i >= 0; i--)
                                discard.add(
                                        new InsnNode(
                                                arguments[i].getSize() == 2
                                                        ? Opcodes.POP2
                                                        : Opcodes.POP));
                            discard.add(new InsnNode(Opcodes.POP));
                            m.instructions.insertBefore(call, discard);
                            m.instructions.remove(call);
                        }
                    }
                }
                assertTrue(tail);
                m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
                m.instructions.add(new InsnNode(Opcodes.ARETURN));
                m.localVariables.clear();
            }
        ClassWriter out = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(out);
        return out.toByteArray();
    }
}
