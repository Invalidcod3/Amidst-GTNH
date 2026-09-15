package amidst.gtnh.worker;

import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.WorldIconImage;

/**
 * Identifies a live world-spawn selection without relying on translated labels or image identity.
 */
public final class GtnhSpawnIcon extends WorldIcon {
    GtnhSpawnIcon(GtnhSpawnPoint point, WorldIconImage image) {
        super(point.coordinates(), point.label(), image, Dimension.OVERWORLD, false, point.y());
    }
}
