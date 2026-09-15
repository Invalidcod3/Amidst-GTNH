package amidst.gtnh.worker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.export.GtnhWaypoint;
import amidst.gtnh.structure.GtnhStructureDescriptor;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.threading.TaskCancellation;

@ThreadSafe
public final class GtnhBiomeWorkerClient implements GtnhBiomeSource {

    @Override
    public List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> prospectingCatalog() throws MinecraftInterfaceException {
        Request request = Request.hello(token);
        request.command = "prospecting_catalog";
        Response response = exchange(request);
        if (response.prospectingDimensions != null) for (var dimension : response.prospectingDimensions) {
            if (dimension == null || dimension.key == null || dimension.oreOptions == null || dimension.fluidOptions == null)
                throw new MinecraftInterfaceException("Worker returned an incomplete prospecting catalog; update both Viewer and Worker.");
            for (var options : List.of(dimension.oreOptions, dimension.fluidOptions)) for (var option : options) {
                if (option == null || option.id == null || option.id.isEmpty() || option.name == null)
                    throw new MinecraftInterfaceException("Worker returned an invalid prospecting choice.");
            }
        }
        return response.prospectingDimensions == null ? List.of() : response.prospectingDimensions;
    }

    @Override
    public amidst.gtnh.prospecting.ProspectingData.Tile prospect(long seed, int dimension, int x, int z,
            int width, int height, String mode) throws MinecraftInterfaceException {
        return prospectFiltered(seed, dimension, x, z, width, height, mode, null);
    }
    @Override
    public amidst.gtnh.prospecting.ProspectingData.Tile prospectFiltered(long seed, int dimension, int x, int z,
            int width, int height, String mode, amidst.gtnh.prospecting.ProspectingData.QueryFilter filter) throws MinecraftInterfaceException {
        Request request = Request.hello(token);
        request.command = "prospecting"; request.seed = seed; request.dimension = dimension;
        request.x = x; request.z = z; request.width = width; request.height = height; request.markerMode = mode;
        request.prospectingFilter = filter;
        Response response = exchange(request);
        if (response.prospecting == null || response.prospecting.deposits == null)
            throw new MinecraftInterfaceException("Worker returned invalid prospecting data");
        if (response.prospecting.deposits.size() > 4096)
            throw new MinecraftInterfaceException("Worker returned too many deposits");
        for (var d : response.prospecting.deposits) {
            if (d == null || d.id == null || d.name == null || d.source == null
                    || ("FLUID".equals(mode) && (d.size != 128 || d.amounts == null || d.amounts.length != 64))
                    || (d.currentAmounts != null && d.currentAmounts.length != 64))
                throw new MinecraftInterfaceException("Worker returned an invalid deposit");
        }
        return response.prospecting;
    }
    @Override public amidst.gtnh.validation.AccuracyReport validate(long seed,int dimension,String key,int x,int z,
            int width,int height,int step,String category,String session) throws MinecraftInterfaceException {
        Request request=Request.hello(token);request.command="validate";request.seed=seed;request.dimension=dimension;
        request.dimensionKey=key;request.x=x;request.z=z;request.width=width;request.height=height;request.step=step;
        request.category=category;request.expectedSession=session;
        var report=exchange(request).accuracy;
        if(report==null || report.details==null || report.details.size()>1000 || report.worldSession==null
                || !category.equals(report.category) || report.dimension!=dimension || report.seed!=seed
                || report.matched<0 || report.mismatched<0 || report.unverified<0)
            throw new MinecraftInterfaceException("Worker returned an invalid accuracy report");
        return report;
    }
	private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;
	private static final int DEFAULT_RETRY_DELAY_MILLIS = 2_000;

	private final String host;
	private final int port;
	private final String token;
	private final int timeoutMillis;
	private final int retryDelayMillis;
	private final Runnable offlineNotifier;
	private final boolean waitForWorker;
	private final boolean profileQueries = Boolean.getBoolean("amidst.gtnh.profileQueries");
	private final Gson gson;
	private final Object availabilityMonitor = new Object();

