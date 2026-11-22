package amidst.gtnh.vegetation;

import static org.junit.Assert.*;

import amidst.fragment.layer.LayerReloader;
import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.gui.main.viewer.BiomeSelection;
import amidst.gui.main.viewer.widget.BiomeWidget;
import amidst.gui.main.viewer.widget.Widget;
import amidst.mojangapi.world.biome.Biome;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.biome.BiomeType;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

public class VegetationBiomeWidgetTest {
    @Test
    public void vegetationSelectsMatchesWithoutChangingRowsOrExistingControls() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var selection = new BiomeSelection();
                    var biomes =
                            new BiomeList(
                                    List.of(
                                            new GtnhBiomeDescriptor(1, "Cold Forest", 0, 0, 0, 0, 0)
                                                    .toBiome(),
                                            new GtnhBiomeDescriptor(84, "Meadow", 0, 0, 0, 0, 0)
                                                    .toBiome(),
                                            new Biome(
                                                    "Sunflower Plains",
                                                    1,
                                                    new BiomeType(0, 0)), // Special biome, ID 129.
                                            new GtnhBiomeDescriptor(
                                                            193, "Tainted Land", 0, 0, 0, 0, 0)
                                                    .toBiome(),
                                            new GtnhBiomeDescriptor(6484, "Meadow", 0, 0, 0, 0, 0)
                                                    .toBiome()));
                    var reloads = new AtomicInteger();
                    var edits = new AtomicInteger();
                    var widget =
                            new BiomeWidget(
                                    Widget.CornerAnchorPoint.NONE,
                                    selection,
                                    new LayerReloader(null, null) {
                                        @Override
                                        public void reloadBackgroundLayer() {
                                            reloads.incrementAndGet();
                                        }
                                    },
                                    null,
                                    biomes);
                    widget.setVegetationAction(() -> true, edits::incrementAndGet);
                    var graphics =
                            new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB)
                                    .createGraphics();
                    try {
                        widget.update(800, 600, null, graphics.getFontMetrics(), 0);
                        selection.toggle(193);
                        selection.highlightOnly(List.of(84, 6484));
                        widget.update(800, 600, null, graphics.getFontMetrics(), 0);
                        assertTrue(selection.isHighlightMode());
                        assertTrue(selection.isSelected(84));
                        assertTrue(selection.isSelected(6484));
                        assertFalse(selection.isSelected(193));
                        assertFalse(selection.isVisible(1));

                        // The original first row remains available even though it did not match.
                        widget.onMousePressed(30, 60);
                        assertTrue(selection.isVisible(1));
                        assertTrue(selection.isSelected(84));
                        // Same-name biomes retain their original separate rows and toggle
                        // independently.
                        widget.onMousePressed(30, 124);
                        assertFalse(selection.isSelected(6484));
                        assertTrue(selection.isSelected(84));

                        widget.onMousePressed(210, 385); // Existing None control.
                        assertFalse(selection.isSelected(84));
                        assertFalse(selection.isSelected(1));
                        widget.onMousePressed(
                                125, 385); // Existing All control selects every biome again.
                        assertTrue(selection.isVisible(1));
                        assertTrue(selection.isVisible(84));
                        assertTrue(selection.isVisible(6484));
                        assertTrue(selection.isVisible(999));
                        widget.onMousePressed(
                                160, 385); // Existing Special control remains unrestricted.
                        assertTrue(selection.isSelected(129));
                        assertFalse(selection.isSelected(1));
                        assertFalse(selection.isSelected(84));
                        assertFalse(selection.isSelected(6484));
                        widget.onMousePressed(200, 12); // Existing active/inactive toggle.
                        assertFalse(selection.isHighlightMode());
                        assertTrue(selection.isVisible(1));
                        widget.onMousePressed(
                                30, 35); // The added entry only opens the plant selector.
                        assertEquals(1, edits.get());
                        assertTrue(reloads.get() >= 6);
                    } finally {
                        graphics.dispose();
                    }
                });
    }
}
