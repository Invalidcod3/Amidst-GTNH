package amidst.fragment.layer;

import java.util.List;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledByAny;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.Fragment;
import amidst.fragment.drawer.FragmentDrawer;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.World;
import amidst.threading.TaskQueue;

@NotThreadSafe
public class LayerManager {
	private final TaskQueue invalidationOperations = new TaskQueue();

	private final List<LayerDeclaration> declarations;
	private final LayerLoader layerLoader;
	private final Iterable<FragmentDrawer> drawers;
	private Dimension currentDimension;

	public LayerManager(
			List<LayerDeclaration> declarations,
			LayerLoader layerLoader,
			Iterable<FragmentDrawer> drawers) {
		this.declarations = declarations;
		this.layerLoader = layerLoader;
		this.drawers = drawers;
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public boolean updateAll(Dimension dimension) {
		boolean dimensionChanged = currentDimension != null && currentDimension != dimension;
		currentDimension = dimension;
		for (LayerDeclaration declaration : declarations) {
			if (declaration.update(dimension)) {
				int layerId = declaration.getLayerId();
				if (layerId == LayerIds.BIOME_DATA || layerId == LayerIds.END_ISLANDS) {
					invalidateLayer(LayerIds.BACKGROUND);
				}
				invalidateLayer(layerId);
			}
		}
		if (dimensionChanged) {
			/*
			 * Overworld and Nether share the fragment biome buffer, but use
			 * different oracles. Force that buffer and its rendered image to
			 * refresh whenever the displayed dimension changes.
			 */
			invalidateLayer(LayerIds.BIOME_DATA);
			invalidateLayer(LayerIds.BACKGROUND);
		}
		return invalidationOperations.processTasks();
	}

	@CalledByAny
	public void invalidateLayer(int layerId) {
		invalidationOperations.invoke(() -> doInvalidateLayer(layerId));
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	private void doInvalidateLayer(int layerId) {
		layerLoader.invalidateLayer(layerId);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void loadAll(Dimension dimension, Fragment fragment) {
		layerLoader.loadAll(dimension, fragment);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void reloadInvalidated(Dimension dimension, Fragment fragment) {
		layerLoader.reloadInvalidated(dimension, fragment);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void reloadBiomeLayers(Dimension dimension, Fragment fragment) {
		layerLoader.reloadBiomeLayers(dimension, fragment);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void clearInvalidatedLayers() {
		layerLoader.clearInvalidatedLayers();
	}

	public Iterable<LayerDeclaration> getDeclarations() {
		return declarations;
	}

	public Iterable<FragmentDrawer> getDrawers() {
		return drawers;
	}

	public LayerReloader createLayerReloader(World world) {
		return new LayerReloader(world, this);
	}

	/**
	 * This should only be used by the layers menu.
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public boolean calculateIsEnabled(int layerId, Dimension dimension) {
		return declarations.get(layerId).calculateIsEnabled(dimension);
	}
}
