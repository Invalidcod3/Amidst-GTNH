package amidst.gui.main.viewer;

import static org.junit.Assert.*;

import amidst.fragment.FragmentGraph;
import amidst.gui.main.Actions;
import amidst.gui.main.viewer.widget.WidgetManager;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.icon.WorldIconImage;
import amidst.settings.Setting;

import org.junit.Test;

import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JPanel;

public class ViewerMouseListenerTest {
    @Test
    public void onlyLeftDoubleClickOnMarkerImports() {
        WorldIcon icon =
                new WorldIcon(
                        CoordinatesInWorld.from(-137, 259),
                        "Fortress",
                        WorldIconImage.from(new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB)),
                        Dimension.OVERWORLD,
                        false);
        List<WorldIcon> imports = new ArrayList<>();
        Actions actions =
                new Actions(null, null, null, null, null, null, null, null) {
                    @Override
                    public void selectWorldIcon(WorldIcon selected) {}

                    @Override
                    public void importWorldIconToJourneyMap(WorldIcon selected) {
                        imports.add(selected);
                    }
                };
        FragmentGraph graph =
                new FragmentGraph(List.of(), null) {
                    @Override
                    public WorldIcon getClosestWorldIcon(CoordinatesInWorld point, double max) {
                        return icon;
                    }
                };
        Zoom zoom = new Zoom(Setting.createDummy(true));
        FragmentGraphToScreenTranslator translator =
                new FragmentGraphToScreenTranslator(graph, zoom) {
                    @Override
                    public CoordinatesInWorld screenToWorld(Point point) {
                        return icon.getCoordinates();
                    }

                    @Override
                    public Point worldToScreen(long x, long z) {
                        return new Point(100, 100);
                    }
                };
        ViewerMouseListener listener =
                new ViewerMouseListener(
                        new WidgetManager(List.of()), graph, translator, zoom, null, actions);
        click(listener, 1, MouseEvent.BUTTON1, 100);
        click(listener, 2, MouseEvent.BUTTON3, 100);
        click(listener, 2, MouseEvent.BUTTON2, 100);
        click(listener, 3, MouseEvent.BUTTON1, 100);
        click(
                listener,
                2,
                MouseEvent.BUTTON1,
                140); // Nearby blank space must not import the closest icon.
        assertTrue(imports.isEmpty());
        click(listener, 2, MouseEvent.BUTTON1, 100);
        assertEquals(List.of(icon), imports);
    }

    private static void click(ViewerMouseListener listener, int count, int button, int x) {
        listener.mouseClicked(
                new MouseEvent(
                        new JPanel(),
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        x,
                        100,
                        count,
                        false,
                        button));
    }
}