	private boolean offline;
    private volatile String lastWorldSession;
    private volatile long retryCacheAfter;
    private static final long CACHE_RETRY_NANOS = 30_000_000_000L;
    private static final int CACHE_TIMEOUT_MILLIS = 2000;
    private final amidst.gtnh.cache.MapStorage mapCache = new amidst.gtnh.cache.MapStorage(amidst.gtnh.cache.MapStorage.root().resolve("biomes"));
    @Override public String cacheIdentity(long seed) throws MinecraftInterfaceException {
        Request request = Request.hello(token);request.command="cache_context";request.seed=seed;
        Response response = optionalCacheContext(request);
        return response == null ? null : response.cacheIdentity;
    }

    /** A slow or unavailable optimization must not prevent a real biome query. */
    private Response optionalCacheContext(Request request) {
        if (retryCacheAfter != 0 && System.nanoTime() - retryCacheAfter < 0) return null;
        try {
            return exchangeOnce(request, Math.min(timeoutMillis, CACHE_TIMEOUT_MILLIS));
        } catch (IOException | MinecraftInterfaceException failure) {
            retryCacheAfter = System.nanoTime() + CACHE_RETRY_NANOS;
            AmidstLogger.warn(failure, "Disk map cache is temporarily unavailable; loading maps directly");
            return null;
        }
    }

	public GtnhBiomeWorkerClient(String host, int port, String token) {
		this(
				host,
				port,
				token,
				DEFAULT_TIMEOUT_MILLIS,
				DEFAULT_RETRY_DELAY_MILLIS,
				() -> { /* Waiting is reported in the console, without a modal dialog. */ },
				true);
	}

	public GtnhBiomeWorkerClient(String host, int port, String token, int timeoutMillis) {
		this(host, port, token, timeoutMillis, DEFAULT_RETRY_DELAY_MILLIS, () -> {
		}, false);
	}

