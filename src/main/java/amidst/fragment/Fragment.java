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
    private final AtomicBoolean fullReloadRequested = new AtomicBoolean();
	private volatile CoordinatesInWorld corner;
	private volatile Dimension loadedDimension;
	private volatile boolean recyclingRequested;
	private volatile Dimension dataDimension;
	// Only the reserved loader writes these bits. Cache handoffs publish them after completion.
	private final BitSet completedLayers = new BitSet();
    private final long[] layerRevisions;
    private final AtomicIntegerArray publishedLayers;
    private volatile boolean synchronizedPublication;
    private volatile DisplaySnapshot displaySnapshot;
    private BitSet requiredLayers = new BitSet();
    private static final DisplaySnapshot EMPTY_DISPLAY = new DisplaySnapshot(null, null,
            new BufferedImage[0], List.of(), new BitSet());
    private final ThreadLocal<DisplaySnapshot> frameSnapshot = new ThreadLocal<>();

    /** Pending buffers belong to the loader; this immutable bundle alone belongs to the display. */
    private record DisplaySnapshot(Dimension dimension, int[][] biomes, BufferedImage[] images,
            List<List<WorldIcon>> icons, BitSet layers) {}

	private volatile float alpha;
	private volatile int[][] biomeData;
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
		biomeData = new int[width][height];
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void populateBiomeData(BiomeDataOracle biomeDataOracle) {
		int width = biomeData.length;
		int height = width == 0 ? 0 : biomeData[0].length;
		int[][] result = new int[width][height];
		boolean populated = biomeDataOracle.getBiomeData(corner, width, height, true, data -> {
			for (int i = 0; i < width; i++) {
				for (int j = 0; j < height; j++) {
					result[i][j] = data[j * width + i];
				}
			}
			return true;
		}, () -> false);
		if (!populated) throw new IllegalStateException("Biome query did not populate fragment " + corner);
		biomeData = result;
	}

    /** Stage zero computes terrain; stage one completes the enabled overlays. */
    public int loadingStage() { return completedLayers.get(LayerIds.BACKGROUND) ? 1 : 0; }

    public int getBiomeDataAt(int x, int y) {
		return biomeData[x][y];
	}

    public int getDisplayBiomeDataAt(int x, int y) {
        if (!synchronizedPublication) return getBiomeDataAt(x, y);
        DisplaySnapshot snapshot = displayForRead();
        return snapshot == null || snapshot.biomes == null ? -1 : snapshot.biomes[x][y];
    }

    public void setSynchronizedPublication(boolean enabled) {
        if (synchronizedPublication != enabled) displaySnapshot = null;
        synchronizedPublication = enabled;
    }

    public boolean isSynchronizedPublication() { return synchronizedPublication; }

    public void setRequiredLayers(BitSet layers) { requiredLayers = (BitSet) layers.clone(); }

    public boolean hasAllRequiredLayers() {
        BitSet missing = (BitSet) requiredLayers.clone();
        missing.andNot(completedLayers);
        return missing.isEmpty();
    }

    /** Pin once per paint, so publication between layer draws cannot mix two generations. */
    public void beginDisplayFrame() {
        if (synchronizedPublication) {
            DisplaySnapshot snapshot = displaySnapshot;
            frameSnapshot.set(snapshot == null ? EMPTY_DISPLAY : snapshot);
        }
    }

    public void endDisplayFrame() { frameSnapshot.remove(); }

    private DisplaySnapshot displayForRead() {
        DisplaySnapshot pinned = frameSnapshot.get();
        return pinned == null ? displaySnapshot : pinned;
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
		if (synchronizedPublication) {
            DisplaySnapshot snapshot = displayForRead();
            return snapshot == null || !snapshot.layers.get(layerId) ? null : snapshot.images[layerId];
        }
		return images.get(layerId);
	}

	public void putWorldIcons(int layerId, List<WorldIcon> icons) {
		worldIcons.set(layerId, icons);
	}

	public List<WorldIcon> getWorldIcons(int layerId) {
        if (synchronizedPublication) {
            DisplaySnapshot snapshot = displayForRead();
            return state.get() == State.UNINITIALIZED || snapshot == null || !snapshot.layers.get(layerId)
                    ? Collections.emptyList() : snapshot.icons.get(layerId);
        }
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
        this.fullReloadRequested.set(false);
		this.dataDimension = null;
        this.displaySnapshot = null;
        completedLayers.clear();
        clearPublishedLayers();
	}

	public void prepareForLoad(Dimension dimension) {
		if (dataDimension != dimension) {
			// Never publish a new dimension's layers under the old picture's
			// identity, including a rapid dimension switch during a refresh.
            loadedDimension = null;
            displaySnapshot = null;
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
        if (!synchronizedPublication && layerId == LayerIds.BACKGROUND) {
                loadedDimension = dataDimension;
        }
    }

    private void clearPublishedLayers() {
        for (int i = 0; i < publishedLayers.length(); i++) publishedLayers.set(i, 0);
    }

    public boolean hasDisplayLayer(Dimension dimension, int layerId) {
        if (synchronizedPublication) {
            DisplaySnapshot snapshot = displayForRead();
            return snapshot != null && snapshot.dimension == dimension && dimension != null
                    && state.get() != State.UNINITIALIZED && snapshot.layers.get(layerId);
        }
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

    public void requestFullReload() {
        fullReloadRequested.set(true);
        requestBiomeReload();
    }

    public boolean getAndClearFullReloadRequested() {
        return fullReloadRequested.getAndSet(false);
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
        if (synchronizedPublication) {
            if (!hasAllRequiredLayers()) throw new IllegalStateException("Incomplete tile cannot be published");
            publishSnapshot(loadedDimension, requiredLayers);
        }
		this.loadedDimension = loadedDimension;
	}

    /** Publish one coherent terrain snapshot while independent POI confirmation continues. */
    public void publishTerrain() {
        if (!synchronizedPublication || !completedLayers.get(LayerIds.BACKGROUND)) return;
        BitSet ready = (BitSet) requiredLayers.clone();
        ready.and(completedLayers);
        publishSnapshot(dataDimension, ready);
        loadedDimension = dataDimension;
    }

    private void publishSnapshot(Dimension dimension, BitSet ready) {
            BufferedImage[] readyImages = new BufferedImage[images.length()];
            java.util.ArrayList<List<WorldIcon>> readyIcons = new java.util.ArrayList<>(images.length());
            for (int i = 0; i < readyImages.length; i++) {
                readyImages[i] = ready.get(i) ? images.get(i) : null;
                List<WorldIcon> icons = ready.get(i) ? worldIcons.get(i) : null;
                readyIcons.add(icons == null ? List.of() : List.copyOf(icons));
            }
            displaySnapshot = new DisplaySnapshot(dimension, biomeData, readyImages,
                    List.copyOf(readyIcons), (BitSet) ready.clone());
    }

	public Dimension getLoadedDimension() {
        if (synchronizedPublication) {
            DisplaySnapshot snapshot = displayForRead();
            return snapshot == null ? null : snapshot.dimension;
        }
		return loadedDimension;
	}

	/** The last completed picture remains visible while this coordinate refreshes. */
	public boolean hasDisplayData(Dimension dimension) {
        if (synchronizedPublication) {
            DisplaySnapshot snapshot = displayForRead();
            return snapshot != null && snapshot.dimension == dimension && dimension != null
                    && state.get() != State.UNINITIALIZED;
        }
		return dimension == loadedDimension && loadedDimension != null && state.get() != State.UNINITIALIZED;
	}
	
	public static enum State {
		UNINITIALIZED,
		INITIALIZED,
		LOADING,
		LOADED;
	}
}
