package amidst.gtnh.worker;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.structure.GtnhStructureDescriptor;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.minecraftinterface.UnsupportedDimensionException;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.biome.BiomeColor;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

@ThreadSafe
public final class GtnhMinecraftInterface implements MinecraftInterface, GtnhBiomeCatalogProvider {
	private static final Set<Dimension> SUPPORTED_DIMENSIONS =
			Collections.unmodifiableSet(EnumSet.of(
					Dimension.OVERWORLD,
					Dimension.NETHER,
					Dimension.END,
					Dimension.MOON,
					Dimension.MARS,
					Dimension.ASTEROIDS,
					Dimension.CERES,
					Dimension.IO,
					Dimension.ENCELADUS,
					Dimension.PROTEUS,
					Dimension.PLUTO,
					Dimension.MEHEN_BELT,
					Dimension.ROSS_128B,
					Dimension.TWILIGHT_FOREST));

	private final GtnhBiomeSource source;
	private final GtnhWorkerInfo workerInfo;
	private final BiomeList biomeList;
	private final Set<Integer> biomeIds;
	private final Map<Integer, BiomeColor> runtimeBiomeColors;

	public static GtnhMinecraftInterface connect(String host, int port, String token)
			throws MinecraftInterfaceException {
		return new GtnhMinecraftInterface(new GtnhBiomeWorkerClient(host, port, token));
	}

	public static GtnhMinecraftInterface connect(GtnhWorkerSettings settings)
			throws MinecraftInterfaceException {
		return new GtnhMinecraftInterface(
				new GtnhBiomeWorkerClient(settings.host(), settings.port(), settings.token()),
				settings.biomeColorsFile());
	}

	public GtnhMinecraftInterface(GtnhBiomeSource source) throws MinecraftInterfaceException {
		this(source, null);
	}

	GtnhMinecraftInterface(GtnhBiomeSource source, java.nio.file.Path biomeColorsFile)
			throws MinecraftInterfaceException {
		this.source = source;
		this.workerInfo = source.getWorkerInfo();
		if (!"RWG".equalsIgnoreCase(workerInfo.worldType())) {
			throw new MinecraftInterfaceException(
					"GTNH biome worker world type is "
							+ workerInfo.worldType()
							+ "; this prototype requires RWG");
		}
		BiomeList runtimeBiomes = new BiomeList();
		workerInfo.biomes().stream().map(GtnhBiomeDescriptor::toBiome).forEach(runtimeBiomes::add);
		this.biomeList = BiomeList.construct(runtimeBiomes);
		this.biomeIds = Collections.unmodifiableSet(new HashSet<>(
				workerInfo.biomes().stream().map(GtnhBiomeDescriptor::id).toList()));
		this.runtimeBiomeColors = GtnhBiomeColorPalette.create(workerInfo, biomeColorsFile);
	}

	@Override
	public WorldAccessor createWorldAccessor(WorldOptions worldOptions) throws MinecraftInterfaceException {
		long requestedSeed = worldOptions.getWorldSeed().getLong();
		return new Accessor(requestedSeed);
	}

	@Override
	public RecognisedVersion getRecognisedVersion() {
		return RecognisedVersion._1_7_10;
	}

	@Override
	public BiomeList getBiomeList() {
		return biomeList;
	}

	public GtnhWorkerInfo getWorkerInfo() {
		return workerInfo;
	}

	public Map<Integer, BiomeColor> getRuntimeBiomeColors() {
		return runtimeBiomeColors;
	}

	public CoordinatesInWorld getWorldSpawn(long seed) throws MinecraftInterfaceException {
		return source.sampleSpawn(seed, Dimension.OVERWORLD.getId());
	}

	public List<GtnhStructureDescriptor> sampleStructures(
			long seed,
			Dimension dimension,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return source.sampleStructures(
				seed,
				toWorkerDimensionId(dimension),
				blockX,
				blockZ,
				width,
				height);
	}

	public List<GtnhStructureDescriptor> sampleVanillaDungeons(
			long seed,
			int dimensionId,
			int blockX,
			int blockZ,
			int width,
			int height) throws MinecraftInterfaceException {
		return source.sampleVanillaDungeons(seed, dimensionId, blockX, blockZ, width, height);
	}

	private int toWorkerDimensionId(Dimension dimension) {
		return switch (dimension) {
			case TWILIGHT_FOREST -> workerInfo.twilightForestDimensionId();
			case MOON -> workerInfo.moonDimensionId();
			case MARS -> workerInfo.marsDimensionId();
			case ASTEROIDS -> workerInfo.asteroidsDimensionId();
			case CERES -> workerInfo.ceresDimensionId();
			case IO -> workerInfo.ioDimensionId();
			case ENCELADUS -> workerInfo.enceladusDimensionId();
			case PROTEUS -> workerInfo.proteusDimensionId();
			case PLUTO -> workerInfo.plutoDimensionId();
			case MEHEN_BELT -> workerInfo.mehenBeltDimensionId();
			case ROSS_128B -> workerInfo.ross128bDimensionId();
			default -> dimension.getId();
		};
	}

	private final class Accessor implements WorldAccessor {
		private final long seed;

		private Accessor(long seed) {
			this.seed = seed;
		}

		@Override
		public <T> T getBiomeData(
				Dimension dimension,
				int x,
				int y,
				int width,
				int height,
				boolean useQuarterResolution,
				Function<int[], T> biomeDataMapper) throws MinecraftInterfaceException {
			if (!SUPPORTED_DIMENSIONS.contains(dimension)) {
				throw new UnsupportedDimensionException(dimension);
			}
			int step = useQuarterResolution ? 4 : 1;
			int blockX;
			int blockZ;
			try {
				blockX = Math.multiplyExact(x, step);
				blockZ = Math.multiplyExact(y, step);
			} catch (ArithmeticException e) {
				throw new MinecraftInterfaceException("GTNH biome coordinate overflow", e);
			}
			int[] biomes = source.sampleBiomes(
					seed,
					toWorkerDimensionId(dimension),
					blockX,
					blockZ,
					width,
					height,
					step);
			for (int biome : biomes) {
				if (!biomeIds.contains(biome)) {
					throw new MinecraftInterfaceException(
							"GTNH worker sampled unregistered biome id "
									+ biome
									+ "; reconnect after GTNH finishes mod initialization");
				}
			}
			return biomeDataMapper.apply(biomes);
		}

		@Override
		public Set<Dimension> supportedDimensions() {
			return SUPPORTED_DIMENSIONS;
		}
	}
}