	private GtnhBiomeWorkerClient(
			String host,
			int port,
			String token,
			int timeoutMillis,
			int retryDelayMillis,
			Runnable offlineNotifier,
			boolean waitForWorker) {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("host must not be blank");
		}
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("port is outside the valid TCP range");
		}
		if (timeoutMillis < 1) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		if (retryDelayMillis < 1) {
			throw new IllegalArgumentException("retry delay must be positive");
		}
		this.host = host;
		this.port = port;
		this.token = token == null ? "" : token;
		this.timeoutMillis = timeoutMillis;
		this.retryDelayMillis = retryDelayMillis;
		this.offlineNotifier = Objects.requireNonNull(offlineNotifier, "offlineNotifier");
		this.waitForWorker = waitForWorker;
		this.gson = new Gson();
	}

	GtnhBiomeWorkerClient(
			String host,
			int port,
			String token,
			int timeoutMillis,
			int retryDelayMillis,
			Runnable offlineNotifier) {
		this(
				host,
				port,
				token,
				timeoutMillis,
				retryDelayMillis,
				offlineNotifier,
				true);
	}

	@Override
	public GtnhWorkerInfo getWorkerInfo() throws MinecraftInterfaceException {
		Response response = exchange(Request.hello(token));
		List<GtnhBiomeDescriptor> biomes = response.biomes == null
				? List.of()
				: Arrays.stream(response.biomes)
						.map(biome -> new GtnhBiomeDescriptor(
								biome.id,
								biome.name,
								biome.mapColor,
								biome.temperature,
								biome.rainfall,
								biome.rootHeight,
								biome.heightVariation,
								biome.tags == null ? List.of() : Arrays.asList(biome.tags)))
						.toList();
		try {
			return new GtnhWorkerInfo(
					response.protocol,
					response.seed,
					response.worldType,
					response.providerClass,
					response.chunkManagerClass,
					response.twilightForestDimensionId,
					response.moonDimensionId,
					response.marsDimensionId,
					response.asteroidsDimensionId,
					response.ceresDimensionId,
					response.ioDimensionId,
					response.enceladusDimensionId,
					response.proteusDimensionId,
					response.plutoDimensionId,
					response.mehenBeltDimensionId,
					response.ross128bDimensionId,
					response.barnardaCDimensionId,
					response.deepDarkDimensionId,
					response.anubisDimensionId,
					response.horusDimensionId,
					biomes);
		} catch (IllegalArgumentException e) {
			throw new MinecraftInterfaceException("invalid GTNH worker handshake", e);
		}
	}

	@Override
	public GtnhWorldState getWorldState(long sinceRevision)
			throws MinecraftInterfaceException {
		Response response = exchange(Request.worldState(token, sinceRevision));
		if (response.worldSession != null) {
            if (!response.worldSession.equals(lastWorldSession)) response.fullRefresh = true;
            lastWorldSession = response.worldSession;
        }
        int[] chunkXs = response.chunkXs == null ? new int[0] : response.chunkXs;
		int[] chunkZs = response.chunkZs == null ? new int[0] : response.chunkZs;
		try {
			return new GtnhWorldState(
					response.worldRevision,
					response.fullRefresh,
					chunkXs,
					chunkZs);
		} catch (IllegalArgumentException | NullPointerException e) {
			throw new MinecraftInterfaceException(
					"GTNH worker returned an invalid world-state response",
					e);
		}
	}

	@Override
	public int[] sampleBiomes(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height,
			int step) throws MinecraftInterfaceException {
		return sampleBiomes(
				seed,
				dimensionId,
				null,
				blockX,
				blockZ,
				width,
				height,
				step);
	}

	@Override
	public int[] sampleBiomes(
			long seed,
			int dimensionId,
			String dimensionKey,
			int blockX,
			int blockZ,
			int width,
			int height,
			int step) throws MinecraftInterfaceException {
		validateArea(width, height, step);
        Request request = Request.biomes(token,seed,dimensionId,dimensionKey,blockX,blockZ,width,height,step);
        if (amidst.gtnh.cache.MapStorage.enabled && width*height>=64) {
            request.command="cache_context";
            Response context=optionalCacheContext(request);
            request.cacheStamp=context == null ? null : context.cacheStamp;
            int[] cached=mapCache.read(request.cacheStamp,width*height);
            if(cached!=null)return cached;
            request.command="biomes";
        }
        Response response=exchange(request);
		int expectedLength = Math.multiplyExact(width, height);
		if (response.ids == null || response.ids.length != expectedLength) {
			throw new MinecraftInterfaceException(
					"GTNH worker returned "
							+ (response.ids == null ? "no" : response.ids.length)
							+ " biome ids; expected "
							+ expectedLength);
		}
		mapCache.write(response.cacheStamp,response.ids);
		return response.ids;
	}

	@Override
	public CoordinatesInWorld sampleSpawn(long seed, int dimensionId)
			throws MinecraftInterfaceException {
        GtnhSpawnPoint spawn = sampleSpawnPoint(seed, dimensionId);
        return spawn == null ? null : spawn.coordinates();
    }

    @Override public GtnhSpawnPoint sampleSpawnPoint(long seed, int dimensionId) throws MinecraftInterfaceException {
		Response response = exchange(Request.spawn(token, seed, dimensionId));
        if ("UNAVAILABLE".equals(response.spawnSource)) return null;
        if (response.spawnX == null || response.spawnZ == null
                || ("RECORDED".equals(response.spawnSource) && response.spawnY == null)
                || !("RECORDED".equals(response.spawnSource) || "ESTIMATED".equals(response.spawnSource)))
            throw new MinecraftInterfaceException("Worker returned incomplete world spawn data");
        return new GtnhSpawnPoint(response.spawnX, response.spawnY, response.spawnZ, response.spawnSource);
	}

	@Override
	public List<GtnhStructureDescriptor> sampleStructures(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return sampleStructures(
				seed,
				dimensionId,
				null,
				blockX,
				blockZ,
				width,
				height);
	}

	@Override
	public List<GtnhStructureDescriptor> sampleStructures(
			long seed,
			int dimensionId,
			String dimensionKey,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		validateStructureArea(width, height);
		Response response = exchange(Request.structures(
				token,
				seed,
				dimensionId,
				dimensionKey,
				blockX,
				blockZ,
				width,
				height,
				false));
		return toStructureDescriptors(response);
	}

	@Override
    public List<GtnhStructureDescriptor> sampleStructureGroup(long seed, int dimensionId, String dimensionKey,
            int x, int z, int width, int height, String group) throws MinecraftInterfaceException {
        validateStructureArea(width, height);
        Request request = Request.structures(token, seed, dimensionId, dimensionKey, x, z, width, height, false);
        request.structureGroup = group;
        return toStructureDescriptors(exchange(request));
    }

    @Override
    public List<GtnhStructureDescriptor> sampleVanillaDungeons(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		validateStructureArea(width, height);
		Response response = exchange(Request.structures(
				token,
				seed,
				dimensionId,
				null,
				blockX,
				blockZ,
				width,
				height,
				true));
		return toStructureDescriptors(response);
	}

	@Override
	public int importJourneyMapWaypoints(
			int dimensionId,
			List<GtnhWaypoint> waypoints) throws MinecraftInterfaceException {
		if (waypoints == null || waypoints.isEmpty()) {
			return 0;
		}
		if (waypoints.size() > 2_000) {
			throw new MinecraftInterfaceException(
					"direct JourneyMap import is limited to 2,000 waypoints per operation");
		}
		Response response = exchange(Request.importWaypoints(
				token,
				dimensionId,
				waypoints.toArray(GtnhWaypoint[]::new)));
		return response.importedWaypoints;
	}

	private static List<GtnhStructureDescriptor> toStructureDescriptors(Response response) {
		if (response.structures == null) {
			return List.of();
		}
		return Arrays.stream(response.structures)
				.map(structure -> new GtnhStructureDescriptor(
						structure.kind,
						structure.subtype,
						structure.x,
						structure.z,
						structure.certainty, structure.y))
				.toList();
	}

	private void validateArea(int width, int height, int step) throws MinecraftInterfaceException {
		if (width < 1 || height < 1 || step < 1) {
			throw new MinecraftInterfaceException("biome query width, height and step must be positive");
		}
		try {
			if (Math.multiplyExact(width, height) > 65_536) {
				throw new MinecraftInterfaceException("biome query exceeds the 65,536 sample limit");
			}
		} catch (ArithmeticException e) {
			throw new MinecraftInterfaceException("biome query size overflow", e);
		}
	}

	private void validateStructureArea(int width, int height) throws MinecraftInterfaceException {
		if (width < 1 || height < 1) {
			throw new MinecraftInterfaceException("structure query width and height must be positive");
		}
	}

	private Response exchange(Request request) throws MinecraftInterfaceException {
		while (true) {
			TaskCancellation.check();
			awaitRecovery();
			try {
				return exchangeOnce(request);
			} catch (IOException e) {
				TaskCancellation.check();
				if (!waitForWorker) {
					throw new MinecraftInterfaceException(
							"unable to communicate with GTNH biome worker at " + host + ":" + port,
							e);
				}
				recoverConnection(e);
			}
		}
	}

	private Response exchangeOnce(Request request) throws IOException, MinecraftInterfaceException {
		return exchangeOnce(request, timeoutMillis);
	}

	private Response exchangeOnce(Request request, int requestTimeoutMillis) throws IOException, MinecraftInterfaceException {
		TaskCancellation.check();
		long startedAt = profileQueries ? System.nanoTime() : 0L;
		request.profile = profileQueries ? Boolean.TRUE : null;
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), requestTimeoutMillis);
			socket.setSoTimeout(requestTimeoutMillis);
			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				writer.write(gson.toJson(request));
				writer.newLine();
				writer.flush();

				String line;
				try {
					line = reader.readLine();
				} catch (SocketTimeoutException e) {
					TaskCancellation.check();
					if ("hello".equals(request.command)) {
						throw new IOException("GTNH worker is listening but has not completed its handshake; "
								+ "waiting for the game to finish loading or resume ticking", e);
					}
					throw new MinecraftInterfaceException(
							"Connected to GTNH worker at " + host + ":" + port
									+ ", but command '" + request.command + "' timed out after "
									+ requestTimeoutMillis + " ms. Check the GTNH game tick and the "
									+ "amidstgtnhworker entries in logs/fml-client-latest.log "
									+ "(or the dedicated-server log).",
							e);
				}
				if (line == null) {
					TaskCancellation.check();
					if ("hello".equals(request.command)) {
						throw new IOException("GTNH worker closed the connection before completing its handshake");
					}
					throw new MinecraftInterfaceException("GTNH worker closed the connection without a response");
				}
				// Keep a successful query's result even if its tile just left the view.
				// The loader saves this layer, then cancels before starting the next one.
				Response response = gson.fromJson(line, Response.class);
				if (response == null) {
					throw new MinecraftInterfaceException("GTNH worker returned an empty response");
				}
				if (profileQueries) {
					AmidstLogger.info(String.format(Locale.ROOT,
							"GTNH performance: %s dimension=%d x=%d z=%d size=%dx%d step=%d ok=%s "
									+ "total=%.3f ms queue=%s ms compute=%s ms",
							request.command, request.dimension, request.x, request.z, request.width, request.height,
							request.step, response.ok, (System.nanoTime() - startedAt) / 1_000_000.0,
							formatMillis(response.queueMillis), formatMillis(response.computeMillis)));
				}
				if (response.protocol != PROTOCOL_VERSION) {
					throw new MinecraftInterfaceException(
							"GTNH worker protocol is "
									+ response.protocol
									+ "; this Amidst build requires "
									+ PROTOCOL_VERSION);
				}
				if (!response.ok) {
					TaskCancellation.check();
					// Protocol 18's game-thread queue timeout is transient during startup.
					// Only hello is retried here; query failures, authentication and
					// protocol mismatches must remain actionable errors.
					if ("hello".equals(request.command)
							&& "server thread did not process the query in time".equals(response.error)) {
						throw new IOException("GTNH worker is waiting for the game thread to process its handshake");
					}
					throw new MinecraftInterfaceException(
							"GTNH worker rejected the request: "
									+ (response.error == null ? "unknown error" : amidst.i18n.I18n.text(response.error)));
				}
				return response;
			}
		} catch (JsonParseException e) {
			throw new MinecraftInterfaceException(
					"GTNH worker returned an invalid response",
					e);
		}
	}

	private static String formatMillis(Double value) {
		return value == null ? "unavailable" : String.format(Locale.ROOT, "%.3f", value);
	}

	private void awaitRecovery() throws MinecraftInterfaceException {
		synchronized (availabilityMonitor) {
			while (offline) {
				TaskCancellation.check();
				try {
					availabilityMonitor.wait(100L);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new MinecraftInterfaceException(
							"interrupted while waiting for the GTNH client to start",
							e);
				}
			}
		}
	}

	private void recoverConnection(IOException initialFailure) throws MinecraftInterfaceException {
		boolean recoveryOwner = false;
		synchronized (availabilityMonitor) {
			if (!offline) {
				offline = true;
				recoveryOwner = true;
			}
		}
		if (!recoveryOwner) {
			awaitRecovery();
			return;
		}

		AmidstLogger.warn(
				initialFailure,
				"GTNH worker at {}:{} is not ready; pausing requests until its handshake succeeds",
				host,
				port);
		AmidstLogger.info("Waiting for GTNH worker at {}:{}. Retrying every {} ms without an overall time limit. "
				+ "Start GTNH with the worker enabled; the Viewer will resume automatically.",
				host, port, retryDelayMillis);
		try {
			offlineNotifier.run();
		} catch (RuntimeException e) {
			AmidstLogger.warn(e, "Unable to display the GTNH client status notification");
		}
		try {
			long lastProgress = System.nanoTime();
			while (true) {
				try {
					exchangeOnce(Request.hello(token));
					AmidstLogger.info("GTNH client at {}:{} is available again; resuming requests", host, port);
					return;
				} catch (IOException e) {
					if (System.nanoTime() - lastProgress >= 30_000_000_000L) {
						AmidstLogger.info("Still waiting for GTNH worker at {}:{}: {}", host, port, e.getMessage());
						lastProgress = System.nanoTime();
					}
					waitBeforeRetry();
				}
			}
		} finally {
			// A cancelled viewport task must release recovery ownership, so a
			// current task can continue waiting for the Worker to start.
			finishRecovery();
		}
	}

	private void waitBeforeRetry() throws MinecraftInterfaceException {
		try {
			long deadline = System.nanoTime() + retryDelayMillis * 1_000_000L;
			while (true) {
				TaskCancellation.check();
				long remainingMillis = (deadline - System.nanoTime()) / 1_000_000L;
				if (remainingMillis <= 0) {
					return;
				}
				Thread.sleep(Math.min(remainingMillis, 100L));
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new MinecraftInterfaceException(
					"interrupted while waiting for the GTNH client to start",
					e);
		}
	}

	private void finishRecovery() {
		synchronized (availabilityMonitor) {
			offline = false;
			availabilityMonitor.notifyAll();
		}
	}

	private static final class Request {
        private String category, expectedSession;
        private String cacheStamp;
        private String markerMode;
        private amidst.gtnh.prospecting.ProspectingData.QueryFilter prospectingFilter;
		private Boolean profile;
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
        private String structureGroup;
		private GtnhWaypoint[] waypoints;

		private static Request hello(String token) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "hello";
			request.token = token;
			return request;
		}

		private static Request worldState(String token, long sinceRevision) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "world_state";
			request.token = token;
			request.sinceRevision = sinceRevision;
			return request;
		}

		private static Request biomes(
				String token,
				long seed,
				int dimension,
				String dimensionKey,
				int x,
				int z,
				int width,
				int height,
				int step) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "biomes";
			request.token = token;
			request.seed = seed;
			request.dimension = dimension;
			request.dimensionKey = dimensionKey;
			request.x = x;
			request.z = z;
			request.width = width;
			request.height = height;
			request.step = step;
			return request;
		}

		private static Request spawn(String token, long seed, int dimension) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "spawn";
			request.token = token;
			request.seed = seed;
			request.dimension = dimension;
			return request;
		}

		private static Request structures(
				String token,
				long seed,
				int dimension,
				String dimensionKey,
				int x,
				int z,
				int width,
				int height,
				boolean vanillaDungeonsOnly) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "structures";
			request.token = token;
			request.seed = seed;
			request.dimension = dimension;
			request.dimensionKey = dimensionKey;
			request.x = x;
			request.z = z;
			request.width = width;
			request.height = height;
			request.vanillaDungeonsOnly = vanillaDungeonsOnly;
			return request;
		}

		private static Request importWaypoints(
				String token,
				int dimension,
				GtnhWaypoint[] waypoints) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "import_waypoints";
			request.token = token;
			request.dimension = dimension;
			request.waypoints = waypoints;
			return request;
		}
	}

	private static final class Response {
        private amidst.gtnh.validation.AccuracyReport accuracy;
        private String cacheIdentity, cacheStamp, worldSession;
        private List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> prospectingDimensions;
        private amidst.gtnh.prospecting.ProspectingData.Tile prospecting;
		private Double queueMillis;
		private Double computeMillis;
		private boolean ok;
		private String error;
		private int protocol;
		private long seed;
		private String worldType;
		private String providerClass;
		private String chunkManagerClass;
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
		private BiomeDescriptor[] biomes;
		private int[] ids;
		private StructureDescriptor[] structures;
        private Integer spawnX, spawnY, spawnZ;
        private String spawnSource;
		private int importedWaypoints;
		private long worldRevision;
		private boolean fullRefresh;
		private int[] chunkXs;
		private int[] chunkZs;
	}

	private static final class BiomeDescriptor {
		private int id;
		private String name;
		private int mapColor;
		private float temperature;
		private float rainfall;
		private float rootHeight;
		private float heightVariation;
		private String[] tags;
	}

	private static final class StructureDescriptor {
		private String kind;
		private String subtype;
		private int x;
		private int z;
		private String certainty;
        private Integer y;
	}
}
