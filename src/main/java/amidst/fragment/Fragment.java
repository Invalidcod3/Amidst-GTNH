package amidst.fragment;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.atomic.AtomicIntegerArray;
import amidst.fragment.layer.LayerIds;
import java.util.function.UnaryOperator;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.ThreadSafe;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.oracle.BiomeDataOracle;
import amidst.mojangapi.world.oracle.EndIsland;

/**
 * Reusable tile buffers with an explicit reservation lifecycle:
 * UNINITIALIZED (available) -> INITIALIZED (in graph) -> LOADING -> LOADED.
 *
 * The EDT assigns a corner only after taking an available fragment. Removing
 * the tile from the graph immediately sets recyclingRequested. The scheduler
 * discards pending work for it and loaders check cancellation between layers
 * and Worker requests. A running query may still write its reserved buffers.
 *
 * FragmentCache serializes retirement, reactivation and completion. A tile
 * leaving the graph keeps its completed layers in a bounded recent cache.
 * Returning to the exact same coordinate may adopt an in-flight task; reuse
 * at a DIFFERENT coordinate always waits for its writer to finish. Layer
 * revisions prevent resumed partial results from skipping invalidated data.
 *
 * Constructors initialize buffers before publication. Thereafter the reserved
 * loading task publishes complete images; the EDT can keep the last completed
 * picture during refresh. Alpha is updated by the drawing thread for fading.
 */
@ThreadSafe
public class Fragment {
	public static final int SIZE = Resolution.FRAGMENT.getStep();

	private final AtomicReference<State> state;
	private final AtomicBoolean biomeReloadRequested;
	private volatile CoordinatesInWorld corner;
	private volatile Dimension loadedDimension;
	private volatile boolean recyclingRequested;
	private volatile Dimension dataDimension;
	// Only the reserved loader writes these bits. Cache handoffs publish them after completion.
	private final BitSet completedLayers = new BitSet();
    private final long[] layerRevisions;
    private final AtomicIntegerArray publishedLayers;

	private volatile float alpha;
	private volatile short[][] biomeData;
	private volatile List<EndIsland> endIslands;
	private final AtomicReferenceArray<BufferedImage> images;
	private final AtomicReferenceArray<List<WorldIcon>> worldIcons;

    public Fragment(int numberOfLayers) {
        this.publishedLayers = new AtomicIntegerArray(numberOfLayers);
		this.layerRevisions = new long[numberOfLayers];
		this.state = new AtomicReference<State>(State.UNINITIALIZED);
		this.biomeReloadRequested = new AtomicBoolean();
		this.images = new AtomicReferenceArray<>(numberOfLayers);
		this.worldIcons = new AtomicReferenceArray<>(numberOfLayers);
	}

	public void setAlpha(float alpha) {
		this.alpha = alpha;
	}

	public float getAlpha() {
		return alpha;
	}

