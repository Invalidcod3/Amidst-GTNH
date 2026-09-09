package amidst.gtnh.prospecting;

import static org.junit.Assert.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.AbstractPreferences;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.junit.Test;
import amidst.AmidstSettings;
import amidst.fragment.*;
import amidst.fragment.layer.LayerIds;
import amidst.gui.main.viewer.*;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.settings.Setting;
import amidst.gtnh.prospecting.ProspectingData.*;

public class ProspectingOverlayTest {
    static class MemoryPreferences extends AbstractPreferences {
        final Map<String,String> values = new HashMap<>();
        MemoryPreferences() { super(null, ""); }
        protected void putSpi(String k,String v) { values.put(k,v); }
        protected String getSpi(String k) { return values.get(k); }
        protected void removeSpi(String k) { values.remove(k); }
        protected void removeNodeSpi() {}
        protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        protected String[] childrenNamesSpi() { return new String[0]; }
        protected AbstractPreferences childSpi(String n) { throw new UnsupportedOperationException(); }
        protected void syncSpi() {}
        protected void flushSpi() {}
    }
    @Test public void rendersBothModesWithoutNetworkOnSwingAndWritesVisualFixtures() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        DimensionInfo info = new DimensionInfo(); info.key = "Overworld"; info.name = "Overworld"; info.ores = info.fluids = true;
        var source = new ProspectingOverlay.DataSource() {
            public List<DimensionInfo> dimensions() { return List.of(info); }
            public Tile load(Dimension dimension, int x, int z, String mode) {
                assertFalse("Socket work must never run on Swing", SwingUtilities.isEventDispatchThread());
                assertEquals(0, Math.floorMod(x, 256)); assertEquals(0, Math.floorMod(z, 256));
                calls.incrementAndGet();
                Tile tile = new Tile(); tile.deposits = new ArrayList<>();
                if (mode.equals("FLUID")) {
                    for (int dx = 0; dx < 2; dx++) for (int dz = 0; dz < 2; dz++) {
                        Deposit d = new Deposit(); d.x = x + dx * 128; d.z = z + dz * 128; d.size = 128;
                        d.name = dx == 0 ? "Natural Gas" : "Heavy Oil"; d.id = d.name;
                        d.color = dx == 0 ? 0xf8b85a : 0x6885e4; d.source = "INITIAL";
                        d.amounts = new int[64];
                        for (int i = 0; i < 64; i++) d.amounts[i] = 70 + i * 3;
                        tile.deposits.add(d);
                    }
                } else for (int dx = 24; dx < 256; dx += 48) for (int dz = 24; dz < 256; dz += 48) {
                    Deposit d = new Deposit(); d.x=x+dx; d.z=z+dz; d.size=16;
                    d.name = dx % 96 == 24 ? "Magnetite Vein" : "Copper Vein";
                    d.id=d.name; d.materials="Iron, Gold, Magnetite"; d.color=0xcf8253; d.minY=40; d.maxY=80;
                    d.source=dz % 96 == 24 ? "PREDICTED" : "RECORDED";
                    tile.deposits.add(d);
                }
                return tile;
            }
        };
        AmidstSettings settings = new AmidstSettings(new MemoryPreferences());
        FragmentManager manager = new FragmentManager(List.of(), LayerIds.NUMBER_OF_LAYERS, Setting.createDummy(1));
        FragmentGraph graph = new FragmentGraph(List.of(), manager);
        Zoom zoom = new Zoom(Setting.createDummy(true));
        FragmentGraphToScreenTranslator translator = new FragmentGraphToScreenTranslator(graph, zoom);
        try (ProspectingOverlay overlay = new ProspectingOverlay(source, settings, translator, zoom)) {
            SwingUtilities.invokeAndWait(() -> {
                zoom.adjustZoom(new Point(), -32); zoom.skipFading(); translator.update(1200, 800);
                translator.centerOn(CoordinatesInWorld.from(100, 100)); settings.markerMode.set(MarkerMode.FLUID);
            });
            BufferedImage detail = settle(overlay);
            assertTrue(calls.get() > 0);
            Path output = Path.of("build/reports/prospecting"); Files.createDirectories(output);
            ImageIO.write(detail, "png", output.resolve("fluid-detail.png").toFile());
            int before = calls.get(); settle(overlay); assertEquals("Repeated draws reuse completed tiles", before, calls.get());
            SwingUtilities.invokeAndWait(() -> settings.prospectingMinimumFluid.set(150));
            BufferedImage threshold = settle(overlay);
            ImageIO.write(threshold, "png", output.resolve("fluid-filtered.png").toFile());
            Point low = translator.worldToScreen(8, 40), high = translator.worldToScreen(120, 40);
            SwingUtilities.invokeAndWait(() -> settings.prospectingMinimumFluid.set(1000));
            BufferedImage hidden = settle(overlay);
            assertEquals("A low chunk must have the same background as a hidden field", hidden.getRGB(low.x, low.y), threshold.getRGB(low.x, low.y));
            assertNotEquals("A retained chunk must still be colored", hidden.getRGB(high.x, high.y), threshold.getRGB(high.x, high.y));
            assertEquals("Changing a filter must reuse cached data", before, calls.get());
            SwingUtilities.invokeAndWait(() -> settings.prospectingMinimumFluid.set(0));
            SwingUtilities.invokeAndWait(() -> { settings.markerMode.set(MarkerMode.ORES); });
            BufferedImage ores = settle(overlay); ImageIO.write(ores, "png", output.resolve("ores.png").toFile());
            assertNotEquals("Switching mode replaces the rendered overlay", detail.getRGB(500, 380), ores.getRGB(500, 380));
            SwingUtilities.invokeAndWait(() -> { zoom.adjustZoom(new Point(), 16); zoom.skipFading(); translator.update(1200,800);
                translator.centerOn(CoordinatesInWorld.from(100,100)); settings.markerMode.set(MarkerMode.FLUID); });
            ImageIO.write(settle(overlay), "png", output.resolve("fluid-overview.png").toFile());
            SwingUtilities.invokeAndWait(() -> { settings.prospectingFilter.set("does-not-exist"); });
            BufferedImage filtered = settle(overlay);
            assertNotEquals(detail.getRGB(500,380), filtered.getRGB(500,380));
        } finally { graph.dispose(); manager.clear(); }
    }
    private static BufferedImage settle(ProspectingOverlay overlay) throws Exception {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_ARGB);
        for (int i = 0; i < 24; i++) {
            SwingUtilities.invokeAndWait(() -> {
                Graphics2D g = image.createGraphics(); g.setColor(new Color(70,95,66)); g.fillRect(0,0,1200,800);
                overlay.draw(g,1200,800,null); g.dispose();
            });
            Thread.sleep(10);
        }
        return image;
    }
    @Test public void allProspectingDimensionsHaveUniqueStableKeys() {
        Set<String> keys = new HashSet<>(); Set<Integer> ids = new HashSet<>();
        for (Dimension d : Dimension.values()) { assertTrue(keys.add(d.prospectingKey())); assertTrue(ids.add(d.getId())); }
        assertEquals(42, keys.size());
    }
}
