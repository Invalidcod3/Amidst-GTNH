package amidst.gtnh.export;

import static org.junit.Assert.*;

import amidst.gtnh.prospecting.ProspectingData.Deposit;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class GtnhMapWaypointTest {
    @Test
    public void preservesKnownStructureHeight() {
        var icon =
                new WorldIcon(
                        CoordinatesInWorld.from(-137, 259),
                        "Dungeon",
                        null,
                        Dimension.OVERWORLD,
                        false,
                        31);
        assertEquals(31, GtnhMapWaypoint.structure(Dimension.OVERWORLD, icon).waypoint().y());
    }

    @Test
    public void usesDisplayedDimensionAndNativeCoordinatesForEveryMap() {
        // Nether structures deliberately carry OVERWORLD as their display metadata.
        WorldIcon icon =
                new WorldIcon(
                        CoordinatesInWorld.from(-137, 259),
                        "Marker",
                        null,
                        Dimension.OVERWORLD,
                        false);
        Set<String> names = new HashSet<>();
        for (Dimension dimension : Dimension.values()) {
            var target = GtnhMapWaypoint.structure(dimension, icon);
            assertEquals(dimension, target.dimension());
            assertEquals(-137, target.waypoint().x());
            assertEquals(259, target.waypoint().z());
            assertTrue(
                    "Same coordinates must coexist across dimensions",
                    names.add(target.waypoint().name()));
        }
    }

    @Test
    public void oreUsesVeinCenterAndHeightRangeInsteadOfMousePosition() {
        Deposit ore = new Deposit();
        ore.name = "Iron";
        ore.x = -24;
        ore.z = 72;
        ore.minY = 21;
        ore.maxY = 49;
        ore.color = 0xc08040;
        var target =
                GtnhMapWaypoint.deposit(Dimension.NETHER, ore, CoordinatesInWorld.from(-26, 74));
        assertEquals(Dimension.NETHER, target.dimension());
        assertEquals(-24, target.waypoint().x());
        assertEquals(35, target.waypoint().y());
        assertEquals(72, target.waypoint().z());
        assertEquals(192, target.waypoint().red());
        assertEquals(128, target.waypoint().green());
        assertEquals(64, target.waypoint().blue());
    }

    @Test
    public void fluidUsesClickedChunkCenterIncludingNegativeBoundaries() {
        Deposit fluid = new Deposit();
        fluid.name = "Oil";
        fluid.x = -128;
        fluid.z = -128;
        fluid.amounts = new int[64];
        long[] clicks = {-128, -17, -16, -1, 0, 15, 16};
        int[] centers = {-120, -24, -8, -8, 8, 8, 24};
        for (int i = 0; i < clicks.length; i++) {
            var target =
                    GtnhMapWaypoint.deposit(
                            Dimension.MOON, fluid, CoordinatesInWorld.from(clicks[i], clicks[i]));
            assertEquals(centers[i], target.waypoint().x());
            assertEquals(centers[i], target.waypoint().z());
            assertEquals(64, target.waypoint().y());
        }
    }
}
