package amidst.gtnh.vegetation;

import static org.junit.Assert.*;

import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldType;

import org.junit.Test;

import java.awt.Component;
import java.awt.Container;
import java.util.List;

import javax.swing.*;

public class VegetationFilterPanelTest {
    private JButton button(Container parent, String text) {
        for (Component child : parent.getComponents()) {
            if (child instanceof JButton b && text.equals(b.getText())) return b;
            if (child instanceof Container c) {
                JButton found = button(c, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private <T> T find(Container parent, Class<T> type) {
        for (Component c : parent.getComponents()) {
            if (type.isInstance(c)) return type.cast(c);
            if (c instanceof Container child) {
                T result = find(child, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    @Test
    public void searchPreservesHiddenSelectionsAndPreviewIsReadOnly() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var b =
                            new GtnhBiomeDescriptor(
                                    510, "Meadow", 0, 0, 0, 0, 0, List.of("FOREST", "PLAINS"));
                    var panel =
                            new VegetationFilterPanel(
                                    List.of(b), WorldType.RWG_ALPHA, Dimension.OVERWORLD);
                    JTextField search = find(panel, JTextField.class);
                    JList<?> list = find(panel, JList.class);
                    search.setText("harvestcraft:apple");
                    assertEquals(1, list.getModel().getSize());
                    list.setSelectedIndex(0);
                    assertEquals(List.of(510), panel.matchingIds());
                    search.setText("不存在的植物");
                    assertEquals(0, list.getModel().getSize());
                    assertEquals(List.of(510), panel.matchingIds());
                    search.setText("harvestcraft:apple");
                    assertEquals(0, list.getSelectedIndex());
                    JTable table = find(panel, JTable.class);
                    assertFalse(table.isCellEditable(0, 0));
                    list.clearSelection();
                    assertTrue(panel.matchingIds().isEmpty());
                });
    }

    @Test
    public void sameNameInstancesShareOneRowButKeepAllHighlightIds() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var first =
                            new GtnhBiomeDescriptor(84, "Meadow", 0, 0, 0, 0, 0, List.of("FOREST"));
                    var transplanted =
                            new GtnhBiomeDescriptor(
                                    6484, "Meadow", 0, 0, 0, 0, 0, List.of("FOREST"));
                    var panel =
                            new VegetationFilterPanel(
                                    List.of(first, transplanted),
                                    WorldType.RWG_ALPHA,
                                    Dimension.OVERWORLD);
                    find(panel, JTextField.class).setText("harvestcraft:apple");
                    find(panel, JList.class).setSelectedIndex(0);
                    assertEquals(List.of(84, 6484), panel.matchingIds());
                    assertEquals(1, find(panel, JTable.class).getRowCount());
                    assertEquals("84, 6484", find(panel, JTable.class).getValueAt(0, 1));
                    var reopened =
                            new VegetationFilterPanel(
                                    List.of(first, transplanted),
                                    WorldType.RWG_ALPHA,
                                    Dimension.OVERWORLD,
                                    panel.selection());
                    assertEquals(panel.matchingIds(), reopened.matchingIds());
                    button(panel, amidst.i18n.I18n.text("Clear plant selection")).doClick();
                    assertTrue(panel.matchingIds().isEmpty());
                });
    }
}
