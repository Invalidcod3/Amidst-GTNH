package amidst.gtnh.worker;

import java.util.Objects;

import amidst.documentation.Immutable;

@Immutable
public record GtnhWorldState(
		long revision,
		boolean fullRefresh,
		int[] chunkXs,
		int[] chunkZs) {

	public GtnhWorldState {
		if (revision < 0) {
			throw new IllegalArgumentException("world revision must not be negative");
		}
		Objects.requireNonNull(chunkXs, "chunkXs");
		Objects.requireNonNull(chunkZs, "chunkZs");
		if (chunkXs.length != chunkZs.length) {
			throw new IllegalArgumentException("chunk coordinate arrays must have equal lengths");
		}
		chunkXs = chunkXs.clone();
		chunkZs = chunkZs.clone();
	}

	@Override
	public int[] chunkXs() {
		return chunkXs.clone();
	}

    /** A newly obtained spawn marker only invalidates its old/new tiles, not the entire map. */
    public GtnhWorldState withSpawnChange(
            amidst.mojangapi.world.coordinates.CoordinatesInWorld before,
            amidst.mojangapi.world.coordinates.CoordinatesInWorld after) {
        if (fullRefresh) return this;
        var positions = new java.util.LinkedHashSet<amidst.mojangapi.world.coordinates.CoordinatesInWorld>();
        if (before != null) positions.add(before);
        if (after != null) positions.add(after);
        int[] xs = java.util.Arrays.copyOf(chunkXs, chunkXs.length + positions.size());
        int[] zs = java.util.Arrays.copyOf(chunkZs, xs.length);
        int index = chunkXs.length;
        for (var point : positions) {
            xs[index] = Math.toIntExact(Math.floorDiv(point.getX(), 16));
            zs[index++] = Math.toIntExact(Math.floorDiv(point.getY(), 16));
        }
        return new GtnhWorldState(revision, false, xs, zs);
    }

	@Override
	public int[] chunkZs() {
		return chunkZs.clone();
	}
}
