package amidst.gtnh.structure;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import amidst.documentation.ThreadSafe;
import amidst.fragment.Fragment;
import amidst.gtnh.worker.GtnhMinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinateUtils;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;

@ThreadSafe
public final class GtnhRoguelikeDungeonProducers {
	private static final int MAX_CACHED_FRAGMENTS = 256;

	private final GtnhMinecraftInterface minecraftInterface;
	private final long seed;
	private final CoordinatesInWorld worldSpawn;
	private final Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> cache;
	private final Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> netherCache;
	private final Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> endCache;
	private final Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> moonCache;
	private final Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> twilightForestCache;
	private final Map<Dimension, Map<CoordinatesInWorld, List<GtnhStructureDescriptor>>>
			spaceCaches;
	private final Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> dungeonCache;
	private final Map<GtnhRoguelikeDungeonType, WorldIconProducer<Void>> producers;
	private final Map<GtnhOverworldStructureType, WorldIconProducer<Void>> overworldProducers;
	private final Map<GtnhNetherStructureType, WorldIconProducer<Void>> netherProducers;
	private final Map<GtnhEndStructureType, WorldIconProducer<Void>> endProducers;
	private final Map<GtnhMoonStructureType, WorldIconProducer<Void>> moonProducers;
	private final Map<GtnhSpaceStructureType, WorldIconProducer<Void>> spaceProducers;
	private final Map<GtnhTwilightForestFeatureType, WorldIconProducer<Void>>
			twilightForestProducers;

	public static GtnhRoguelikeDungeonProducers empty() {
		return new GtnhRoguelikeDungeonProducers();
	}

	public GtnhRoguelikeDungeonProducers(GtnhMinecraftInterface minecraftInterface, long seed) {
		this(minecraftInterface, seed, null);
	}

	public GtnhRoguelikeDungeonProducers(
			GtnhMinecraftInterface minecraftInterface,
			long seed,
			CoordinatesInWorld worldSpawn) {
		this.minecraftInterface = minecraftInterface;
		this.seed = seed;
		this.worldSpawn = worldSpawn;
		this.cache = Collections.synchronizedMap(
				new LinkedHashMap<CoordinatesInWorld, List<GtnhStructureDescriptor>>(32, 0.75F, true) {
					@Override
					protected boolean removeEldestEntry(
							Map.Entry<CoordinatesInWorld, List<GtnhStructureDescriptor>> eldest) {
						return size() > MAX_CACHED_FRAGMENTS;
					}
				});
		this.netherCache = createFragmentCache();
		this.endCache = createFragmentCache();
		this.moonCache = createFragmentCache();
		this.twilightForestCache = createFragmentCache();
		this.spaceCaches = createSpaceCaches();
		this.dungeonCache = createFragmentCache();
		this.producers = createProducers();
		this.overworldProducers = createOverworldProducers();
		this.netherProducers = createNetherProducers();
		this.endProducers = createEndProducers();
		this.moonProducers = createMoonProducers();
		this.spaceProducers = createSpaceProducers();
		this.twilightForestProducers = createTwilightForestProducers();
	}

	private GtnhRoguelikeDungeonProducers() {
		this.minecraftInterface = null;
		this.seed = 0L;
		this.worldSpawn = null;
		this.cache = Collections.emptyMap();
		this.netherCache = Collections.emptyMap();
		this.endCache = Collections.emptyMap();
		this.moonCache = Collections.emptyMap();
		this.twilightForestCache = Collections.emptyMap();
		this.spaceCaches = Collections.emptyMap();
		this.dungeonCache = Collections.emptyMap();
		this.producers = createProducers();
		this.overworldProducers = createOverworldProducers();
		this.netherProducers = createNetherProducers();
		this.endProducers = createEndProducers();
		this.moonProducers = createMoonProducers();
		this.spaceProducers = createSpaceProducers();
		this.twilightForestProducers = createTwilightForestProducers();
	}