	public void initBiomeData(int width, int height) {
		biomeData = new short[width][height];
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void populateBiomeData(BiomeDataOracle biomeDataOracle) {
		int width = biomeData.length;
		int height = width == 0 ? 0 : biomeData[0].length;
		boolean populated = biomeDataOracle.getBiomeData(corner, width, height, true, data -> {
			for (int i = 0; i < width; i++) {
				for (int j = 0; j < height; j++) {
					biomeData[i][j] = (short) data[j * width + i];
				}
			}
			return true;
		}, () -> false);
		if (!populated) throw new IllegalStateException("Biome query did not populate fragment " + corner);
	}

	public short getBiomeDataAt(int x, int y) {
		return biomeData[x][y];
	}

	public void setEndIslands(List<EndIsland> endIslands) {
		this.endIslands = endIslands;
	}

	public List<EndIsland> getEndIslands() {
		return endIslands;
	}

	public BufferedImage getAndSetImage(int layerId, BufferedImage image) {
		return images.getAndSet(layerId, image);
	}

	public void putImage(int layerId, BufferedImage image) {
		images.set(layerId, image);
	}

	public BufferedImage getImage(int layerId) {
		return images.get(layerId);
	}

	public void putWorldIcons(int layerId, List<WorldIcon> icons) {
		worldIcons.set(layerId, icons);
	}

	public List<WorldIcon> getWorldIcons(int layerId) {
        if (loadedDimension != null && state.get() != State.UNINITIALIZED
                && (state.get() == State.LOADED || publishedLayers.get(layerId) != 0)) {
			List<WorldIcon> result = worldIcons.get(layerId);
			if (result != null) {
				return result;
			}
		}
		return Collections.emptyList();
	}

	public void setState(State state) {
		this.state.set(state);
	}

	public State getState() {
		return this.state.get();
	}

	public State getAndSetState(State state) {
		return this.state.getAndSet(state);
	}

	public State updateAndGetState(UnaryOperator<State> updateFunction) {
		return this.state.updateAndGet(updateFunction);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	// can only be recycled if it's not loading
	public boolean tryRecycle() {
		return this.state.updateAndGet(s -> s.equals(State.LOADING) ? State.LOADING : State.UNINITIALIZED).equals(State.UNINITIALIZED);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	// can only be recycled if it's not loading
	public boolean tryRecycleNotLoaded() {
		return this.state.updateAndGet(s -> s.equals(State.LOADING) || s.equals(State.LOADED) ? s : State.UNINITIALIZED).equals(State.UNINITIALIZED);
	}

    public void setCorner(CoordinatesInWorld corner) {
		this.corner = corner;
		this.recyclingRequested = false;
		this.loadedDimension = null;
		this.biomeReloadRequested.set(false);
		this.dataDimension = null;
        completedLayers.clear();
        clearPublishedLayers();
	}

	public void prepareForLoad(Dimension dimension) {
		if (dataDimension != dimension) {
			// Never publish a new dimension's layers under the old picture's
			// identity, including a rapid dimension switch during a refresh.
            loadedDimension = null;
            completedLayers.clear();
            clearPublishedLayers();
			dataDimension = dimension;
		}
	}

	public Dimension getDataDimension() {
		return dataDimension;
	}

	public boolean hasLayerData(int layerId) {
		return completedLayers.get(layerId);
	}

	public boolean hasAnyLayerData() {
		// ALPHA (layer 0) is only fade setup, not useful prediction work to retain.
		return completedLayers.nextSetBit(1) >= 0;
	}

    public void markLayerComplete(int layerId, long revision) {
		layerRevisions[layerId] = revision;
        completedLayers.set(layerId);
        publishedLayers.set(layerId, 1);
        // Publish the complete accurate image independently of later icons.
        // This volatile identity write also publishes the completed biome data.
        if (layerId == LayerIds.BACKGROUND) loadedDimension = dataDimension;
    }

    private void clearPublishedLayers() {
        for (int i = 0; i < publishedLayers.length(); i++) publishedLayers.set(i, 0);
    }

    public boolean hasDisplayLayer(Dimension dimension, int layerId) {
        return hasDisplayData(dimension) && (state.get() == State.LOADED || publishedLayers.get(layerId) != 0);
    }

	public long getLayerRevision(int layerId) {
		return layerRevisions[layerId];
	}

	public void invalidateLayerData(int layerId) {
		completedLayers.clear(layerId);
	}

	public void clearLayerData() {
		completedLayers.clear();
	}

	/** Rejoin the graph at the SAME coordinate; never change a running task's buffers. */
	public void cancelRecycling() {
		recyclingRequested = false;
	}

	/** Mark immediately on the EDT; actual reuse waits for the loading task to finish. */
	public void requestRecycling() {
		recyclingRequested = true;
	}

	public boolean isRecyclingRequested() {
		return recyclingRequested;
	}

	public void requestBiomeReload() {
		biomeReloadRequested.set(true);
	}

	public boolean getAndClearBiomeReloadRequested() {
		return biomeReloadRequested.getAndSet(false);
	}

	public boolean hasBiomeReloadRequested() {
		return biomeReloadRequested.get();
	}

	public CoordinatesInWorld getCorner() {
		return corner;
	}

	public void setLoadedDimension(Dimension loadedDimension) {
		this.loadedDimension = loadedDimension;
	}

	public Dimension getLoadedDimension() {
		return loadedDimension;
	}

	/** The last completed picture remains visible while this coordinate refreshes. */
	public boolean hasDisplayData(Dimension dimension) {
		return dimension == loadedDimension && loadedDimension != null && state.get() != State.UNINITIALIZED;
	}
	
	public static enum State {
		UNINITIALIZED,
		INITIALIZED,
		LOADING,
		LOADED;
	}
}
