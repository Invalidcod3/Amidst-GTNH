package amidst.gui.main.viewer;

import amidst.documentation.ThreadSafe;
import amidst.mojangapi.world.icon.WorldIcon;

@ThreadSafe
public class WorldIconSelection {
	private volatile WorldIcon selection;
    private final java.util.function.Supplier<WorldIcon> worldSpawn;

    public WorldIconSelection() { this(null); }

    public WorldIconSelection(java.util.function.Supplier<WorldIcon> worldSpawn) {
        this.worldSpawn = worldSpawn;
    }

	public WorldIcon get() {
        WorldIcon selected = selection;
        return worldSpawn != null && selected instanceof amidst.gtnh.worker.GtnhSpawnIcon
                ? worldSpawn.get() : selected;
	}

	public void select(WorldIcon selection) {
		this.selection = selection;
	}

	public void clear() {
		this.selection = null;
	}

	public boolean isSelected(WorldIcon worldIcon) {
        WorldIcon selected = get();
        if (worldSpawn != null && selected instanceof amidst.gtnh.worker.GtnhSpawnIcon
                && worldIcon instanceof amidst.gtnh.worker.GtnhSpawnIcon) {
            return selected.getCoordinates().equals(worldIcon.getCoordinates());
        }
        return selected == worldIcon;
	}

	public boolean hasSelection() {
		return get() != null;
	}
}
