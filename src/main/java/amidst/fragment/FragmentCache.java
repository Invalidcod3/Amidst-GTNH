package amidst.fragment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

import amidst.documentation.ThreadSafe;
import amidst.fragment.constructor.FragmentConstructor;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

/**
 * Owns the pool and bounded recent-result cache. All ownership handoffs use this
 * monitor, including completion vs. an EDT request to bring a retired tile back.
 * Retained tiles keep their original buffers; no image or biome-array copies.
 */
@ThreadSafe
public class FragmentCache {
	private static final int NEW_FRAGMENTS_PER_REQUEST = 1024;
	private static final int DEFAULT_RETAINED_TILES = 256;
	private final List<Fragment> cache = new LinkedList<>();
	// Last released is most recently used. Active graph tiles do not consume this limit.
	private final LinkedHashMap<Key, Fragment> retained = new LinkedHashMap<>();
	private final Map<Key, Fragment> retiring = new HashMap<>();
	private final ConcurrentLinkedQueue<Fragment> availableQueue;
	private final ConcurrentLinkedQueue<Fragment> loadingQueue;
	private final Iterable<FragmentConstructor> constructors;
	private final int numberOfLayers;
	private final int retainedLimit;
	private long cacheHits, evictions;

	private record Key(Dimension dimension, CoordinatesInWorld corner) {
		static Key of(Fragment fragment) {
			return new Key(fragment.getDataDimension(), fragment.getCorner());
		}
	}

	public FragmentCache(ConcurrentLinkedQueue<Fragment> availableQueue,
			ConcurrentLinkedQueue<Fragment> loadingQueue,
			Iterable<FragmentConstructor> constructors, int numberOfLayers) {
		this(availableQueue, loadingQueue, constructors, numberOfLayers,
				Math.max(0, Math.min(1024, Integer.getInteger("amidst.fragment.retainedTiles", DEFAULT_RETAINED_TILES))));
	}

	FragmentCache(ConcurrentLinkedQueue<Fragment> availableQueue,
			ConcurrentLinkedQueue<Fragment> loadingQueue,
			Iterable<FragmentConstructor> constructors, int numberOfLayers, int retainedLimit) {
		this.availableQueue = availableQueue;
		this.loadingQueue = loadingQueue;
		this.constructors = constructors;
		this.numberOfLayers = numberOfLayers;
		this.retainedLimit = retainedLimit;
	}

	public synchronized Fragment requestFragment(CoordinatesInWorld corner, Dimension dimension) {
		Key key = new Key(dimension, corner);
		Fragment fragment = retained.remove(key);
		if (fragment == null) fragment = retiring.remove(key);
		if (fragment != null) {
			cacheHits++;
			fragment.cancelRecycling();
			if (fragment.getState() == Fragment.State.LOADED && !fragment.hasBiomeReloadRequested()) {
				fragment.setAlpha(1.0f);
			} else if (fragment.getState() != Fragment.State.LOADING) {
				fragment.setState(Fragment.State.INITIALIZED);
				loadingQueue.offer(fragment);
			}
			// A still-running task may be adopted only at its exact coordinate/dimension.
			// Its completion will publish or requeue it under this same monitor.
			return fragment;
		}
		while ((fragment = availableQueue.poll()) == null) increaseSize();
		fragment.setCorner(corner);
		fragment.prepareForLoad(dimension);
		fragment.setState(Fragment.State.INITIALIZED);
		loadingQueue.offer(fragment);
		return fragment;
	}

	public synchronized void requestRecycling(Fragment fragment) {
		fragment.requestRecycling();
		retiring.put(Key.of(fragment), fragment);
	}

	/** False means its writer is still active; keep the recycle notification. */
	public synchronized boolean retire(Fragment fragment) {
		if (!fragment.isRecyclingRequested()) return true; // Adopted back into the graph.
		while (loadingQueue.remove(fragment)) { /* discard duplicate pending notifications */ }
		if (fragment.getState() == Fragment.State.LOADING) return false;
		Key key = Key.of(fragment);
		retiring.remove(key, fragment);
		if (retainedLimit > 0 && fragment.hasAnyLayerData() && !fragment.hasBiomeReloadRequested()) {
			Fragment previous = retained.remove(key);
			if (previous != null && previous != fragment) release(previous);
			retained.put(key, fragment);
			while (retained.size() > retainedLimit) {
				var oldest = retained.entrySet().iterator();
				Fragment victim = oldest.next().getValue();
				oldest.remove();
				release(victim);
				evictions++;
			}
		} else {
			release(fragment);
		}
		return true;
	}

	private void release(Fragment fragment) {
		if (!fragment.tryRecycle()) throw new IllegalStateException("Cannot release a reserved fragment");
		availableQueue.offer(fragment);
	}

