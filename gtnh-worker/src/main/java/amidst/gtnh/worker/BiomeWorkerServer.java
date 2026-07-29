package amidst.gtnh.worker;

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
import java.util.ArrayDeque;
import java.util.Deque;
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

    private static final int PROTOCOL_VERSION = 18;
    private static final int NETHER = -1;
    private static final int OVERWORLD = 0;
    private static final int END = 1;
    private static final String NETHER_KEY = "minecraft:the_nether";
    private static final String OVERWORLD_KEY = "minecraft:overworld";
    private static final String END_KEY = "minecraft:the_end";
    private static final String MOON_KEY = "galacticraftcore:moon";
    private static final String TWILIGHT_FOREST_KEY = "twilightforest:twilight_forest";
    private static final int DUNGEON_STRUCTURE_MARGIN = 9 * 16;
    private static final int MAX_SAMPLES = 65536;
    private static final int MAX_CACHED_SEEDS = 4;
    private static final int MAX_CACHED_BIOME_TILES = 256;
    private static final int MAX_CHUNK_CHANGES = 4_096;
    private static final int SOCKET_TIMEOUT_MILLIS = 35000;

    private final int port;
    private final String token;
    private final Gson gson = new Gson();
    private final BlockingQueue<FutureTask<Response>> serverThreadQueries = new LinkedBlockingQueue<FutureTask<Response>>();
    private final ExecutorService clientExecutor = Executors.newFixedThreadPool(4, daemonFactory("GTNH biome client"));
    private final Deque<ChunkChange> overworldChunkChanges = new ArrayDeque<ChunkChange>();

    private volatile boolean running;
    private long overworldChunkRevision;
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
    private EndAsteroidPredictor endAsteroidPredictor;
    private TwilightForestFeaturePredictor twilightForestFeaturePredictor;
    private MoonStructurePredictor moonStructurePredictor;
    private SpaceDimensionSampler spaceDimensionSampler;
    private SpaceStructurePredictor spaceStructurePredictor;
    private OreVeinPredictor oreVeinPredictor;
    private JourneyMapWaypointImporter journeyMapWaypointImporter;
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
    private final Map<BiomeTileKey, int[]> biomeTileCache =
            new LinkedHashMap<BiomeTileKey, int[]>(256, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(Map.Entry<BiomeTileKey, int[]> eldest) {
                    return size() > MAX_CACHED_BIOME_TILES;
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

    synchronized void recordOverworldChunkChange(int chunkX, int chunkZ) {
        long revision = ++overworldChunkRevision;
        overworldChunkChanges.addLast(new ChunkChange(revision, chunkX, chunkZ));
        while (overworldChunkChanges.size() > MAX_CHUNK_CHANGES) {
            overworldChunkChanges.removeFirst();
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
            if ("world_state".equals(request.command)) {
                return createWorldStateResponse(request);
            }
            if ("spawn".equals(request.command)) {
                return sampleSpawn(request);
            }
            if ("structures".equals(request.command)) {
                return sampleStructures(request);
            }
            if ("import_waypoints".equals(request.command)) {
                return importJourneyMapWaypoints(request);
            }
            return Response.error("unknown command " + request.command);
        } catch (RuntimeException e) {
            AmidstGtnhWorkerLog.LOG.error("GTNH biome query failed", e);
            return Response.error(safeMessage(e));
        }
    }

    private Response importJourneyMapWaypoints(Request request) {
        if (journeyMapWaypointImporter == null) {
            journeyMapWaypointImporter = new JourneyMapWaypointImporter();
        }
        Response response = Response.ok();
        response.importedWaypoints = journeyMapWaypointImporter.importWaypoints(
                request.dimension,
                request.waypoints);
        return response;
    }

    private synchronized Response createWorldStateResponse(Request request) {
        Response response = Response.ok();
        response.worldRevision = overworldChunkRevision;
        if (request.sinceRevision < 0L) {
            response.chunkXs = new int[0];
            response.chunkZs = new int[0];
            return response;
        }
        if (request.sinceRevision > overworldChunkRevision
                || (!overworldChunkChanges.isEmpty()
                        && request.sinceRevision
                                < overworldChunkChanges.getFirst().revision - 1L)) {
            response.fullRefresh = true;
            response.chunkXs = new int[0];
            response.chunkZs = new int[0];
            return response;
        }

        int count = 0;
        for (ChunkChange change : overworldChunkChanges) {
            if (change.revision > request.sinceRevision) {
                count++;
            }
        }
        response.chunkXs = new int[count];
        response.chunkZs = new int[count];
        int index = 0;
        for (ChunkChange change : overworldChunkChanges) {
            if (change.revision > request.sinceRevision) {
                response.chunkXs[index] = change.chunkX;
                response.chunkZs[index] = change.chunkZ;
                index++;
            }
        }
        return response;
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
        response.barnardaCDimensionId = SpaceDimensionSampler.barnardaCDimensionId();
        response.deepDarkDimensionId = SpaceDimensionSampler.deepDarkDimensionId();
        response.anubisDimensionId = SpaceDimensionSampler.anubisDimensionId();
        response.horusDimensionId = SpaceDimensionSampler.horusDimensionId();
        return response;
    }

    private Response sampleBiomes(Request request) {
        validateRequest(request);
        if (matches(request, NETHER_KEY, NETHER)) {
            return sampleNetherBiomes(request);
        }
        if (matches(request, END_KEY, END)) {
            return sampleEndBiomes(request);
        }
        if (matches(request, MOON_KEY, MoonBiomeSampler.configuredDimensionId())) {
            Response response = Response.ok();
            response.ids = MoonBiomeSampler.sample(request.width, request.height);
            remapBiomeIds(response.ids, request.dimensionKey);
            return response;
        }
        if (matches(
                request,
                TWILIGHT_FOREST_KEY,
                TwilightForestBiomeSampler.configuredDimensionId())) {
            return sampleTwilightForestBiomes(request);
        }
        if (SpaceDimensionSampler.supports(request.dimensionKey, request.dimension)) {
            if (spaceDimensionSampler == null) {
                spaceDimensionSampler = new SpaceDimensionSampler();
            }
            Response response = Response.ok();
            response.ids = spaceDimensionSampler.sample(
                    request.seed,
                    request.dimension,
                    request.dimensionKey,
                    request.x,
                    request.z,
                    request.width,
                    request.height,
                    request.step);
            return response;
        }
        return sampleOverworldBiomes(request);
    }

    private static void remapBiomeIds(int[] biomeIds, String dimensionKey) {
        for (int index = 0; index < biomeIds.length; index++) {
            biomeIds[index] =
                    SpaceDimensionSampler.displayBiomeId(
                            dimensionKey,
                            biomeIds[index]);
        }
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
        boolean useLiveWorld = world != null
                && world.getSeed() == request.seed
                && SurfaceBiomeSamplers.isRwg(world.getWorldChunkManager());
        BiomeTileKey cacheKey = useLiveWorld ? null : new BiomeTileKey(request);
        int[] cached = cacheKey == null ? null : biomeTileCache.get(cacheKey);
        if (cached != null) {
            Response response = Response.ok();
            response.ids = cached.clone();
            return response;
        }
        SurfaceBiomeSampler sampler =
                useLiveWorld
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
        if (cacheKey != null) {
            biomeTileCache.put(cacheKey, ids.clone());
        }
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
     * AMIDST draws the overworld at one map pixel per four blocks. Use the
     * center of that display cell as its deterministic representative. The old
     * four-corner vote performed four times as much RWG work and could select a
     * biome that did not correspond to the displayed coordinate.
     */
    private BiomeGenBase sampleDisplayedBiome(
            SurfaceBiomeSampler sampler,
            int x,
            int z,
            int step) {
        int offset = step == 4 ? 2 : 0;
        return sampler.getBiomeAt(x + offset, z + offset);
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
        if (matches(request, MOON_KEY, MoonBiomeSampler.configuredDimensionId())) {
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
        if (matches(
                request,
                TWILIGHT_FOREST_KEY,
                TwilightForestBiomeSampler.configuredDimensionId())) {
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
        if (SpaceDimensionSampler.supports(request.dimensionKey, request.dimension)) {
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
                    request.dimensionKey,
                    request.x,
                    request.z,
                    request.width,
                    request.height);
            if (SpaceDimensionSampler.matches(
                    request.dimensionKey,
                    "bartworks:ross128b",
                    request.dimension,
                    SpaceDimensionSampler.ross128bDimensionId())) {
                addOreVeinPredictions(response, request);
            }
            return response;
        }
        if (matches(request, END_KEY, END)) {
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
            try {
                if (endAsteroidPredictor == null) {
                    endAsteroidPredictor = new EndAsteroidPredictor();
                }
                response.structures.addAll(endAsteroidPredictor.predict(
                        request.seed,
                        request.x,
                        request.z,
                        request.width,
                        request.height));
            } catch (RuntimeException e) {
                AmidstGtnhWorkerLog.LOG.warn(
                        "GT End asteroid prediction is unavailable; continuing with End structures",
                        e);
            }
            return response;
        }
        if (matches(request, NETHER_KEY, NETHER)) {
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
            addOreVeinPredictions(response, request);
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

    private void addOreVeinPredictions(Response response, Request request) {
        try {
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
        } catch (RuntimeException e) {
            // An incompatible GT ore API must not make the entire dimension
            // unusable. Other structures and the biome map remain available,
            // while the full cause is retained in the GTNH log.
            AmidstGtnhWorkerLog.LOG.error(
                    "GT ore locator is unavailable for dimension {}; omitting ore markers",
                    Integer.valueOf(request.dimension),
                    e);
        }
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

    private static boolean matches(Request request, String dimensionKey, int dimensionId) {
        return request.dimensionKey == null || request.dimensionKey.isEmpty()
                ? request.dimension == dimensionId
                : dimensionKey.equals(request.dimensionKey);
    }

    private static void validateRequest(Request request) {
        if (!matches(request, OVERWORLD_KEY, OVERWORLD)
                && !matches(request, NETHER_KEY, NETHER)
                && !matches(request, END_KEY, END)
                && !matches(request, MOON_KEY, MoonBiomeSampler.configuredDimensionId())
                && !matches(
                        request,
                        TWILIGHT_FOREST_KEY,
                        TwilightForestBiomeSampler.configuredDimensionId())
                && !SpaceDimensionSampler.supports(
                        request.dimensionKey,
                        request.dimension)) {
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
        if (!matches(request, OVERWORLD_KEY, OVERWORLD)
                && !matches(request, NETHER_KEY, NETHER)
                && !matches(request, END_KEY, END)
                && !matches(request, MOON_KEY, MoonBiomeSampler.configuredDimensionId())
                && !matches(
                        request,
                        TWILIGHT_FOREST_KEY,
                        TwilightForestBiomeSampler.configuredDimensionId())
                && !SpaceDimensionSampler.supports(
                        request.dimensionKey,
                        request.dimension)) {
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
        for (Map.Entry<String, BiomeGenBase[]> scoped :
                SpaceDimensionSampler.scopedBiomeSources().entrySet()) {
            for (BiomeGenBase biome : scoped.getValue()) {
                BiomeDescriptor source = new BiomeDescriptor(biome);
                int displayId =
                        SpaceDimensionSampler.displayBiomeId(
                                scoped.getKey(),
                                biome.biomeID);
                byId.put(
                        Integer.valueOf(displayId),
                        new BiomeDescriptor(displayId, source));
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
        addSyntheticSpaceBiome(
                byId,
                SpaceDimensionSampler.CERES_DISPLAY_BIOME,
                "Ceres",
                0x858B91);
        addSyntheticSpaceBiome(
                byId,
                SpaceDimensionSampler.ENCELADUS_DISPLAY_BIOME,
                "Enceladus",
                0xD4E2E8);
        addSyntheticSpaceBiome(
                byId,
                SpaceDimensionSampler.PROTEUS_DISPLAY_BIOME,
                "Proteus",
                0x667782);
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

    private static void addSyntheticSpaceBiome(
            Map<Integer, BiomeDescriptor> byId,
            int id,
            String name,
            int color) {
        byId.put(
                Integer.valueOf(id),
                new BiomeDescriptor(
                        id,
                        name,
                        color,
                        0.2F,
                        0.0F,
                        0.1F,
                        0.2F,
                        "GALAXYSPACE"));
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
        biomeTileCache.clear();
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
        private String dimensionKey;
        private int x;
        private int z;
        private int width;
        private int height;
        private int step;
        private long sinceRevision;
        private boolean vanillaDungeonsOnly;
        private List<JourneyMapWaypointImporter.WaypointDescriptor> waypoints;
    }

    private static final class BiomeTileKey {

        private final long seed;
        private final int dimension;
        private final int x;
        private final int z;
        private final int width;
        private final int height;
        private final int step;

        private BiomeTileKey(Request request) {
            seed = request.seed;
            dimension = request.dimension;
            x = request.x;
            z = request.z;
            width = request.width;
            height = request.height;
            step = request.step;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof BiomeTileKey)) {
                return false;
            }
            BiomeTileKey key = (BiomeTileKey) other;
            return seed == key.seed
                    && dimension == key.dimension
                    && x == key.x
                    && z == key.z
                    && width == key.width
                    && height == key.height
                    && step == key.step;
        }

        @Override
        public int hashCode() {
            int result = (int) (seed ^ (seed >>> 32));
            result = 31 * result + dimension;
            result = 31 * result + x;
            result = 31 * result + z;
            result = 31 * result + width;
            result = 31 * result + height;
            result = 31 * result + step;
            return result;
        }
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
        private int barnardaCDimensionId;
        private int deepDarkDimensionId;
        private int anubisDimensionId;
        private int horusDimensionId;
        private int[] ids;
        private List<RoguelikeDungeonPredictor.StructureDescriptor> structures;
        private int spawnX;
        private int spawnZ;
        private int importedWaypoints;
        private long worldRevision;
        private boolean fullRefresh;
        private int[] chunkXs;
        private int[] chunkZs;

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

    private static final class ChunkChange {

        private final long revision;
        private final int chunkX;
        private final int chunkZ;

        private ChunkChange(long revision, int chunkX, int chunkZ) {
            this.revision = revision;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
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

        private BiomeDescriptor(int id, BiomeDescriptor source) {
            this.id = id;
            this.name = source.name;
            this.mapColor = source.mapColor;
            this.temperature = source.temperature;
            this.rainfall = source.rainfall;
            this.rootHeight = source.rootHeight;
            this.heightVariation = source.heightVariation;
            this.tags = new ArrayList<String>(source.tags);
        }
    }
}
