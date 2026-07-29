package amidst.gtnh.export;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import amidst.fragment.Fragment;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;

public final class GtnhCoordinateLocator {
	public static final int MAX_FRAGMENT_QUERIES = 4096;

	private GtnhCoordinateLocator() {
	}

	public static List<GtnhCoordinate> locate(
			World world,
			GtnhCoordinateType type,
			int x1,
			int z1,
			int x2,
			int z2) {
		int minX = Math.min(x1, x2);
		int maxX = Math.max(x1, x2);
		int minZ = Math.min(z1, z2);
		int maxZ = Math.max(z1, z2);
		long firstFragmentX = Math.floorDiv(minX, Fragment.SIZE) * (long) Fragment.SIZE;
		long firstFragmentZ = Math.floorDiv(minZ, Fragment.SIZE) * (long) Fragment.SIZE;
		long lastFragmentX = Math.floorDiv(maxX, Fragment.SIZE) * (long) Fragment.SIZE;
		long lastFragmentZ = Math.floorDiv(maxZ, Fragment.SIZE) * (long) Fragment.SIZE;
		long columns = (lastFragmentX - firstFragmentX) / Fragment.SIZE + 1L;
		long rows = (lastFragmentZ - firstFragmentZ) / Fragment.SIZE + 1L;
		if (columns * rows > MAX_FRAGMENT_QUERIES) {
			throw new IllegalArgumentException(
					"The selected range needs "
							+ (columns * rows)
							+ " map queries; the safety limit is "
							+ MAX_FRAGMENT_QUERIES
							+ ". Reduce the export range.");
		}

		WorldIconProducer<Void> producer = type.getProducer(world);
		Map<String, GtnhCoordinate> unique = new LinkedHashMap<>();
		for (long fragmentZ = firstFragmentZ;
				fragmentZ <= lastFragmentZ;
				fragmentZ += Fragment.SIZE) {
			for (long fragmentX = firstFragmentX;
					fragmentX <= lastFragmentX;
					fragmentX += Fragment.SIZE) {
				if (Thread.currentThread().isInterrupted()) {
					throw new CancellationException("Coordinate export cancelled");
				}
				CoordinatesInWorld corner = CoordinatesInWorld.from(fragmentX, fragmentZ);
				List<WorldIcon> icons = new ArrayList<>();
				producer.produce(corner, icons::add, null);
				for (WorldIcon icon : icons) {
					long x = icon.getCoordinates().getX();
					long z = icon.getCoordinates().getY();
					if (x < minX || x > maxX || z < minZ || z > maxZ) {
						continue;
					}
					if (x < Integer.MIN_VALUE || x > Integer.MAX_VALUE
							|| z < Integer.MIN_VALUE || z > Integer.MAX_VALUE) {
						continue;
					}
					GtnhCoordinate coordinate =
							new GtnhCoordinate((int) x, (int) z, icon.getName());
					unique.putIfAbsent(x + "," + z, coordinate);
				}
			}
		}
		return unique.values().stream()
				.sorted(Comparator.comparingInt(GtnhCoordinate::x)
						.thenComparingInt(GtnhCoordinate::z))
				.toList();
	}
}
