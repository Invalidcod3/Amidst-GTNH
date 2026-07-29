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

	@Override
	public int[] chunkZs() {
		return chunkZs.clone();
	}
}
