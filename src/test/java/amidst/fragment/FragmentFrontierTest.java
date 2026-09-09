package amidst.fragment;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

public class FragmentFrontierTest {
	@Test
	public void followsExistingBoundaryInsteadOfJumpingToViewCenter() {
		Fragment edge = tile(512, 0), middle = tile(1024, 0), far = tile(1536, 0);
		List<Fragment> candidates = new ArrayList<>(List.of(far, middle, edge));
		FragmentFrontier frontier = new FragmentFrontier(view(), candidates, Map.of(at(0, 0), 4));
		candidates.sort(frontier::compare);
		assertEquals(List.of(edge, middle, far), candidates);
	}

	@Test
	public void closesConcaveHoleBeforeExtendingSingleNeighborEdge() {
		Fragment edge = tile(0, 0), hole = tile(1024, 512);
		FragmentFrontier frontier = new FragmentFrontier(view(), List.of(edge, hole),
				Map.of(at(0, -512), 4, at(1024, 0), 4, at(512, 512), 4, at(1536, 512), 4));
		assertTrue(frontier.compare(hole, edge) < 0);
	}

	@Test
	public void usesFourNeighborsAndInflightTilesAsWeakerSources() {
		Fragment edge = tile(512, 0), diagonal = tile(512, 512), speculative = tile(2048, 0);
		FragmentFrontier frontier = new FragmentFrontier(view(), List.of(edge, diagonal, speculative),
				Map.of(at(0, 0), 4, at(1536, 0), 1));
		assertTrue(frontier.compare(edge, diagonal) < 0);
		assertTrue(frontier.compare(diagonal, speculative) < 0);
		Fragment disconnected = tile(0, 1024);
		FragmentFrontier bootstrap = new FragmentFrontier(view(), List.of(disconnected, speculative), Map.of(at(1536, 0), 1));
		assertTrue(bootstrap.compare(speculative, disconnected) < 0);
	}

	@Test
	public void visibleDisconnectedRegionBeatsOffscreenFrontierAndStartsAtTopLeft() {
		Fragment buffer = tile(-512, 0), topLeft = tile(0, 0), middle = tile(1024, 512);
		FragmentFrontier frontier = new FragmentFrontier(view(), List.of(buffer, middle, topLeft), Map.of(at(-1024, 0), 4));
		assertTrue(frontier.compare(topLeft, buffer) < 0);
		assertTrue(frontier.compare(topLeft, middle) < 0);
	}

	private static FragmentViewport view() { return new FragmentViewport(at(0, 0), at(2560, 1536)); }
	private static CoordinatesInWorld at(int x, int z) { return CoordinatesInWorld.from(x, z); }
	private static Fragment tile(int x, int z) {
		Fragment fragment = new Fragment(4);
		fragment.setCorner(at(x, z));
		return fragment;
	}
}
