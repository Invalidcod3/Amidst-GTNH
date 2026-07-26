package amidst.gtnh.structure;

import java.util.function.Consumer;

import amidst.documentation.Immutable;
import amidst.fragment.Fragment;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;
import amidst.util.FastRand;

/**
 * Minecraft 1.7.10 Nether-fortress predictor using native Nether block
 * coordinates for the dedicated GTNH Nether view.
 */
@Immutable
public final class GtnhNetherFortressProducer extends WorldIconProducer<Void> {
	private static final Resolution RESOLUTION = Resolution.CHUNK;
	private static final int MARKER_OFFSET = 11;
	private static final int REGION_SIZE_IN_CHUNKS = 16;
	private static final int INTERSECTING_REGION_CHUNKS =
			(RESOLUTION.getStepsPerFragment() + REGION_SIZE_IN_CHUNKS - 1)
					/ REGION_SIZE_IN_CHUNKS
					* REGION_SIZE_IN_CHUNKS;

	private final long seed;

	public GtnhNetherFortressProducer(long seed) {
		this.seed = seed;
	}

	@Override
	public void produce(
			CoordinatesInWorld corner,
			Consumer<WorldIcon> consumer,
			Void additionalData) {
		for (int xRelative = 0;
				xRelative <= INTERSECTING_REGION_CHUNKS;
				xRelative += REGION_SIZE_IN_CHUNKS) {
			for (int zRelative = 0;
					zRelative <= INTERSECTING_REGION_CHUNKS;
					zRelative += REGION_SIZE_IN_CHUNKS) {
				generateAt(corner, consumer, xRelative, zRelative);
			}
		}
	}

	private void generateAt(
			CoordinatesInWorld corner,
			Consumer<WorldIcon> consumer,
			int xRelative,
			int zRelative) {
		int chunkX = xRelative + (int) corner.getXAs(RESOLUTION);
		int chunkZ = zRelative + (int) corner.getYAs(RESOLUTION);
		int regionX = chunkX >> 4;
		int regionZ = chunkZ >> 4;
		FastRand random = new FastRand(regionX ^ regionZ << 4 ^ seed);
		random.advance();
		if (random.nextInt(3) != 0) {
			return;
		}

		int selectedChunkX = (regionX << 4) + 4 + random.nextInt(8);
		int selectedChunkZ = (regionZ << 4) + 4 + random.nextInt(8);
		CoordinatesInWorld coordinates = CoordinatesInWorld.from(
				(long) selectedChunkX * 16L + MARKER_OFFSET,
				(long) selectedChunkZ * 16L + MARKER_OFFSET);
		if (coordinates.isInBoundsOf(corner, Fragment.SIZE)) {
			/*
			 * The dedicated Nether map uses native Nether coordinates rather
			 * than the legacy 1:8 Overworld overlay coordinate space.
			 */
			consumer.accept(new WorldIcon(
					coordinates,
					DefaultWorldIconTypes.NETHER_FORTRESS.getLabel(),
					DefaultWorldIconTypes.NETHER_FORTRESS.getImage(),
					Dimension.OVERWORLD,
					false));
		}
	}
}