	public synchronized void completeLoad(Fragment fragment, Dimension dimension, boolean succeeded,
			boolean cancelled, Dimension currentDimension) {
		boolean wanted = !fragment.isRecyclingRequested();
		if (succeeded && dimension == currentDimension) {
			fragment.setLoadedDimension(dimension);
			fragment.setState(Fragment.State.LOADED);
			if (wanted && fragment.hasBiomeReloadRequested()) loadingQueue.offer(fragment);
		} else {
			fragment.setState(Fragment.State.INITIALIZED);
			if (wanted && (cancelled || dimension != currentDimension)) loadingQueue.offer(fragment);
		}
	}

	public synchronized void increaseSize() {
		AmidstLogger.info("increasing fragment cache size from {} to {}", cache.size(), cache.size() + NEW_FRAGMENTS_PER_REQUEST);
		for (int i = 0; i < NEW_FRAGMENTS_PER_REQUEST; i++) {
			Fragment fragment = new Fragment(numberOfLayers);
			for (FragmentConstructor constructor : constructors) constructor.construct(fragment);
			cache.add(fragment);
			availableQueue.offer(fragment);
		}
	}

	/** A style/layer/dimension change invalidates retained results conservatively. */
	public synchronized void reloadAll() {
		evictRetained(null);
		loadingQueue.clear();
		for (Fragment fragment : cache) {
			Fragment.State state = fragment.getState();
			if (state == Fragment.State.INITIALIZED || state == Fragment.State.LOADED) {
				if (fragment.isRecyclingRequested()) {
					// These are outside the graph and will miss this refresh batch.
					fragment.clearLayerData();
					fragment.setState(Fragment.State.INITIALIZED);
				} else {
					loadingQueue.offer(fragment);
				}
			}
		}
	}

	public synchronized void reloadBiomeChunks(int[] chunkXs, int[] chunkZs) {
		if (chunkXs.length != chunkZs.length) throw new IllegalArgumentException("chunk coordinate arrays must have equal lengths");
		Set<CoordinatesInWorld> affected = new HashSet<>();
		for (int i = 0; i < chunkXs.length; i++) {
			affected.add(CoordinatesInWorld.from((long) chunkXs[i] * 16L, (long) chunkZs[i] * 16L).toFragmentCorner());
		}
		evictRetained(affected);
		for (Fragment fragment : cache) {
			if (fragment.getState() != Fragment.State.UNINITIALIZED && affected.contains(fragment.getCorner())) {
				// Mark retired in-flight queries too: they must not later enter the cache stale.
				fragment.requestBiomeReload();
				if (!fragment.isRecyclingRequested()) loadingQueue.offer(fragment);
			}
		}
	}

	public synchronized void reloadBiomesAllUsed() {
		evictRetained(null);
		for (Fragment fragment : cache) {
			if (fragment.getState() != Fragment.State.UNINITIALIZED) {
				fragment.requestBiomeReload();
				if (!fragment.isRecyclingRequested()) loadingQueue.offer(fragment);
			}
		}
	}

	private void evictRetained(Set<CoordinatesInWorld> affected) {
		var entries = retained.entrySet().iterator();
		while (entries.hasNext()) {
			var entry = entries.next();
			if (affected == null || affected.contains(entry.getKey().corner())) {
				Fragment fragment = entry.getValue();
				entries.remove();
				release(fragment);
				evictions++;
			}
		}
	}

	/** Completed tiles (including retained neighbors) are stronger sources than in-flight tiles. */
	public synchronized Map<CoordinatesInWorld, Integer> frontierMarks(Dimension dimension) {
		Map<CoordinatesInWorld, Integer> marks = new HashMap<>();
		for (Fragment fragment : cache) {
			if (fragment.getDataDimension() != dimension || fragment.hasBiomeReloadRequested()) continue;
			if (fragment.getState() == Fragment.State.LOADED) {
				marks.put(fragment.getCorner(), FragmentFrontier.COMPLETE_MARK);
			} else if (fragment.getState() == Fragment.State.LOADING && !fragment.isRecyclingRequested()) {
				marks.merge(fragment.getCorner(), FragmentFrontier.IN_FLIGHT_MARK, Math::max);
			}
		}
		return marks;
	}

	public synchronized int retainedSize() { return retained.size(); }
	public synchronized long cacheHits() { return cacheHits; }
	public synchronized long evictions() { return evictions; }

	public synchronized void clear() {
		AmidstLogger.info("fragment cache cleared");
		retained.clear();
		retiring.clear();
		cache.clear();
		cacheHits = 0;
		evictions = 0;
	}

	public synchronized int size() { return cache.size(); }
}
