package amidst.fragment;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.junit.Test;

import amidst.fragment.layer.LayerDeclaration;
import amidst.fragment.layer.LayerLoader;
import amidst.fragment.layer.LayerManager;
import amidst.fragment.loader.FragmentLoader;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.settings.Setting;

public class FragmentQueueProcessorTest {
	@Test(timeout = 5000)
	public void dispatchIsBoundedEvenBeforeExecutorStartsAnyThread() {
		Fixture f = new Fixture(2);
		for (int i = 0; i < 100; i++) f.request(i * 512, 0);
		f.processor.processQueues();
		assertEquals(2, f.executor.tasks.size());
		assertEquals(2, f.processor.getActiveLoadCount());
		assertEquals(98, f.loading.size());
		for (int i = 0; i < 50; i++) f.processor.processQueues();
		assertEquals(2, f.executor.tasks.size());
	}

	@Test(timeout = 5000)
	public void blankViewStartsAtVisibleEdgeInsteadOfCenter() {
		Fixture f = new Fixture(1);
		Fragment buffer = f.request(4608, 512);
		Fragment edge = f.request(0, 0);
		Fragment center = f.request(4608, 0);
		f.view(0, 0, 10000, 512);
		f.drain();
		assertEquals(List.of(edge, center, buffer), f.firstLoads);
	}

	@Test(timeout = 5000)
	public void panDropsQueuedTilesAndReprioritizesWithoutCancellingOverlap() {
		Fixture f = new Fixture(1);
		Fragment old = f.request(0, 0);
		Fragment queuedOld = f.request(512, 0);
		Fragment overlap = f.request(1024, 0);
		f.view(0, 0, 512, 512);
		f.processor.processQueues();
		f.recycle(old);
		f.recycle(queuedOld);
		Fragment newCenter = f.request(2048, 0);
		f.view(1792, 0, 2816, 512);
		f.processor.processQueues();
		assertEquals(Fragment.State.LOADING, old.getState());
		assertFalse(f.available.contains(old));
		assertTrue(f.available.contains(queuedOld));
		f.executor.runNext(); // Cancelled before entering its first loader.
		f.drain();
		assertEquals(List.of(newCenter, overlap), f.firstLoads);
		assertEquals(Fragment.State.UNINITIALIZED, old.getState());
		assertTrue(f.available.contains(old));
	}

	@Test(timeout = 5000)
	public void panDuringQuerySkipsRemainingLayersAndDefersReuseUntilWriterFinishes() throws Exception {
		Fixture f = new Fixture(1);
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Fragment old = f.request(0, 0);
		f.firstAction = (dimension, fragment) -> {
			if (fragment == old) {
				entered.countDown();
				await(release);
				fragment.setAlpha(0.75f); // Simulate a query still writing the reserved buffer.
			}
		};
		f.processor.processQueues();
		ExecutorService running = Executors.newSingleThreadExecutor();
		try {
			Future<?> load = running.submit(f.executor.tasks.remove());
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			f.recycle(old);
			f.recycle(old); // Duplicate notification must not duplicate the available buffer.
			Fragment next = f.request(4096, 0);
			f.view(4096, 0, 4608, 512);
			f.processor.processQueues(); // Must return even though the old query is blocked.
			assertFalse(f.available.contains(old));
			assertEquals(Fragment.State.LOADING, old.getState());
			release.countDown();
			load.get(2, TimeUnit.SECONDS);
			f.drain();
			assertEquals(List.of(next), f.secondLoads);
			assertEquals(0.75f, old.getAlpha(), 0);
			assertFalse(f.available.contains(old));
			assertEquals(1, f.cache.retainedSize());
			assertEquals(Fragment.State.INITIALIZED, old.getState());
			assertSame(old, f.request(0, 0));
			f.drain();
			assertEquals(List.of(old, next), f.firstLoads); // Resume without repeating the completed query.
			assertEquals(List.of(next, old), f.secondLoads);
		} finally {
			release.countDown();
			running.shutdownNow();
		}
	}

	@Test(timeout = 5000)
	public void dimensionSwitchDoesNotPublishOrReuseOldDimensionWork() {
		Fixture f = new Fixture(1);
		Fragment fragment = f.request(0, 0);
		f.processor.processQueues();
		f.dimension.set(Dimension.NETHER);
		f.processor.processQueues();
		assertEquals(1, f.executor.tasks.size());
		f.executor.runNext();
		f.drain();
		assertEquals(List.of(Dimension.NETHER), f.dimensions);
		assertEquals(Dimension.NETHER, fragment.getLoadedDimension());
		assertEquals(Fragment.State.LOADED, fragment.getState());
	}

