package amidst.gtnh.worker.mod;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.world.WorldServer;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraftforge.common.BiomeDictionary;
import net.minecraftforge.common.DimensionManager;

import com.google.gson.Gson;

final class BiomeWorkerServer implements Closeable {

    private static final int PROTOCOL_VERSION = 13;
    private static final int NETHER = -1;
    private static final int OVERWORLD = 0;
    private static final int END = 1;
    private static final int DUNGEON_STRUCTURE_MARGIN = 9 * 16;
    private static final int MAX_SAMPLES = 65536;
    private static final int MAX_CACHED_SEEDS = 4;
    private static final int SOCKET_TIMEOUT_MILLIS = 35000;

    private final int port;
    private final String token;
    private final Gson gson = new Gson();
    private final BlockingQueue<FutureTask<Response>> serverThreadQueries = new LinkedBlockingQueue<FutureTask<Response>>();
    private final ExecutorService clientExecutor = Executors.newFixedThreadPool(4, daemonFactory("GTNH biome client"));

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private WorldServer sampledWorld;
    private WorldChunkManager sampledManager;
    private SurfaceBiomeSampler surfaceBiomeSampler;
    private WorldServer sampledNetherWorld;
    private WorldChunkManager sampledNetherManager;
    private NetherBiomeSampler netherBiomeSampler;
    private RoguelikeDungeonPredictor roguelikeDungeonPredictor;
    private RwgStructurePredictor rwgStructurePredictor;
    private ModStructurePredictor modStructurePredictor;
    private ThaumcraftStructurePredictor thaumcraftStructurePredictor;
    private VanillaDungeonPredictor vanillaDungeonPredictor;
    private EndStructurePredictor endStructurePredictor;
    private TwilightForestFeaturePredictor twilightForestFeaturePredictor;
    private MoonStructurePredictor moonStructurePredictor;
    private SpaceDimensionSampler spaceDimensionSampler;
    private SpaceStructurePredictor spaceStructurePredictor;
    private OreVeinPredictor oreVeinPredictor;
    private final Map<Long, SurfaceBiomeSampler> arbitrarySeedSamplers =
            new LinkedHashMap<Long, SurfaceBiomeSampler>(8, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, SurfaceBiomeSampler> eldest) {
                    return size() > MAX_CACHED_SEEDS;
                }
            };
    private final Map<Long, NetherBiomeSampler> arbitrarySeedNetherSamplers =
            new LinkedHashMap<Long, NetherBiomeSampler>(8, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, NetherBiomeSampler> eldest) {
                    return size() > MAX_CACHED_SEEDS;
                }
            };
    private final Map<Long, TwilightForestBiomeSampler> arbitrarySeedTwilightSamplers =
            new LinkedHashMap<Long, TwilightForestBiomeSampler>(8, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, TwilightForestBiomeSampler> eldest) {
                    return size() > MAX_CACHED_SEEDS;
                }
            };

    BiomeWorkerServer(int port, String token) {
        this.port = port;
        this.token = token;
    }

    void start() {
        try {
            serverSocket = new ServerSocket(port, 16, InetAddress.getLoopbackAddress());
            running = true;
            acceptThread = daemonFactory("GTNH biome acceptor").newThread(new Runnable() {

                @Override
                public void run() {
                    acceptConnections();
                }
            });
            acceptThread.start();
            AmidstGtnhWorkerLog.LOG.info(
                    "GTNH biome worker listening on 127.0.0.1:{} (token {})",
                    Integer.valueOf(port),
                    token.isEmpty() ? "not configured" : "configured");
        } catch (IOException e) {
            throw new IllegalStateException("unable to start GTNH biome worker on 127.0.0.1:" + port, e);
        }
    }

    synchronized void executeQueuedQueries() {
        FutureTask<Response> query;
        while ((query = serverThreadQueries.poll()) != null) {
            query.run();
        }
    }

    private void acceptConnections() {
        while (running) {
            try {
                final Socket socket = serverSocket.accept();
                clientExecutor.execute(new Runnable() {

                    @Override
                    public void run() {
                        handleClient(socket);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    AmidstGtnhWorkerLog.LOG.error("GTNH biome worker accept failed", e);
                }
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                Response response;
                if (line == null) {
                    response = Response.error("request was empty");
                } else {
                    Request request = gson.fromJson(line, Request.class);
                    response = submit(request);
                }
                writer.write(gson.toJson(response));
                writer.newLine();
                writer.flush();
            } finally {
                socket.close();
            }
        } catch (Exception e) {
            AmidstGtnhWorkerLog.LOG.warn("GTNH biome worker client request failed", e);
            try {
                socket.close();
            } catch (IOException ignored) {}
        }
    }

    private Response submit(final Request request) {
        if (request == null) {
            return Response.error("request JSON was invalid");
        }
        if (request.protocol != PROTOCOL_VERSION) {
            return Response.error("unsupported protocol " + request.protocol);
        }
        if (!token.equals(request.token == null ? "" : request.token)) {
            return Response.error("authentication failed");
        }
        FutureTask<Response> task = new FutureTask<Response>(new Callable<Response>() {

            @Override
            public Response call() {
                return executeOnServerThread(request);
            }
        });
        serverThreadQueries.add(task);
        try {
            return task.get(SOCKET_TIMEOUT_MILLIS - 5000L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Response.error("request was interrupted");
        } catch (ExecutionException e) {
            AmidstGtnhWorkerLog.LOG.error("GTNH biome query failed", e.getCause());
            return Response.error(safeMessage(e.getCause()));
        } catch (TimeoutException e) {
            task.cancel(false);
            return Response.error("server thread did not process the query in time");
        }
    }

    private Response executeOnServerThread(Request request) {
        try {
            if ("hello".equals(request.command)) {
                return createHelloResponse();
            }
            if ("biomes".equals(request.command)) {
                return sampleBiomes(request);
            }
            if ("spawn".equals(request.command)) {
                return sampleSpawn(request);
            }
            if ("structures".equals(request.command)) {
                return sampleStructures(request);
            }
            return Response.error("unknown command " + request.command);
        } catch (RuntimeException e) {
            AmidstGtnhWorkerLog.LOG.error("GTNH biome query failed", e);
            return Response.error(safeMessage(e));
        }
    }

    private Response createHelloResponse() {
        WorldServer world = DimensionManager.getWorld(0);
        Response response = Response.ok();
        if (world != null && SurfaceBiomeSamplers.isRwg(world.getWorldChunkManager())) {
            response.seed = world.getSeed();
            response.worldType = world.getWorldInfo().getTerrainType().getWorldTypeName();
            response.providerClass = world.provider.getClass().getName();
            response.chunkManagerClass = world.getWorldChunkManager().getClass().getName();
            getSurfaceBiomeSampler(world);
        } else {
            getArbitrarySeedSampler(0L);
            response.seed = 0L;
            response.worldType = "RWG";
            response.providerClass = SurfaceBiomeSamplers.OVERWORLD_PROVIDER;
            response.chunkManagerClass = SurfaceBiomeSamplers.RWG_CHUNK_MANAGER;
        }
        response.biomes = getBiomeDescriptors();
        response.twilightForestDimensionId = TwilightForestBiomeSampler.configuredDimensionId();
        response.moonDimensionId = MoonBiomeSampler.configuredDimensionId();
        response.marsDimensionId = SpaceDimensionSampler.marsDimensionId();
        response.asteroidsDimensionId = SpaceDimensionSampler.asteroidsDimensionId();
        response.ceresDimensionId = SpaceDimensionSampler.ceresDimensionId();
        response.ioDimensionId = SpaceDimensionSampler.ioDimensionId();
        response.enceladusDimensionId = SpaceDimensionSampler.enceladusDimensionId();
        response.proteusDimensionId = SpaceDimensionSampler.proteusDimensionId();
        response.plutoDimensionId = SpaceDimensionSampler.plutoDimensionId();
        response.mehenBeltDimensionId = SpaceDimensionSampler.mehenBeltDimensionId();
        response.ross128bDimensionId = SpaceDimensionSampler.ross128bDimensionId();
        return response;
    }

    private Response sampleBiomes(Request request) {
        validateRequest(request);
        if (request.dimension == NETHER) {
            return sampleNetherBiomes(request);
        }
        if (request.dimension == END) {
            return sampleEndBiomes(request);
        }
        if (request.dimension == MoonBiomeSampler.configuredDimensionId()) {
            Response response = Response.ok();
            response.ids = MoonBiomeSampler.sample(request.width, request.height);
            return response;
        }
        if (request.dimension == TwilightForestBiomeSampler.configuredDimensionId()) {
            return sampleTwilightForestBiomes(request);
        }
        if (SpaceDimensionSampler.supports(request.dimension)) {
            if (spaceDimensionSampler == null) {
                spaceDimensionSampler = new SpaceDimensionSampler();
            }
            Response response = Response.ok();
            response.ids = spaceDimensionSampler.sample(
                    request.seed,
                    request.dimension,
                    request.x,
                    request.z,
                    request.width,
                    request.height,
                    request.step);
            return response;
        }
        return sampleOverworldBiomes(request);
    }

    private Response sampleTwilightForestBiomes(Request request) {
        TwilightForestBiomeSampler sampler = getArbitrarySeedTwilightSampler(request.seed);
        int[] ids;
        if (request.step == 4) {
            if ((request.x & 3) != 0 || (request.z & 3) != 0) {
                throw new IllegalArgumentException(
                        "Twilight Forest quarter-resolution coordinates must align to four blocks");
            }
            ids = sampler.getQuarterResolutionBiomeIds(
                    request.x >> 2,
                    request.z >> 2,
                    request.width,
                    request.height);
        } else if (request.step == 1) {
            ids = new int[request.width * request.height];
            for (int row = 0; row < request.height; row++) {
                for (int column = 0; column < request.width; column++) {
                    BiomeGenBase biome =
                            sampler.getBiomeAt(request.x + column, request.z + row);
                    if (biome == null) {
                        throw new IllegalStateException(
                                "Twilight Forest sampler returned no biome at "
                                        + (request.x + column)
                                        + ","
                                        + (request.z + row));
                    }
                    ids[row * request.width + column] = biome.biomeID;
                }
            }
        } else {
            throw new IllegalArgumentException(
                    "Twilight Forest biome step must be 1 or 4");
        }
        Response response = Response.ok();
        response.ids = ids;
        return response;
    }

    private Response sampleEndBiomes(Request request) {
        if (endStructurePredictor == null) {
            endStructurePredictor = new EndStructurePredictor();
        }
        Response response = Response.ok();
        response.ids = endStructurePredictor.sampleBiomes(
                request.seed,
                request.x,
                request.z,
                request.width,
                request.height,
                request.step);
        return response;
    }

    private Response sampleOverworldBiomes(Request request) {
        WorldServer world = DimensionManager.getWorld(request.dimension);
        SurfaceBiomeSampler sampler =
                world != null
                                && world.getSeed() == request.seed
                                && SurfaceBiomeSamplers.isRwg(world.getWorldChunkManager())
                        ? getSurfaceBiomeSampler(world)
                        : getArbitrarySeedSampler(request.seed);
        int[] ids = new int[request.width * request.height];
        for (int row = 0; row < request.height; row++) {
            int sampleZ = request.z + row * request.step;
            for (int column = 0; column < request.width; column++) {
                int sampleX = request.x + column * request.step;
                BiomeGenBase biome = sampleDisplayedBiome(
                        sampler,
                        sampleX,
                        sampleZ,
                        request.step);
                if (biome == null) {
                    throw new IllegalStateException("surface sampler returned no biome at " + sampleX + "," + sampleZ);
                }
                ids[row * request.width + column] = biome.biomeID;
            }
        }
        Response response = Response.ok();
        response.ids = ids;
        return response;
    }

    private Response sampleNetherBiomes(Request request) {
        WorldServer world = DimensionManager.getWorld(NETHER);
        NetherBiomeSampler sampler =
                world != null && world.getSeed() == request.seed
                        ? getNetherBiomeSampler(world)
                        : getArbitrarySeedNetherSampler(request.seed);
        int[] ids = new int[request.width * request.height];
        for (int row = 0; row < request.height; row++) {
            int sampleZ = request.z + row * request.step;
            for (int column = 0; column < request.width; column++) {
                int sampleX = request.x + column * request.step;
                BiomeGenBase biome = sampler.getBiomeAt(sampleX, sampleZ);
                if (biome == null) {
                    throw new IllegalStateException(
                            "Nether sampler returned no biome at " + sampleX + "," + sampleZ);
                }
                ids[row * request.width + column] = biome.biomeID;
            }
        }
        Response response = Response.ok();
        response.ids = ids;
        return response;
    }

    /**
     * AMIDST draws the overworld at one map pixel per four blocks. Sampling only
     * the north-west block loses narrow RWG rivers and makes biome borders shift
     * by up to three blocks. Keep the same compact response size, but vote among
     * four samples inside each 4x4 display cell.
     */
    private BiomeGenBase sampleDisplayedBiome(
            SurfaceBiomeSampler sampler,
            int x,
            int z,
            int step) {
        if (step != 4) {
            return sampler.getBiomeAt(x, z);
        }
        BiomeGenBase northWest = sampler.getBiomeAt(x, z);
        BiomeGenBase northEast = sampler.getBiomeAt(x + 2, z);
        BiomeGenBase southWest = sampler.getBiomeAt(x, z + 2);
        BiomeGenBase southEast = sampler.getBiomeAt(x + 2, z + 2);
        if (northWest == null || northEast == null || southWest == null || southEast == null) {
            return null;
        }
        if (northWest.biomeID == northEast.biomeID
                || northWest.biomeID == southWest.biomeID
                || northWest.biomeID == southEast.biomeID) {
            return northWest;
        }
        if (northEast.biomeID == southWest.biomeID
                || northEast.biomeID == southEast.biomeID) {
            return northEast;
        }
        if (southWest.biomeID == southEast.biomeID) {
            return southWest;
        }
        return southEast;
    }

    private Response sampleSpawn(Request request) {
        if (request.dimension != 0) {
            throw new IllegalArgumentException("spawn prediction supports only overworld dimension 0");
        }
        SurfaceBiomeSampler sampler = getSampler(request.seed, request.dimension);
        Random random = new Random(request.seed);
        ChunkPosition biomePosition = sampler.findSpawnBiomePosition(random);
        int x = biomePosition == null ? 0 : biomePosition.chunkPosX;
        int z = biomePosition == null ? 0 : biomePosition.chunkPosZ;
        int attempts = 0;
        while (!sampler.isLikelySpawnCoordinate(x, z) && attempts < 1000) {
            x += random.nextInt(64) - random.nextInt(64);
            z += random.nextInt(64) - random.nextInt(64);
            attempts++;
        }
        Response response = Response.ok();
        response.spawnX = x;
        response.spawnZ = z;
        return response;
    }

    private Response sampleStructures(Request request) {
        validateStructureRequest(request);
        if (request.dimension == MoonBiomeSampler.configuredDimensionId()) {
            if (request.vanillaDungeonsOnly) {
                throw new IllegalArgumentException(
                        "vanilla spawner dungeon prediction supports only overworld dimension 0");
            }
            if (moonStructurePredictor == null) {
                moonStructurePredictor = new MoonStructurePredictor();
            }
            Response response = Response.ok();
            response.structures = moonStructurePredictor.predict(
                    request.seed,
                    request.dimension,
                    request.x,
                    request.z,
                    request.width,
                    request.height,
                    MoonBiomeSampler.isVillageGenerationEnabled());
            return response;
        }
        if (request.dimension == TwilightForestBiomeSampler.configuredDimensionId()) {
            if (request.vanillaDungeonsOnly) {
                throw new IllegalArgumentException(
                        "vanilla spawner dungeon prediction supports only overworld dimension 0");
            }
            if (twilightForestFeaturePredictor == null) {
                twilightForestFeaturePredictor = new TwilightForestFeaturePredictor();
            }
            Response response = Response.ok();
            response.structures = twilightForestFeaturePredictor.predict(
                    request.seed,
                    request.x,
                    request.z,
                    request.width,
                    request.height,
                    getArbitrarySeedTwilightSampler(request.seed));
            return response;
        }
        if (SpaceDimensionSampler.supports(request.dimension)) {
            if (request.vanillaDungeonsOnly) {
                throw new IllegalArgumentException(
                        "vanilla spawner dungeon prediction supports only overworld dimension 0");
            }
            if (spaceStructurePredictor == null) {
                spaceStructurePredictor = new SpaceStructurePredictor();
            }
            Response response = Response.ok();
            response.structures = spaceStructurePredictor.predict(
                    request.seed,
                    request.dimension,
                    request.x,
                    request.z,
                    request.width,
                    request.height);
            if (request.dimension == SpaceDimensionSampler.ross128bDimensionId()) {
                if (oreVeinPredictor == null) {
                    oreVeinPredictor = new OreVeinPredictor();
                }
                response.structures.addAll(oreVeinPredictor.predict(
                        request.seed,
                        request.dimension,
                        request.x,
                        request.z,
                        request.width,
                        request.height));
            }
            return response;
        }
        if (request.dimension == END) {
            if (request.vanillaDungeonsOnly) {
                throw new IllegalArgumentException(
                        "vanilla spawner dungeon prediction supports only overworld dimension 0");
            }
            if (endStructurePredictor == null) {
                endStructurePredictor = new EndStructurePredictor();
            }
            Response response = Response.ok();
            response.structures = endStructurePredictor.predict(
                    request.seed,
                    request.x,
                    request.z,
                    request.width,
                    request.height);
            return response;
        }
        if (request.dimension == NETHER) {
            if (request.vanillaDungeonsOnly) {
                throw new IllegalArgumentException(
                        "vanilla spawner dungeon prediction supports only overworld dimension 0");
            }
            if (modStructurePredictor == null) {
                modStructurePredictor = new ModStructurePredictor();
            }
            Response response = Response.ok();
            response.structures = modStructurePredictor.predict(
                    request.seed,
                    request.dimension,
                    request.x,
                    request.z,
                    request.width,
                    request.height);
            if (oreVeinPredictor == null) {
                oreVeinPredictor = new OreVeinPredictor();
            }
            response.structures.addAll(oreVeinPredictor.predict(
                    request.seed,
                    request.dimension,
                    request.x,
                    request.z,
                    request.width,
                    request.height));
            return response;
        }
        SurfaceBiomeSampler sampler = getSampler(request.seed, request.dimension);
        if (rwgStructurePredictor == null) {
            rwgStructurePredictor = new RwgStructurePredictor();
        }
        Response response = Response.ok();
        if (request.vanillaDungeonsOnly) {
            if (vanillaDungeonPredictor == null) {
                vanillaDungeonPredictor = new VanillaDungeonPredictor();
            }
            List<RoguelikeDungeonPredictor.StructureDescriptor> nearbyRwgStructures =
                    rwgStructurePredictor.predict(
                            request.seed,
                            request.x - DUNGEON_STRUCTURE_MARGIN,
                            request.z - DUNGEON_STRUCTURE_MARGIN,
                            request.width + DUNGEON_STRUCTURE_MARGIN * 2,
                            request.height + DUNGEON_STRUCTURE_MARGIN * 2,
                            sampler);
            response.structures = vanillaDungeonPredictor.predict(
                    request.seed,
                    request.x,
                    request.z,
                    request.width,
                    request.height,
                    sampler,
                    nearbyRwgStructures);
            return response;
        }
        List<RoguelikeDungeonPredictor.StructureDescriptor> rwgStructures =
                rwgStructurePredictor.predict(
                        request.seed,
                        request.x,
                        request.z,
                        request.width,
                        request.height,
                        sampler);
        if (roguelikeDungeonPredictor == null) {
            roguelikeDungeonPredictor = new RoguelikeDungeonPredictor();
        }
        if (modStructurePredictor == null) {
            modStructurePredictor = new ModStructurePredictor();
        }
        if (thaumcraftStructurePredictor == null) {
            thaumcraftStructurePredictor = new ThaumcraftStructurePredictor();
        }
        response.structures = rwgStructures;
        response.structures.addAll(roguelikeDungeonPredictor.predict(
                request.seed,
                request.dimension,
                request.x,
                request.z,
                request.width,
                request.height,
                sampler));
        response.structures.addAll(modStructurePredictor.predict(
                request.seed,
                request.dimension,
                request.x,
                request.z,
                request.width,
                request.height));
        response.structures.addAll(thaumcraftStructurePredictor.predict(
                request.seed,
                request.dimension,
                request.x,
                request.z,
                request.width,
                request.height,
                sampler));
        return response;
    }

    private SurfaceBiomeSampler getSampler(long seed, int dimension) {
        WorldServer world = DimensionManager.getWorld(dimension);
        return world != null
                        && world.getSeed() == seed
                        && SurfaceBiomeSamplers.isRwg(world.getWorldChunkManager())
                ? getSurfaceBiomeSampler(world)
                : getArbitrarySeedSampler(seed);
    }

    private SurfaceBiomeSampler getSurfaceBiomeSampler(WorldServer world) {
        WorldChunkManager manager = world.getWorldChunkManager();
        if (surfaceBiomeSampler == null || sampledWorld != world || sampledManager != manager) {
            sampledWorld = world;
            sampledManager = manager;
            surfaceBiomeSampler = SurfaceBiomeSamplers.create(world);
        }
        return surfaceBiomeSampler;
    }

    private SurfaceBiomeSampler getArbitrarySeedSampler(long seed) {
        Long key = Long.valueOf(seed);
        SurfaceBiomeSampler sampler = arbitrarySeedSamplers.get(key);
        if (sampler == null) {
            sampler = SurfaceBiomeSamplers.createRwg(seed);
            arbitrarySeedSamplers.put(key, sampler);
            AmidstGtnhWorkerLog.LOG.info("Prepared RWG biome preview context for seed {}", key);
        }
        return sampler;
    }

    private NetherBiomeSampler getNetherBiomeSampler(WorldServer world) {
        WorldChunkManager manager = world.getWorldChunkManager();
        if (netherBiomeSampler == null
                || sampledNetherWorld != world
                || sampledNetherManager != manager) {
            sampledNetherWorld = world;
            sampledNetherManager = manager;
            netherBiomeSampler = NetherBiomeSamplers.create(world);
            AmidstGtnhWorkerLog.LOG.info(
                    "GTNH biome worker is using Nether provider {} with biome manager {}",
                    world.provider.getClass().getName(),
                    manager.getClass().getName());
        }
        return netherBiomeSampler;
    }

    private NetherBiomeSampler getArbitrarySeedNetherSampler(long seed) {
        Long key = Long.valueOf(seed);
        NetherBiomeSampler sampler = arbitrarySeedNetherSamplers.get(key);
        if (sampler == null) {
            sampler = NetherBiomeSamplers.create(seed);
            arbitrarySeedNetherSamplers.put(key, sampler);
            AmidstGtnhWorkerLog.LOG.info("Prepared Nether biome preview context for seed {}", key);
        }
        return sampler;
    }

    private TwilightForestBiomeSampler getArbitrarySeedTwilightSampler(long seed) {
        Long key = Long.valueOf(seed);
        TwilightForestBiomeSampler sampler = arbitrarySeedTwilightSamplers.get(key);
        if (sampler == null) {
            sampler = new TwilightForestBiomeSampler(seed);
            arbitrarySeedTwilightSamplers.put(key, sampler);
            AmidstGtnhWorkerLog.LOG.info(
                    "Prepared Twilight Forest magic-map preview context for seed {}",
                    key);
        }
        return sampler;
    }

    private static void validateRequest(Request request) {
        if (request.dimension != OVERWORLD
                && request.dimension != NETHER
                && request.dimension != END
                && request.dimension != MoonBiomeSampler.configuredDimensionId()
                && request.dimension != TwilightForestBiomeSampler.configuredDimensionId()
                && !SpaceDimensionSampler.supports(request.dimension)) {
            throw new IllegalArgumentException(
                    "biome prediction does not support dimension " + request.dimension);
        }
        if (request.width < 1 || request.height < 1 || request.step < 1) {
            throw new IllegalArgumentException("width, height and step must be positive");
        }
        long samples = (long) request.width * (long) request.height;
        if (samples > MAX_SAMPLES) {
            throw new IllegalArgumentException("query exceeds the " + MAX_SAMPLES + " sample limit");
        }
        long lastX = (long) request.x + (long) (request.width - 1) * request.step;
        long lastZ = (long) request.z + (long) (request.height - 1) * request.step;
        if (lastX < Integer.MIN_VALUE
                || lastX > Integer.MAX_VALUE
                || lastZ < Integer.MIN_VALUE
                || lastZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("query coordinates overflow the Minecraft integer coordinate range");
        }
    }

    private static void validateStructureRequest(Request request) {
        if (request.dimension != OVERWORLD
                && request.dimension != NETHER
                && request.dimension != END
                && request.dimension != MoonBiomeSampler.configuredDimensionId()
                && request.dimension != TwilightForestBiomeSampler.configuredDimensionId()
                && !SpaceDimensionSampler.supports(request.dimension)) {
            throw new IllegalArgumentException(
                    "structure prediction does not support dimension " + request.dimension);
        }
        if (request.width < 1 || request.height < 1) {
            throw new IllegalArgumentException("structure query width and height must be positive");
        }
        long lastX = (long) request.x + (long) request.width - 1L;
        long lastZ = (long) request.z + (long) request.height - 1L;
        if (lastX < Integer.MIN_VALUE
                || lastX > Integer.MAX_VALUE
                || lastZ < Integer.MIN_VALUE
                || lastZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("structure query coordinates overflow the Minecraft integer range");
        }
    }

    private static List<BiomeDescriptor> getBiomeDescriptors() {
        Map<Integer, BiomeDescriptor> byId = new TreeMap<Integer, BiomeDescriptor>();
        BiomeGenBase[] registry = BiomeGenBase.getBiomeGenArray();
        for (int registryIndex = 0; registryIndex < registry.length; registryIndex++) {
            BiomeGenBase biome = registry[registryIndex];
            if (biome != null) {
                BiomeDescriptor descriptor = new BiomeDescriptor(biome);
                BiomeDescriptor previous = byId.get(Integer.valueOf(descriptor.id));
                if (previous == null || registryIndex == descriptor.id) {
                    byId.put(Integer.valueOf(descriptor.id), descriptor);
                }
                if (previous != null && !previous.name.equals(descriptor.name)) {
                    AmidstGtnhWorkerLog.LOG.warn(
                            "Biome id {} is registered as both '{}' and '{}'; using the canonical registry entry",
                            Integer.valueOf(descriptor.id),
                            previous.name,
                            descriptor.name);
                }
            }
        }
        addSyntheticEndBiome(
                byId,
                EndStructurePredictor.END_VOID_BIOME,
                "GTNH End Void",
                0x17131F);
        addSyntheticEndBiome(
                byId,
                EndStructurePredictor.CENTRAL_END_BIOME,
                "GTNH Central End",
                0xC9C29B);
        addSyntheticEndBiome(
                byId,
                EndStructurePredictor.INFESTED_FOREST_BIOME,
                "HEE Infested Forest",
                0x526B48);
        addSyntheticEndBiome(
                byId,
                EndStructurePredictor.BURNING_MOUNTAINS_BIOME,
                "HEE Burning Mountains",
                0x9B4B32);
        addSyntheticEndBiome(
                byId,
                EndStructurePredictor.ENCHANTED_ISLAND_BIOME,
                "HEE Enchanted Island",
                0x6575A8);
        return new ArrayList<BiomeDescriptor>(byId.values());
    }

    private static void addSyntheticEndBiome(
            Map<Integer, BiomeDescriptor> byId,
            int id,
            String name,
            int color) {
        byId.put(
                Integer.valueOf(id),
                new BiomeDescriptor(id, name, color, 0.5F, 0.0F, 0.1F, 0.2F, "END"));
    }

    private static ThreadFactory daemonFactory(final String name) {
        return new ThreadFactory() {

            private int index;

            @Override
            public synchronized Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, name + " " + (++index));
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        clientExecutor.shutdownNow();
        serverThreadQueries.clear();
        arbitrarySeedSamplers.clear();
        arbitrarySeedNetherSamplers.clear();
        arbitrarySeedTwilightSamplers.clear();
        if (spaceDimensionSampler != null) {
            spaceDimensionSampler.close();
        }
        AmidstGtnhWorkerLog.LOG.info("GTNH biome worker stopped");
    }

    private static final class Request {

        private int protocol;
        private String command;
        private String token;
        private long seed;
        private int dimension;
        private int x;
        private int z;
        private int width;
        private int height;
        private int step;
        private boolean vanillaDungeonsOnly;
    }

    private static final class Response {

        private boolean ok;
        private String error;
        private int protocol;
        private long seed;
        private String worldType;
        private String providerClass;
        private String chunkManagerClass;
        private List<BiomeDescriptor> biomes;
        private int twilightForestDimensionId;
        private int moonDimensionId;
        private int marsDimensionId;
        private int asteroidsDimensionId;
        private int ceresDimensionId;
        private int ioDimensionId;
        private int enceladusDimensionId;
        private int proteusDimensionId;
        private int plutoDimensionId;
        private int mehenBeltDimensionId;
        private int ross128bDimensionId;
        private int[] ids;
        private List<RoguelikeDungeonPredictor.StructureDescriptor> structures;
        private int spawnX;
        private int spawnZ;

        private static Response ok() {
            Response response = new Response();
            response.ok = true;
            response.protocol = PROTOCOL_VERSION;
            return response;
        }

        private static Response error(String error) {
            Response response = new Response();
            response.ok = false;
            response.protocol = PROTOCOL_VERSION;
            response.error = error;
            return response;
        }
    }

    private static final class BiomeDescriptor {

        private final int id;
        private final String name;
        private final int mapColor;
        private final float temperature;
        private final float rainfall;
        private final float rootHeight;
        private final float heightVariation;
        private final List<String> tags;

        private BiomeDescriptor(BiomeGenBase biome) {
            id = biome.biomeID;
            boolean moon = biome.getClass().getName().startsWith(
                    "micdoodle8.mods.galacticraft.core.world.gen.BiomeGen")
                    && "moon".equalsIgnoreCase(biome.biomeName);
            name = moon ? "Moon" : biome.biomeName;
            mapColor = biome.color;
            temperature = biome.temperature;
            rainfall = biome.rainfall;
            rootHeight = biome.rootHeight;
            heightVariation = biome.heightVariation;
            tags = new ArrayList<String>();
            for (BiomeDictionary.Type type : BiomeDictionary.getTypesForBiome(biome)) {
                tags.add(type.name());
            }
            if (biome.getClass().getName().startsWith("twilightforest.biomes.")) {
                tags.add("TWILIGHT_FOREST");
            }
            if (moon) {
                tags.add("MOON");
            }
            String biomeClassName = biome.getClass().getName();
            if (biomeClassName.contains(".planets.mars.")) {
                tags.add("MARS");
            }
            if (biomeClassName.contains(".planets.asteroids.")) {
                tags.add("ASTEROIDS");
            }
            if (biomeClassName.startsWith("galaxyspace.")) {
                tags.add("GALAXYSPACE");
            }
        }

        private BiomeDescriptor(
                int id,
                String name,
                int mapColor,
                float temperature,
                float rainfall,
                float rootHeight,
                float heightVariation,
                String tag) {
            this.id = id;
            this.name = name;
            this.mapColor = mapColor;
            this.temperature = temperature;
            this.rainfall = rainfall;
            this.rootHeight = rootHeight;
            this.heightVariation = heightVariation;
            this.tags = new ArrayList<String>();
            this.tags.add(tag);
        }
    }
}
