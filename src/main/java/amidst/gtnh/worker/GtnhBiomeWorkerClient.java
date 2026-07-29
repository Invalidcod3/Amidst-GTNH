package amidst.gtnh.worker;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.export.GtnhWaypoint;
import amidst.gtnh.structure.GtnhStructureDescriptor;
import amidst.logging.AmidstLogger;
import amidst.logging.AmidstMessageBox;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

@ThreadSafe
public final class GtnhBiomeWorkerClient implements GtnhBiomeSource {
	private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;
	private static final int DEFAULT_RETRY_DELAY_MILLIS = 2_000;

	private final String host;
	private final int port;
	private final String token;
	private final int timeoutMillis;
	private final int retryDelayMillis;
	private final Runnable offlineNotifier;
	private final boolean waitForWorker;
	private final Gson gson;
	private final Object availabilityMonitor = new Object();

	private boolean offline;

	public GtnhBiomeWorkerClient(String host, int port, String token) {
		this(
				host,
				port,
				token,
				DEFAULT_TIMEOUT_MILLIS,
				DEFAULT_RETRY_DELAY_MILLIS,
				() -> AmidstMessageBox.displayWarning(
						"GTNH client not detected",
						"Amidst could not detect the GTNH client at "
								+ host
								+ ":"
								+ port
								+ ".\n\nMap updates have been paused. Amidst will wait for GTNH to start "
								+ "and resume automatically when the worker becomes available."),
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
		Response response = exchange(Request.biomes(
				token,
				seed,
				dimensionId,
				dimensionKey,
				blockX,
				blockZ,
				width,
				height,
				step));
		int expectedLength = Math.multiplyExact(width, height);
		if (response.ids == null || response.ids.length != expectedLength) {
			throw new MinecraftInterfaceException(
					"GTNH worker returned "
							+ (response.ids == null ? "no" : response.ids.length)
							+ " biome ids; expected "
							+ expectedLength);
		}
		return response.ids;
	}

	@Override
	public CoordinatesInWorld sampleSpawn(long seed, int dimensionId)
			throws MinecraftInterfaceException {
		Response response = exchange(Request.spawn(token, seed, dimensionId));
		return CoordinatesInWorld.from(response.spawnX, response.spawnZ);
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
						structure.certainty))
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
			awaitRecovery();
			try {
				return exchangeOnce(request);
			} catch (IOException e) {
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
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(host, port), timeoutMillis);
			socket.setSoTimeout(timeoutMillis);
			try (BufferedWriter writer = new BufferedWriter(
					new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
					BufferedReader reader = new BufferedReader(
							new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
				writer.write(gson.toJson(request));
				writer.newLine();
				writer.flush();

				String line = reader.readLine();
				if (line == null) {
					throw new MinecraftInterfaceException("GTNH worker closed the connection without a response");
				}
				Response response = gson.fromJson(line, Response.class);
				if (response == null) {
					throw new MinecraftInterfaceException("GTNH worker returned an empty response");
				}
				if (response.protocol != PROTOCOL_VERSION) {
					throw new MinecraftInterfaceException(
							"GTNH worker protocol is "
									+ response.protocol
									+ "; this Amidst build requires "
									+ PROTOCOL_VERSION);
				}
				if (!response.ok) {
					throw new MinecraftInterfaceException(
							"GTNH worker rejected the request: "
									+ (response.error == null ? "unknown error" : response.error));
				}
				return response;
			}
		} catch (JsonParseException e) {
			throw new MinecraftInterfaceException(
					"GTNH worker returned an invalid response",
					e);
		}
	}

	private void awaitRecovery() throws MinecraftInterfaceException {
		synchronized (availabilityMonitor) {
			while (offline) {
				try {
					availabilityMonitor.wait();
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
				"GTNH client at {}:{} is unavailable; pausing requests until it returns",
				host,
				port);
		try {
			offlineNotifier.run();
		} catch (RuntimeException e) {
			AmidstLogger.warn(e, "Unable to display the GTNH client status notification");
		}
		while (true) {
			try {
				exchangeOnce(Request.hello(token));
				finishRecovery();
				AmidstLogger.info("GTNH client at {}:{} is available again; resuming requests", host, port);
				return;
			} catch (IOException e) {
				waitBeforeRetry();
			} catch (MinecraftInterfaceException e) {
				finishRecovery();
				throw e;
			}
		}
	}

	private void waitBeforeRetry() throws MinecraftInterfaceException {
		try {
			Thread.sleep(retryDelayMillis);
		} catch (InterruptedException e) {
			finishRecovery();
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
		private int spawnX;
		private int spawnZ;
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
	}
}
