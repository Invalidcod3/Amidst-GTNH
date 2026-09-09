package amidst.gtnh.worker;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import amidst.mojangapi.world.Dimension;

public class GtnhCursorBiomeLookupTest {
	@Test
	public void exactBlockCoordinatesAreDebouncedAndCachedBetweenFrames() {
		AtomicLong time = new AtomicLong();
		Queue<Runnable> work = new ArrayDeque<>();
		GtnhCursorBiomeLookup lookup = new GtnhCursorBiomeLookup((dim, x, z) -> {
			assertEquals(-191, x);
			assertEquals(-240, z);
			return "Fen";
		}, work::add, time::get);
		assertNull(lookup.poll(Dimension.OVERWORLD, -191, -240));
		assertTrue(work.isEmpty());
		time.set(150_000_000L);
		assertNull(lookup.poll(Dimension.OVERWORLD, -191, -240));
		assertEquals(1, work.size());
		work.remove().run();
		assertEquals("Fen", lookup.poll(Dimension.OVERWORLD, -191, -240));
		for (int frame = 0; frame < 100; frame++) assertEquals("Fen", lookup.poll(Dimension.OVERWORLD, -191, -240));
		assertTrue(work.isEmpty());
	}

	@Test
	public void cursorMovementDoesNotQueueUnboundedWorkOrShowOldCoordinates() {
		AtomicLong time = new AtomicLong();
		Queue<Runnable> work = new ArrayDeque<>();
		GtnhCursorBiomeLookup lookup = new GtnhCursorBiomeLookup((dim, x, z) -> "biome " + x, work::add, time::get);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		time.set(150_000_000L);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		for (int x = 1; x <= 100; x++) {
			time.addAndGet(200_000_000L);
			assertNull(lookup.poll(Dimension.OVERWORLD, x, 0));
		}
		assertEquals(1, work.size());
		work.remove().run();
		time.addAndGet(200_000_000L);
		assertNull(lookup.poll(Dimension.OVERWORLD, 100, 0));
		assertEquals(1, work.size());
		work.remove().run();
		assertEquals("biome 100", lookup.poll(Dimension.OVERWORLD, 100, 0));
	}

	@Test
	public void worldChangesInvalidateInFlightResultsAndClosingStopsQueries() {
		AtomicLong time = new AtomicLong();
		Queue<Runnable> work = new ArrayDeque<>();
		GtnhCursorBiomeLookup lookup = new GtnhCursorBiomeLookup((dim, x, z) -> "changed", work::add, time::get);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		time.set(150_000_000L);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		lookup.invalidate();
		work.remove().run();
		assertNull(lookup.poll(Dimension.OVERWORLD, 0, 0));
		lookup.close();
		work.remove().run();
		assertNull(lookup.poll(Dimension.OVERWORLD, 0, 0));
		assertTrue(work.isEmpty());
	}

	@Test
	public void offlineFailuresBackOffWithoutThrowingIntoRendering() {
		AtomicLong time = new AtomicLong();
		Queue<Runnable> work = new ArrayDeque<>();
		GtnhCursorBiomeLookup lookup = new GtnhCursorBiomeLookup((dim, x, z) -> { throw new Exception("offline"); }, work::add, time::get);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		time.set(150_000_000L);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		work.remove().run();
		assertNull(lookup.poll(Dimension.OVERWORLD, 0, 0));
		time.addAndGet(1_000_000_000L);
		assertNull(lookup.poll(Dimension.OVERWORLD, 0, 0));
		assertTrue(work.isEmpty());
		time.addAndGet(5_000_000_000L);
		lookup.poll(Dimension.OVERWORLD, 0, 0);
		assertEquals(1, work.size());
	}
}
