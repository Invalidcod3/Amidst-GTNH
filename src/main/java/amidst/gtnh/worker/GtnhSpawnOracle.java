package amidst.gtnh.worker;

import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.WorldIconImage;
import amidst.mojangapi.world.oracle.WorldSpawnOracle;

import java.util.Objects;

/** One shared snapshot for the map, exports and Go to Spawn. Reads never perform network I/O. */
public final class GtnhSpawnOracle implements WorldSpawnOracle {
    @FunctionalInterface
    public interface Query {
        GtnhSpawnPoint get() throws MinecraftInterfaceException;
    }

    private final Query query;
    private volatile GtnhSpawnPoint point;
    private long generation;

    public GtnhSpawnOracle(Query query) {
        this.query = query;
    }

    @Override
    public CoordinatesInWorld get() {
        GtnhSpawnPoint snapshot = point;
        return snapshot == null ? null : snapshot.coordinates();
    }

    public WorldIcon icon(WorldIconImage image) {
        GtnhSpawnPoint snapshot = point;
        return snapshot == null ? null : new GtnhSpawnIcon(snapshot, image);
    }

    /** Called only by the background world-state poller. */
    public boolean refresh() throws MinecraftInterfaceException {
        final long requestGeneration;
        synchronized (this) {
            requestGeneration = generation;
        }
        GtnhSpawnPoint response = query.get();
        synchronized (this) {
            if (generation != requestGeneration) return false;
            boolean changed = !Objects.equals(point, response);
            point = response;
            return changed;
        }
    }

    /** Invalidates in-flight replies as well as a previous save's marker. */
    public synchronized void invalidate() {
        generation++;
        point = null;
    }
}
