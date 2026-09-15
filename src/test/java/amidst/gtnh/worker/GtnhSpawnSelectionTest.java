package amidst.gtnh.worker;

import static org.junit.Assert.*;

import amidst.gui.main.viewer.WorldIconSelection;
import amidst.gui.main.viewer.widget.SelectedIconWidget;
import amidst.gui.main.viewer.widget.Widget;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicReference;

public class GtnhSpawnSelectionTest {
    private static class InfoPanel extends SelectedIconWidget {
        InfoPanel(WorldIconSelection selection) {
            super(Widget.CornerAnchorPoint.TOP_LEFT, selection);
        }

        String text() {
            var lines = updateTextLines();
            return lines == null ? null : String.join("\n", lines);
        }
    }

    @Test
    public void selectedEstimateFollowsSavedSpawnForReportedSeed() throws Exception {
        // User report: seed -8138049151491905853, screenshot estimate vs live Worker saved spawn.
        var value = new AtomicReference<>(new GtnhSpawnPoint(-336, null, -573, "ESTIMATED"));
        var oracle = new GtnhSpawnOracle(value::get);
        var image = DefaultWorldIconTypes.SPAWN.getImage();
        var selected = new WorldIconSelection(() -> oracle.icon(image));
        var panel = new InfoPanel(selected);
        oracle.refresh();
        WorldIcon old = oracle.icon(image);
        selected.select(old);
        assertTrue(panel.text().contains("-336"));
        value.set(new GtnhSpawnPoint(-58, 64, -180, "RECORDED"));
        oracle.refresh();
        assertEquals(value.get().coordinates(), selected.get().getCoordinates());
        assertEquals(value.get().label(), selected.get().getName());
        assertTrue(panel.text().contains("-58"));
        assertFalse(panel.text().contains("-336"));
        assertTrue(selected.isSelected(oracle.icon(image)));
        assertFalse(selected.isSelected(old));
        oracle.invalidate();
        assertNull(panel.text());
        assertFalse(selected.hasSelection());
        oracle.refresh();
        assertTrue(selected.hasSelection());
        selected.clear();
        assertNull(selected.get());
    }

    @Test
    public void ordinaryStructureSelectionDoesNotFollowSpawnChanges() {
        var point = new GtnhSpawnPoint(10, 64, 20, "RECORDED");
        var image = DefaultWorldIconTypes.SPAWN.getImage();
        var ordinary =
                new WorldIcon(
                        point.coordinates(),
                        "Other structure",
                        image,
                        amidst.mojangapi.world.Dimension.OVERWORLD,
                        false);
        var selection = new WorldIconSelection(() -> null);
        selection.select(ordinary);
        assertSame(ordinary, selection.get());
        assertTrue(selection.isSelected(ordinary));
    }
}
