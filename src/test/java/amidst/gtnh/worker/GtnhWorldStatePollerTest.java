package amidst.gtnh.worker;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;

public class GtnhWorldStatePollerTest {
	@Test(timeout = 5000)
	public void slowPollDoesNotBlockOrSubmitDuplicatesAndChangesKeepTheirRevision() {
		ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		AtomicLong clock = new AtomicLong(1);
		List<Long> revisions = new ArrayList<>();
		GtnhWorldStatePoller poller = new GtnhWorldStatePoller(since -> {
			revisions.add(since);
			return new GtnhWorldState(since + 2, false, new int[] {7}, new int[] {-9});
		}, tasks::add, clock::get);
		for (int i = 0; i < 100; i++) assertNull(poller.poll());
		assertEquals(1, tasks.size());
		tasks.remove().run();
		assertTrue(poller.poll().fullRefresh()); // Include tiles loaded before the asynchronous baseline.
		clock.addAndGet(1_000_000_000L);
		assertNull(poller.poll());
		tasks.remove().run();
		GtnhWorldState change = poller.poll();
		assertEquals(List.of(-1L, 1L), revisions);
		assertEquals(3L, change.revision());
		assertArrayEquals(new int[] {7}, change.chunkXs());
		assertArrayEquals(new int[] {-9}, change.chunkZs());
	}

	@Test(timeout = 5000)
	public void failedPollBacksOffAndClosedPollerDiscardsPendingWork() {
		ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		AtomicLong clock = new AtomicLong(1);
		GtnhWorldStatePoller poller = new GtnhWorldStatePoller(since -> {
			throw new MinecraftInterfaceException("test failure");
		}, tasks::add, clock::get);
		poller.poll();
		tasks.remove().run();
		assertNull(poller.poll());
		clock.addAndGet(4_999_999_999L);
		assertNull(poller.poll());
		assertTrue(tasks.isEmpty());
		clock.incrementAndGet();
		poller.poll();
		assertEquals(1, tasks.size());
		poller.close();
		tasks.remove().run();
		assertNull(poller.poll());
		assertTrue(tasks.isEmpty());
	}
}