	@Test(timeout = 5000)
	public void chunkReloadDuringLoadIsNotLostOrRunConcurrently() {
		Fixture f = new Fixture(1);
		Fragment fragment = f.request(0, 0);
		f.processor.processQueues();
		f.processor.requestBiomeReloadForChunks(new int[] {0}, new int[] {0});
		f.processor.requestBiomeReloadForChunks(new int[] {0}, new int[] {0});
		f.processor.processQueues();
		assertEquals(1, f.executor.tasks.size());
		f.drain();
		assertEquals(List.of(fragment, fragment), f.firstLoads);
		assertEquals(List.of(fragment, fragment), f.secondLoads);
		assertFalse(fragment.hasBiomeReloadRequested());
		assertEquals(0, f.processor.getActiveLoadCount());
	}

	@Test(timeout = 5000)
	public void failureReleasesReservationAndDisposedTasksCannotRepopulateSharedQueues() {
		Fixture f = new Fixture(1);
		Fragment failed = f.request(0, 0);
		f.firstAction = (dimension, fragment) -> { throw new IllegalStateException("test loader failure"); };
		f.drain();
		assertEquals(0, f.processor.getActiveLoadCount());
		assertEquals(Fragment.State.INITIALIZED, failed.getState());
		f.recycle(failed);
		f.processor.processQueues();
		assertTrue(f.available.contains(failed));
		f.firstAction = (dimension, fragment) -> { };
		f.request(1024, 0);
		f.processor.processQueues();
		f.processor.dispose();
		f.available.clear();
		f.loading.clear();
		f.recycle.clear();
		f.executor.runNext();
		f.processor.processQueues();
		assertTrue(f.available.isEmpty());
		assertTrue(f.loading.isEmpty());
		assertTrue(f.recycle.isEmpty());
	}

	@Test(timeout = 5000)
	public void managerMarksRecyclingImmediately() {
		FragmentManager manager = new FragmentManager(List.of(), 4, Setting.createImmutable(1));
		Fragment fragment = manager.requestFragment(CoordinatesInWorld.origin());
		manager.recycleFragment(fragment);
		assertTrue(fragment.isRecyclingRequested());
		assertEquals(Fragment.State.INITIALIZED, fragment.getState());
		manager.clear();
	}

