package amidst.gtnh.export;

import amidst.gtnh.prospecting.ProspectingData.Deposit;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;

/** A snapshot of the clicked marker in native dimension block coordinates. */
public record GtnhMapWaypoint(Dimension dimension, GtnhWaypoint waypoint) {
    public static GtnhMapWaypoint structure(Dimension displayedDimension, WorldIcon icon) {
        // GTNH's dedicated Nether view already uses native coordinates. Its icons
        // carry OVERWORLD for legacy label formatting, so use the displayed map.
        return create(
                displayedDimension,
                icon.getName(),
                icon.getCoordinates().getX(),
                icon.getHeight() == null ? 64 : icon.getHeight(),
                icon.getCoordinates().getY(),
                0x55ccff);
    }

    public static GtnhMapWaypoint deposit(
            Dimension dimension, Deposit deposit, CoordinatesInWorld clicked) {
        boolean fluid = deposit.amounts != null;
        long x = fluid ? Math.floorDiv(clicked.getX(), 16) * 16 + 8 : deposit.x;
        long z = fluid ? Math.floorDiv(clicked.getY(), 16) * 16 + 8 : deposit.z;
        int y =
                fluid
                        ? 64
                        : deposit.y != null
                                ? deposit.y
                                : (int) (((long) deposit.minY + deposit.maxY) / 2);
        return create(dimension, deposit.name, x, y, z, deposit.color);
    }

    private static GtnhMapWaypoint create(
            Dimension dimension, String name, long x, int y, long z, int color) {
        // JourneyMap's ID is name + position; include the dimension to avoid
        // replacing a same-named marker at identical coordinates on another planet.
        return new GtnhMapWaypoint(
                dimension,
                new GtnhWaypoint(
                        name + " [" + dimension.prospectingKey() + "] " + x + "," + z,
                        Math.toIntExact(x),
                        y,
                        Math.toIntExact(z),
                        color >> 16 & 255,
                        color >> 8 & 255,
                        color & 255));
    }
}
