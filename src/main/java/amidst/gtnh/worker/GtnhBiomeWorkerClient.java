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

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.structure.GtnhStructureDescriptor;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

@ThreadSafe
public final class GtnhBiomeWorkerClient implements GtnhBiomeSource {
	private static final int DEFAULT_TIMEOUT_MILLIS = 30_000;

	private final String host;
	private final int port;
	private final String token;
	private final int timeoutMillis;
	private final Gson gson;

	public GtnhBiomeWorkerClient(String host, int port, String token) {
		this(host, port, token, DEFAULT_TIMEOUT_MILLIS);
	}

	public GtnhBiomeWorkerClient(String host, int port, String token, int timeoutMillis) {
		if (host == null || host.isBlank()) {
			throw new IllegalArgumentException("host must not be blank");
		}
		if (port < 1 || port > 65_535) {
			throw new IllegalArgumentException("port is outside the valid TCP range");
		}
		if (timeoutMillis < 1) {
			throw new IllegalArgumentException("timeout must be positive");
		}
		this.host = host;
		this.port = port;
		this.token = token == null ? "" : token;
		this.timeoutMillis = timeoutMillis;
		this.gson = new Gson();
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
					biomes);
		} catch (IllegalArgumentException e) {
			throw new MinecraftInterfaceException("invalid GTNH worker handshake", e);
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
		validateArea(width, height, step);
		Response response = exchange(Request.biomes(
				token,
				seed,
				dimensionId,
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
		validateStructureArea(width, height);
		Response response = exchange(Request.structures(
				token,
				seed,
				dimensionId,
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
				blockX,
				blockZ,
				width,
				height,
				true));
		return toStructureDescriptors(response);
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
		} catch (IOException | JsonParseException e) {
			throw new MinecraftInterfaceException(
					"unable to communicate with GTNH biome worker at " + host + ":" + port,
					e);
		}
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

		private static Request hello(String token) {
			Request request = new Request();
			request.protocol = PROTOCOL_VERSION;
			request.command = "hello";
			request.token = token;
			return request;
		}

		private static Request biomes(
				String token,
				long seed,
				int dimension,
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
			request.x = x;
			request.z = z;
			request.width = width;
			request.height = height;
			request.vanillaDungeonsOnly = vanillaDungeonsOnly;
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
		private BiomeDescriptor[] biomes;
		private int[] ids;
		private StructureDescriptor[] structures;
		private int spawnX;
		private int spawnZ;
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