	public WorldIconProducer<Void> get(GtnhRoguelikeDungeonType type) {
		return producers.get(type);
	}

	public WorldIconProducer<Void> get(GtnhOverworldStructureType type) {
		return overworldProducers.get(type);
	}

	public WorldIconProducer<Void> get(GtnhNetherStructureType type) {
		return netherProducers.get(type);
	}

	public WorldIconProducer<Void> get(GtnhEndStructureType type) {
		return endProducers.get(type);
	}

	public WorldIconProducer<Void> get(GtnhMoonStructureType type) {
		return moonProducers.get(type);
	}

	public WorldIconProducer<Void> get(GtnhSpaceStructureType type) {
		return spaceProducers.get(type);
	}

	public WorldIconProducer<Void> get(GtnhTwilightForestFeatureType type) {
		return twilightForestProducers.get(type);
	}

	private Map<GtnhRoguelikeDungeonType, WorldIconProducer<Void>> createProducers() {
		Map<GtnhRoguelikeDungeonType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhRoguelikeDungeonType.class);
		for (GtnhRoguelikeDungeonType type : GtnhRoguelikeDungeonType.values()) {
			result.put(type, new TypeProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<GtnhOverworldStructureType, WorldIconProducer<Void>> createOverworldProducers() {
		Map<GtnhOverworldStructureType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhOverworldStructureType.class);
		for (GtnhOverworldStructureType type : GtnhOverworldStructureType.values()) {
			result.put(type, new OverworldStructureProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<GtnhTwilightForestFeatureType, WorldIconProducer<Void>>
			createTwilightForestProducers() {
		Map<GtnhTwilightForestFeatureType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhTwilightForestFeatureType.class);
		for (GtnhTwilightForestFeatureType type :
				GtnhTwilightForestFeatureType.values()) {
			result.put(type, new TwilightForestStructureProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<GtnhNetherStructureType, WorldIconProducer<Void>> createNetherProducers() {
		Map<GtnhNetherStructureType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhNetherStructureType.class);
		for (GtnhNetherStructureType type : GtnhNetherStructureType.values()) {
			result.put(type, new NetherStructureProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<GtnhEndStructureType, WorldIconProducer<Void>> createEndProducers() {
		Map<GtnhEndStructureType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhEndStructureType.class);
		for (GtnhEndStructureType type : GtnhEndStructureType.values()) {
			result.put(type, new EndStructureProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<GtnhMoonStructureType, WorldIconProducer<Void>> createMoonProducers() {
		Map<GtnhMoonStructureType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhMoonStructureType.class);
		for (GtnhMoonStructureType type : GtnhMoonStructureType.values()) {
			result.put(type, new MoonStructureProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<GtnhSpaceStructureType, WorldIconProducer<Void>> createSpaceProducers() {
		Map<GtnhSpaceStructureType, WorldIconProducer<Void>> result =
				new EnumMap<>(GtnhSpaceStructureType.class);
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			result.put(type, new SpaceStructureProducer(type));
		}
		return Collections.unmodifiableMap(result);
	}

	private static Map<Dimension, Map<CoordinatesInWorld, List<GtnhStructureDescriptor>>>
			createSpaceCaches() {
		Map<Dimension, Map<CoordinatesInWorld, List<GtnhStructureDescriptor>>> result =
				new EnumMap<>(Dimension.class);
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			result.computeIfAbsent(type.getDimension(), ignored -> createFragmentCache());
		}
		return Collections.unmodifiableMap(result);
	}

	private static Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> createFragmentCache() {
		return Collections.synchronizedMap(
				new LinkedHashMap<CoordinatesInWorld, List<GtnhStructureDescriptor>>(32, 0.75F, true) {
					@Override
					protected boolean removeEldestEntry(
							Map.Entry<CoordinatesInWorld, List<GtnhStructureDescriptor>> eldest) {
						return size() > MAX_CACHED_FRAGMENTS;
					}
				});
	}

	private List<GtnhStructureDescriptor> getStructures(
			CoordinatesInWorld corner,
			Dimension dimension,
			boolean vanillaDungeonsOnly) {
		if (minecraftInterface == null) {
			return List.of();
		}
		Map<CoordinatesInWorld, List<GtnhStructureDescriptor>> selectedCache =
				vanillaDungeonsOnly
						? dungeonCache
						: spaceCaches.containsKey(dimension)
								? spaceCaches.get(dimension)
						: dimension == Dimension.NETHER
								? netherCache
						: dimension == Dimension.END
										? endCache
										: dimension == Dimension.MOON
												? moonCache
										: dimension == Dimension.TWILIGHT_FOREST
												? twilightForestCache
												: cache;
		List<GtnhStructureDescriptor> cached = selectedCache.get(corner);
		if (cached != null) {
			return cached;
		}
		int x = toMinecraftCoordinate(corner.getX());
		int z = toMinecraftCoordinate(corner.getY());
		try {
			List<GtnhStructureDescriptor> loaded = List.copyOf(
					vanillaDungeonsOnly
							? minecraftInterface.sampleVanillaDungeons(
									seed,
									dimension.getId(),
									x,
									z,
									Fragment.SIZE,
									Fragment.SIZE)
							: minecraftInterface.sampleStructures(
							seed,
							dimension,
							x,
							z,
							Fragment.SIZE,
							Fragment.SIZE));
			selectedCache.put(corner, loaded);
			return loaded;
		} catch (MinecraftInterfaceException e) {
			throw new IllegalStateException(
					"GTNH structure prediction failed for fragment " + corner,
					e);
		}
	}

	private static int toMinecraftCoordinate(long value) {
		if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			throw new IllegalArgumentException(
					"GTNH structure prediction is outside Minecraft's integer coordinate range");
		}
		return (int) value;
	}

	private final class TypeProducer extends WorldIconProducer<Void> {
		private final GtnhRoguelikeDungeonType type;

		private TypeProducer(GtnhRoguelikeDungeonType type) {
			this.type = type;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			for (GtnhStructureDescriptor structure :
					getStructures(corner, Dimension.OVERWORLD, false)) {
				if (!"ROGUELIKE_DUNGEON".equals(structure.kind())
						|| type != GtnhRoguelikeDungeonType.fromWireName(structure.subtype())) {
					continue;
				}
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						type.getIconLabel(),
						type.getIcon(),
						Dimension.OVERWORLD,
						false));
			}
		}
	}

	private final class OverworldStructureProducer extends WorldIconProducer<Void> {
		private final GtnhOverworldStructureType type;

		private OverworldStructureProducer(GtnhOverworldStructureType type) {
			this.type = type;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			if (type == GtnhOverworldStructureType.WORLD_SPAWN) {
				if (worldSpawn != null
						&& CoordinateUtils.isInBounds(
								worldSpawn.getX(),
								worldSpawn.getY(),
								corner.getX(),
								corner.getY(),
								Fragment.SIZE,
								Fragment.SIZE)) {
					consumer.accept(new WorldIcon(
							worldSpawn,
							type.getDisplayName(),
							type.getIcon(),
							Dimension.OVERWORLD,
							false));
				}
				return;
			}
			boolean vanillaDungeonsOnly =
					type == GtnhOverworldStructureType.VANILLA_SPAWNER_DUNGEON;
			for (GtnhStructureDescriptor structure :
					getStructures(corner, Dimension.OVERWORLD, vanillaDungeonsOnly)) {
				if (!type.getWireName().equals(structure.kind())) {
					continue;
				}
				String label = type == GtnhOverworldStructureType.VANILLA_SPAWNER_DUNGEON
						&& structure.subtype() != null
						&& !structure.subtype().isBlank()
								? type.getDisplayName() + " (Y " + structure.subtype() + ")"
								: type.getDisplayName();
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						label,
						type.getIcon(),
						Dimension.OVERWORLD,
						false));
			}
		}
	}

	private final class NetherStructureProducer extends WorldIconProducer<Void> {
		private final GtnhNetherStructureType type;

		private NetherStructureProducer(GtnhNetherStructureType type) {
			this.type = type;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			for (GtnhStructureDescriptor structure :
					getStructures(corner, Dimension.NETHER, false)) {
				if (!type.getWireName().equals(structure.kind())) {
					continue;
				}
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						type.getDisplayName(),
						type.getIcon(),
						// The switched Nether map uses native block
						// coordinates, not the legacy 1:8 overlay space.
						Dimension.OVERWORLD,
						false));
			}
		}
	}

	private final class EndStructureProducer extends WorldIconProducer<Void> {
		private final GtnhEndStructureType type;

		private EndStructureProducer(GtnhEndStructureType type) {
			this.type = type;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			for (GtnhStructureDescriptor structure :
					getStructures(corner, Dimension.END, false)) {
				if (!type.getWireName().equals(structure.kind())) {
					continue;
				}
				String label = type.getDisplayName();
				if (type == GtnhEndStructureType.HEE_BIOME_ISLAND
						&& structure.subtype() != null) {
					label = switch (structure.subtype()) {
						case "INFESTED_FOREST" -> "HEE Infested Forest Island";
						case "BURNING_MOUNTAINS" -> "HEE Burning Mountains Island";
						case "ENCHANTED_ISLAND" -> "HEE Enchanted Island";
						default -> label;
					};
				}
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						label,
						type.getIcon(structure.subtype()),
						Dimension.END,
						false));
			}
		}
	}

	private final class MoonStructureProducer extends WorldIconProducer<Void> {
		private final GtnhMoonStructureType type;

		private MoonStructureProducer(GtnhMoonStructureType type) {
			this.type = type;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			for (GtnhStructureDescriptor structure :
					getStructures(corner, Dimension.MOON, false)) {
				if (!type.getWireName().equals(structure.kind())) {
					continue;
				}
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						type.getDisplayName(),
						type.getIcon(),
						Dimension.MOON,
						false));
			}
		}
	}

	private final class SpaceStructureProducer extends WorldIconProducer<Void> {
		private final GtnhSpaceStructureType type;

		private SpaceStructureProducer(GtnhSpaceStructureType type) {
			this.type = type;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			for (GtnhStructureDescriptor structure :
					getStructures(corner, type.getDimension(), false)) {
				if (!type.getWireName().equals(structure.kind())) {
					continue;
				}
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						type.getDisplayName(),
						type.getIcon(),
						type.getDimension(),
						false));
			}
		}
	}

	private final class TwilightForestStructureProducer extends WorldIconProducer<Void> {
		private final GtnhTwilightForestFeatureType selectedType;

		private TwilightForestStructureProducer(
				GtnhTwilightForestFeatureType selectedType) {
			this.selectedType = selectedType;
		}

		@Override
		public void produce(
				CoordinatesInWorld corner,
				Consumer<WorldIcon> consumer,
				Void additionalData) {
			for (GtnhStructureDescriptor structure :
					getStructures(corner, Dimension.TWILIGHT_FOREST, false)) {
				if (!"TWILIGHT_FEATURE".equals(structure.kind())) {
					continue;
				}
				GtnhTwilightForestFeatureType type =
						GtnhTwilightForestFeatureType.fromWireName(structure.subtype());
				if (type != selectedType) {
					continue;
				}
				consumer.accept(new WorldIcon(
						CoordinatesInWorld.from(structure.x(), structure.z()),
						type.getDisplayName(),
						type.getIcon(),
						Dimension.TWILIGHT_FOREST,
						true));
			}
		}
	}
}
