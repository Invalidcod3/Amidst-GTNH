package amidst.fragment.layer;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledByAny;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.Fragment;
import amidst.fragment.loader.FragmentLoader;
import amidst.mojangapi.world.Dimension;
import amidst.threading.TaskCancellation;

@NotThreadSafe
public class LayerLoader {
	private final Iterable<FragmentLoader> loaders;
	private final long[] revisions;

	@CalledByAny
	public LayerLoader(Iterable<FragmentLoader> loaders, int numberOfLayers) {
		this.loaders = loaders;
		this.revisions = new long[numberOfLayers];
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void invalidateLayer(int layerId) {
		revisions[layerId]++;
	}

	/** Called before submission, even if the task is cancelled before its first layer. */
	public void prepareFragment(Fragment fragment, boolean reloadBiomes) {
		for (FragmentLoader loader : loaders) {
			int id = loader.getLayerId();
			if (fragment.getLayerRevision(id) != revisions[id]
					|| (reloadBiomes && (id == LayerIds.BIOME_DATA || id == LayerIds.BACKGROUND))) {
				fragment.invalidateLayerData(id);
			}
		}
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void loadAll(Dimension dimension, Fragment fragment) {
		for (FragmentLoader loader : loaders) {
			TaskCancellation.check();
			int id = loader.getLayerId();
			if (loader.isEnabled() && !fragment.hasLayerData(id)) {
				loader.load(dimension, fragment);
				fragment.markLayerComplete(id, revisions[id]);
			}
		}
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void reloadInvalidated(Dimension dimension, Fragment fragment) {
		reloadMissing(dimension, fragment);
	}

	@CalledOnlyBy(AmidstThread.FRAGMENT_LOADER)
	public void reloadBiomeLayers(Dimension dimension, Fragment fragment) {
		reloadMissing(dimension, fragment);
	}

	private void reloadMissing(Dimension dimension, Fragment fragment) {
		for (FragmentLoader loader : loaders) {
			TaskCancellation.check();
			int id = loader.getLayerId();
			if (loader.isEnabled() && !fragment.hasLayerData(id)) {
				loader.reload(dimension, fragment);
				fragment.markLayerComplete(id, revisions[id]);
			}
		}
	}
}
