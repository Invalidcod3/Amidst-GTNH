package amidst.fragment;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

/**
 * Multi-source weighted wavefront over the requested tiles' four-neighbor graph.
 * Completed data starts the wave; in-flight tiles are weaker, speculative sources.
 * This is rebuilt at dispatch, so moving the view never leaves an old heap order.
 */
public final class FragmentFrontier {
	public static final int COMPLETE_MARK = 4;
	public static final int IN_FLIGHT_MARK = 1;
	private static final int COMPLETE_START_COST = 0;
	private static final int IN_FLIGHT_START_COST = 2;
	private final FragmentViewport viewport;
	private final Map<CoordinatesInWorld, Integer> distance = new HashMap<>();
	private final Map<CoordinatesInWorld, Integer> neighborWeight = new HashMap<>();
	private record Wave(CoordinatesInWorld corner, int cost) implements Comparable<Wave> {
		@Override public int compareTo(Wave other) { return Integer.compare(cost, other.cost); }
	}

	public FragmentFrontier(FragmentViewport viewport, Collection<Fragment> candidates,
			Map<CoordinatesInWorld, Integer> marks) {
		this.viewport = viewport;
		Set<CoordinatesInWorld> requested = new HashSet<>();
		for (Fragment candidate : candidates) requested.add(candidate.getCorner());
		PriorityQueue<Wave> wave = new PriorityQueue<>();
		for (var entry : marks.entrySet()) {
			CoordinatesInWorld source = entry.getKey();
			int start = entry.getValue() == COMPLETE_MARK ? COMPLETE_START_COST : IN_FLIGHT_START_COST;
			if (requested.contains(source)) offer(wave, source, start);
			for (CoordinatesInWorld neighbor : neighbors(source)) {
				if (requested.contains(neighbor)) {
					offer(wave, neighbor, start + 1);
					neighborWeight.merge(neighbor, entry.getValue(), Integer::sum);
				}
			}
		}
		while (!wave.isEmpty()) {
			Wave next = wave.remove();
			if (next.cost() != distance.get(next.corner())) continue;
			for (CoordinatesInWorld neighbor : neighbors(next.corner())) {
				if (requested.contains(neighbor)) offer(wave, neighbor, next.cost() + 1);
			}
		}
	}

	private void offer(PriorityQueue<Wave> wave, CoordinatesInWorld corner, int cost) {
		if (cost < distance.getOrDefault(corner, Integer.MAX_VALUE)) {
			distance.put(corner, cost);
			wave.offer(new Wave(corner, cost));
		}
	}

	private static CoordinatesInWorld[] neighbors(CoordinatesInWorld corner) {
		return new CoordinatesInWorld[] {
				corner.add(-Fragment.SIZE, 0), corner.add(Fragment.SIZE, 0),
				corner.add(0, -Fragment.SIZE), corner.add(0, Fragment.SIZE)};
	}

	public int compare(Fragment first, Fragment second) {
		if (viewport != null) {
			int visible = Boolean.compare(viewport.isVisible(second), viewport.isVisible(first));
			if (visible != 0) return visible;
		}
		int wave = Integer.compare(distance.getOrDefault(first.getCorner(), Integer.MAX_VALUE),
				distance.getOrDefault(second.getCorner(), Integer.MAX_VALUE));
		if (wave != 0) return wave;
		// Prefer closing notches/holes with more already-completed neighbors.
		int neighbors = Integer.compare(neighborWeight.getOrDefault(second.getCorner(), 0),
				neighborWeight.getOrDefault(first.getCorner(), 0));
		if (neighbors != 0) return neighbors;
		// Stable edge sweep, also used to seed a completely disconnected/empty view.
		int row = Long.compare(first.getCorner().getY(), second.getCorner().getY());
		return row != 0 ? row : Long.compare(first.getCorner().getX(), second.getCorner().getX());
	}
}