	@Test(timeout = 5000)
	public void completedTileReturnsImmediatelyWithItsOriginalImageAndNoNewLoads() {
		Fixture f = new Fixture(1);
		Fragment fragment = f.request(-512, 1024);
		var image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, 0xff123456);
		fragment.putImage(3, image);
		f.drain();
		f.recycle(fragment);
		f.processor.processQueues();
		assertEquals(1, f.cache.retainedSize());
		assertSame(fragment, f.request(-512, 1024));
		assertEquals(Fragment.State.LOADED, fragment.getState());
		assertSame(image, fragment.getImage(3));
		assertEquals(0xff123456, fragment.getImage(3).getRGB(0, 0));
		f.drain();
		assertEquals(List.of(fragment), f.firstLoads);
		assertEquals(1, f.cache.cacheHits());
	}

	@Test(timeout = 5000)
	public void returningWhileQueryIsRunningAdoptsSameReservationAndSurvivesOldRecycleNotification() throws Exception {
		Fixture f = new Fixture(1);
		Fragment fragment = f.request(0, 0);
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		f.firstAction = (dimension, tile) -> { entered.countDown(); await(release); };
		f.processor.processQueues();
		ExecutorService running = Executors.newSingleThreadExecutor();
		try {
			Future<?> load = running.submit(f.executor.tasks.remove());
			assertTrue(entered.await(2, TimeUnit.SECONDS));
			f.recycle(fragment);
			f.processor.processQueues();
			assertSame(fragment, f.request(0, 0));
			f.processor.processQueues();
			assertEquals(1, f.processor.getActiveLoadCount());
			assertFalse(fragment.isRecyclingRequested());
			release.countDown();
			load.get(2, TimeUnit.SECONDS);
			f.drain();
			assertEquals(List.of(fragment), f.firstLoads);
			assertEquals(List.of(fragment), f.secondLoads);
			assertEquals(Fragment.State.LOADED, fragment.getState());
			assertEquals(0, f.cache.retainedSize());
		} finally {
			release.countDown();
			running.shutdownNow();
		}
	}

	@Test(timeout = 5000)
	public void retainedCacheEvictsLeastRecentlyUsedTileAtCapacity() {
		Fixture f = new Fixture(1, 2);
		Fragment a = f.request(0, 0), b = f.request(512, 0), c = f.request(1024, 0);
		f.drain();
		f.recycle(a); f.recycle(b); f.processor.processQueues();
		assertSame(a, f.request(0, 0));
		f.recycle(a); f.processor.processQueues(); // A is now more recent than B.
		f.recycle(c); f.processor.processQueues();
		assertEquals(2, f.cache.retainedSize());
		assertEquals(1, f.cache.evictions());
		assertEquals(Fragment.State.UNINITIALIZED, b.getState());
		assertTrue(f.available.contains(b));
		assertSame(a, f.request(0, 0));
		assertSame(c, f.request(1024, 0));
		f.drain();
		assertEquals(3, f.firstLoads.size());
	}

	@Test(timeout = 5000)
	public void chunkUpdatesEvictOnlyAffectedRetainedResults() {
		Fixture f = new Fixture(1);
		Fragment a = f.request(0, 0), b = f.request(512, 0);
		f.drain();
		f.recycle(a); f.recycle(b); f.processor.processQueues();
		f.processor.requestBiomeReloadForChunks(new int[] {0}, new int[] {0});
		assertEquals(1, f.cache.retainedSize());
		assertEquals(Fragment.State.UNINITIALIZED, a.getState());
		assertSame(b, f.request(512, 0));
		Fragment fresh = f.request(0, 0);
		f.drain();
		assertEquals(3, f.firstLoads.size());
		assertEquals(Fragment.State.LOADED, fresh.getState());
	}

	@Test(timeout = 5000)
	public void chunkChangeDuringOffscreenLoadPreventsStaleResultEnteringCache() {
		Fixture f = new Fixture(1);
		Fragment old = f.request(0, 0);
		f.firstAction = (dimension, fragment) -> {
			f.recycle(fragment);
			f.processor.requestBiomeReloadForChunks(new int[] {0}, new int[] {0});
		};
		f.processor.processQueues();
		f.executor.runNext();
		f.processor.processQueues();
		assertEquals(0, f.cache.retainedSize());
		assertEquals(Fragment.State.UNINITIALIZED, old.getState());
	}

	@Test(timeout = 5000)
	public void dimensionStyleAndWorldChangesInvalidateRetainedResults() {
		Fixture f = new Fixture(1);
		Fragment overworld = f.request(0, 0);
		f.drain(); f.recycle(overworld); f.processor.processQueues();
		f.dimension.set(Dimension.NETHER);
		Fragment nether = f.request(0, 0);
		assertNotSame(overworld, nether);
		f.drain();
		assertEquals(Dimension.NETHER, nether.getLoadedDimension());
		assertEquals(0, f.cache.retainedSize());
		f.recycle(nether); f.processor.processQueues();
		f.layers.invalidateLayer(3);
		f.processor.processQueues();
		assertEquals(0, f.cache.retainedSize());
		f.cache.clear();
		assertEquals(0, f.cache.size());
		assertEquals(0, f.cache.cacheHits());
	}

	@Test(timeout = 5000)
	public void layerRevisionRefreshesChangedImageWithoutRepeatingUnchangedBiomeQuery() {
		Fixture f = new Fixture(1);
		Fragment fragment = f.request(0, 0);
		f.drain();
		f.layers.invalidateLayer(3);
		f.drain();
		assertEquals(List.of(fragment), f.firstLoads);
		assertEquals(List.of(fragment, fragment), f.secondLoads);
	}

	@Test(timeout = 5000)
	public void returningDuringInvalidationOfPendingRetirementCannotShowStaleLoadedTile() {
		Fixture f = new Fixture(1);
		Fragment fragment = f.request(0, 0);
		f.drain();
		f.recycle(fragment);
		// Simulate an invalidation after the tick drained its recycle queue, but
		// before the new retirement notification has been processed.
		f.cache.reloadAll();
		assertSame(fragment, f.request(0, 0));
		assertEquals(Fragment.State.INITIALIZED, fragment.getState());
		f.drain();
		assertEquals(List.of(fragment, fragment), f.firstLoads);
		assertEquals(Fragment.State.LOADED, fragment.getState());
	}

	@Test(timeout = 5000)
	public void cacheCanBeDisabledAndFadeSetupAloneIsNotRetained() {
		Fixture f = new Fixture(1, 0);
		Fragment completed = f.request(0, 0);
		f.drain(); f.recycle(completed); f.processor.processQueues();
		assertEquals(0, f.cache.retainedSize());
		assertTrue(f.available.contains(completed));
		Fixture enabled = new Fixture(1);
		Fragment fadeOnly = enabled.request(0, 0);
		fadeOnly.markLayerComplete(0, 0);
		enabled.recycle(fadeOnly); enabled.processor.processQueues();
		assertEquals(0, enabled.cache.retainedSize());
		assertTrue(enabled.available.contains(fadeOnly));
	}

	@Test(timeout = 5000)
	public void failedBiomeFallbackCannotBeMistakenForCompletedPrediction() {
		Fragment fragment = new Fragment(4);
		fragment.initBiomeData(2, 2);
		fragment.setCorner(CoordinatesInWorld.origin());
		var oracle = new amidst.mojangapi.world.oracle.BiomeDataOracle(null, Dimension.OVERWORLD, null,
				new amidst.mojangapi.world.oracle.BiomeDataOracle.Config()) {
			@Override public <T> T getBiomeData(CoordinatesInWorld corner, int width, int height, boolean quarter,
					java.util.function.Function<int[], T> mapper, java.util.function.Supplier<T> fallback) {
				return fallback.get();
			}
		};
		assertThrows(IllegalStateException.class, () -> fragment.populateBiomeData(oracle));
		assertFalse(fragment.hasAnyLayerData());
	}

	private static void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(2, TimeUnit.SECONDS));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	private static final class ManualExecutor extends ThreadPoolExecutor {
		final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
		ManualExecutor() { super(8, 8, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>()); }
		@Override public void execute(Runnable task) { tasks.add(task); }
		void runNext() { tasks.remove().run(); }
	}

	private static final class Fixture {
		final ConcurrentLinkedQueue<Fragment> available = new ConcurrentLinkedQueue<>();
		final ConcurrentLinkedQueue<Fragment> loading = new ConcurrentLinkedQueue<>();
		final ConcurrentLinkedQueue<Fragment> recycle = new ConcurrentLinkedQueue<>();
		final ManualExecutor executor = new ManualExecutor();
		final Setting<Dimension> dimension = Setting.createDummy(Dimension.OVERWORLD);
		final List<Fragment> firstLoads = new ArrayList<>();
		final List<Fragment> secondLoads = new ArrayList<>();
		final List<Dimension> dimensions = new ArrayList<>();
		BiConsumer<Dimension, Fragment> firstAction = (dimension, fragment) -> { };
		FragmentViewport viewport;
		final FragmentQueueProcessor processor;
		final FragmentCache cache;
		final LayerManager layers;

		Fixture(int limit) {
			this(limit, 256);
		}

		Fixture(int limit, int retainedLimit) {
			cache = new FragmentCache(available, loading, List.of(), 4, retainedLimit);
			cache.increaseSize();
			LayerDeclaration first = new LayerDeclaration(1, null, false, true, Setting.createImmutable(true));
			LayerDeclaration second = new LayerDeclaration(3, null, false, true, Setting.createImmutable(true));
			LayerLoader loaders = new LayerLoader(List.of(
					loader(first, (dimension, fragment) -> {
						firstLoads.add(fragment);
						dimensions.add(dimension);
						firstAction.accept(dimension, fragment);
					}), loader(second, (dimension, fragment) -> secondLoads.add(fragment))), 4);
			layers = new LayerManager(List.of(first, second), loaders, List.of());
			processor = new FragmentQueueProcessor(loading, recycle, cache, layers,
					executor, dimension, () -> viewport, limit);
		}

		Fragment request(int x, int z) {
			return cache.requestFragment(CoordinatesInWorld.from(x, z), dimension.get());
		}
		void recycle(Fragment fragment) { cache.requestRecycling(fragment); recycle.offer(fragment); }
		void view(int left, int top, int right, int bottom) {
			viewport = new FragmentViewport(CoordinatesInWorld.from(left, top), CoordinatesInWorld.from(right, bottom));
		}
		void drain() {
			processor.processQueues();
			for (int guard = 0; guard < 1000; guard++) {
				if (!executor.tasks.isEmpty()) executor.runNext();
				processor.processQueues();
				if (executor.tasks.isEmpty() && loading.isEmpty() && processor.getActiveLoadCount() == 0) return;
			}
			fail("Scheduler did not become idle");
		}
		static FragmentLoader loader(LayerDeclaration declaration, BiConsumer<Dimension, Fragment> action) {
			return new FragmentLoader(declaration) {
				@Override public void load(Dimension dimension, Fragment fragment) { action.accept(dimension, fragment); }
				@Override public void reload(Dimension dimension, Fragment fragment) { load(dimension, fragment); }
			};
		}
	}
}
