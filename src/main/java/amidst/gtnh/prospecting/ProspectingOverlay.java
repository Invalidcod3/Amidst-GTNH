package amidst.gtnh.prospecting;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import amidst.AmidstSettings;
import amidst.i18n.I18n;
import amidst.gtnh.prospecting.ProspectingData.*;
import amidst.gui.main.viewer.FragmentGraphToScreenTranslator;
import amidst.gui.main.viewer.Zoom;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;

/** Screen-space prospecting renderer; all socket work is isolated from Swing. */
public final class ProspectingOverlay implements AutoCloseable {
    interface DataSource {
        List<DimensionInfo> dimensions();
        Tile load(Dimension dimension, int x, int z, String mode) throws Exception;
    }
    private record Key(Dimension dimension, MarkerMode mode, int x, int z) {}
    private record Cached(Tile tile, long time) {}
    private record Hit(Rectangle bounds, Deposit deposit) {}
    private final DataSource source;
    private final AmidstSettings settings;
    private final FragmentGraphToScreenTranslator translator;
    private final Zoom zoom;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "GTNH prospecting"); t.setDaemon(true); return t;
    });
    private final Map<Key, Cached> tiles = new LinkedHashMap<>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> e) { return size() > 256; }
    };
    private final Map<String, BufferedImage> images = new LinkedHashMap<>(32, .75f, true) {
        protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > 512; }
    };
    private final List<Hit> hits = new ArrayList<>();
    private volatile boolean closed;
    private Future<?> pending;
    private String error = "";
    private long retryAfter;
    private Dimension displayedDimension;
    private MarkerMode displayedMode;
    private long generation;

    public ProspectingOverlay(World world, AmidstSettings settings, FragmentGraphToScreenTranslator translator, Zoom zoom) {
        this(new DataSource() {
            public List<DimensionInfo> dimensions() { return world.prospectingCatalog(); }
            public Tile load(Dimension d, int x, int z, String mode) throws Exception { return world.prospect(d, x, z, 256, 256, mode); }
        }, settings, translator, zoom);
    }
    ProspectingOverlay(DataSource source, AmidstSettings settings, FragmentGraphToScreenTranslator translator, Zoom zoom) {
        this.source = source; this.settings = settings; this.translator = translator; this.zoom = zoom;
    }
    public boolean active() { return settings.markerMode.get() != MarkerMode.STRUCTURE; }
    public DimensionInfo dimensionInfo(Dimension dimension) {
        return source.dimensions().stream().filter(d -> d.key.equals(dimension.prospectingKey())).findFirst().orElse(null);
    }
    public static ImageIcon menuIcon(DimensionInfo info) {
        BufferedImage image = decode(info == null ? "" : info.icon);
        return image == null ? null : new ImageIcon(image.getScaledInstance(20, 20, Image.SCALE_FAST));
    }
    private static BufferedImage decode(String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > 100_000) return null;
        try { return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64))); }
        catch (Exception e) { return null; }
    }
    public void draw(Graphics2D target, int width, int height, Point mouse) {
        hits.clear();
        if (closed || !active()) return;
        Dimension dimension = settings.dimension.get();
        MarkerMode mode = settings.markerMode.get();
        if (dimension != displayedDimension || mode != displayedMode) {
            displayedDimension = dimension; displayedMode = mode; error = ""; retryAfter = 0;
            if (pending != null) pending.cancel(true);
            pending = null;
        }
        Graphics2D g = (Graphics2D) target.create();
        try {
            g.setComposite(AlphaComposite.SrcOver);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            if (mode == MarkerMode.FLUID || dimension.isProspectingOnly()) {
                g.setColor(dimension.isProspectingOnly() ? new Color(26, 29, 33) : new Color(0, 0, 0, 135));
                g.fillRect(0, 0, width, height);
            }
            DimensionInfo info = dimensionInfo(dimension);
            if (info == null || !(mode == MarkerMode.ORES ? info.ores : info.fluids)) {
                label(g, I18n.format("No {0} registered in {1}",I18n.text(mode.toString()),dimension), 15, height - 48); return;
            }
            var corner = translator.screenToWorld(new Point(0, 0));
            var end = translator.screenToWorld(new Point(width, height));
            long tx0 = Math.floorDiv(corner.getX(), 256), tz0 = Math.floorDiv(corner.getY(), 256);
            long tx1 = Math.floorDiv(end.getX(), 256), tz1 = Math.floorDiv(end.getY(), 256);
            long count = (tx1 - tx0 + 1) * (tz1 - tz0 + 1);
            if (count > 256 || tx0 < Integer.MIN_VALUE / 256 || tx1 >= Integer.MAX_VALUE / 256
                    || tz0 < Integer.MIN_VALUE / 256 || tz1 >= Integer.MAX_VALUE / 256) {
                label(g, "Zoom in to load prospecting data", 15, height - 48); return;
            }
            Key next = null;
            int loaded = 0;
            String filter = settings.prospectingFilter.get();
            if (mode == MarkerMode.FLUID) grid(g, corner, end, width, height);
            for (long tz = tz0; tz <= tz1; tz++) for (long tx = tx0; tx <= tx1; tx++) {
                Key key = new Key(dimension, mode, (int)tx * 256, (int)tz * 256);
                Cached cached = tiles.get(key);
                if (cached == null) { if (next == null) next = key; continue; }
                loaded++;
                for (Deposit d : cached.tile.deposits) {
                    if (!matches(d, filter, mode == MarkerMode.FLUID ? settings.prospectingMinimumFluid.get() : 0)) continue;
                    Point p = screen(d.x, d.z);
                    if (mode == MarkerMode.ORES) ore(g, d, p, width, height);
                    else fluid(g, d, p, width, height);
                }
            }
            if (next == null) {
                for (long tz = tz0; tz <= tz1 && next == null; tz++) for (long tx = tx0; tx <= tx1; tx++) {
                    Key key = new Key(dimension, mode, (int)tx * 256, (int)tz * 256);
                    Cached cached = tiles.get(key);
                    if (cached != null && System.currentTimeMillis() - cached.time > 30_000) { next = key; break; }
                }
            }
            if (next != null && (pending == null || pending.isDone()) && System.currentTimeMillis() >= retryAfter) load(next);
            String status = error.isEmpty() ? I18n.format("{0} · {1}/{2} tiles",I18n.text(mode.toString()),loaded,count)
                    + " · " + I18n.text(mode == MarkerMode.FLUID ? "L/Op · zoom in for chunk values" : "outlined = recorded, dashed = predicted") : error;
            if (!filter.isEmpty()) {
                List<FilterOption> options = mode == MarkerMode.FLUID ? info.fluidOptions : info.oreOptions;
                String name = options == null ? filter : options.stream().filter(o -> filter.equals(o.id))
                        .map(o -> o.name).findFirst().orElse(filter);
                status += " · " + I18n.text("filter") + ": " + name;
            }
            if (mode == MarkerMode.FLUID && settings.prospectingMinimumFluid.get() > 0)
                status += " · " + I18n.text("minimum") + ": " + settings.prospectingMinimumFluid.get() + " L/Op";
            label(g, status, 15, height - 48);
            if (dimension.isProspectingOnly()) label(g, "Prospecting map · biome background unavailable in this dimension", 15, height - 68);
            if (mouse != null) {
                Hit hit = hit(mouse);
                if (hit != null) tooltip(g, hit.deposit, mouse, width, height);
            }
        } finally { g.dispose(); }
    }
    private void load(Key key) {
        long requestedGeneration = generation;
        pending = executor.submit(() -> {
            try {
                Tile tile = source.load(key.dimension, key.x, key.z, key.mode.name());
                if (Thread.currentThread().isInterrupted()) return;
                SwingUtilities.invokeLater(() -> {
                    if (!closed && generation == requestedGeneration) { tiles.put(key, new Cached(tile, System.currentTimeMillis())); error = ""; }
                });
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) return;
                SwingUtilities.invokeLater(() -> {
                    if (!closed && settings.dimension.get() == key.dimension && settings.markerMode.get() == key.mode) {
                        error = "Prospecting: " + e.getMessage(); retryAfter = System.currentTimeMillis() + 5000;
                    }
                });
            }
        });
    }
    private Point screen(long x, long z) {
        return translator.worldToScreen(x, z);
    }
    private void ore(Graphics2D g, Deposit d, Point p, int width, int height) {
        int size = (int)Math.max(10, Math.min(32, zoom.worldToScreen(24)));
        if (p.x < -size || p.y < -size || p.x > width + size || p.y > height + size) return;
        BufferedImage image = images.computeIfAbsent(d.icon == null ? "" : d.icon, ProspectingOverlay::decode);
        if (image != null) g.drawImage(image, p.x - size / 2, p.y - size / 2, size, size, null);
        else { g.setColor(new Color(d.color)); g.fillRect(p.x - size / 2, p.y - size / 2, size, size); }
        g.setColor("RECORDED".equals(d.source) ? new Color(120, 255, 180) : new Color(230, 230, 230, 150));
        g.setStroke("RECORDED".equals(d.source) ? new BasicStroke(1) : new BasicStroke(1, 0, 0, 1, new float[]{2, 2}, 0));
        g.drawRect(p.x - size / 2, p.y - size / 2, size, size);
        g.setStroke(new BasicStroke(1));
        hits.add(new Hit(new Rectangle(p.x - size / 2, p.y - size / 2, size, size), d));
        if (zoom.worldToScreen(48) >= 95) label(g, d.name, p.x - g.getFontMetrics().stringWidth(d.name) / 2, p.y + size / 2 + 15);
    }
    private void fluid(Graphics2D g, Deposit d, Point p, int width, int height) {
        if (d.amounts == null || d.amounts.length != 64) return;
        int field = (int)Math.ceil(zoom.worldToScreen(128));
        if (p.x + field < 0 || p.y + field < 0 || p.x > width || p.y > height) return;
        int threshold = settings.prospectingMinimumFluid.get();
        int min = Arrays.stream(d.amounts).filter(a -> a >= threshold).min().orElse(0);
        int max = Arrays.stream(d.amounts).max().orElse(0);
        if (max < threshold) return;
        if (threshold == 0) {
            g.setColor(new Color(d.color & 0xffffff | 0x38000000, true));
            g.fillRect(p.x, p.y, field, field);
        }
        double cell = zoom.worldToScreen(16);
        if (cell >= 8 || threshold > 0) for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) {
            int amount = d.amounts[x * 8 + z];
            if (amount < threshold) continue;
            int alpha = 35 + (int)((amount - min) / (double)(max - min + 1) * 145);
            Point cp = screen(d.x + x * 16L, d.z + z * 16L);
            g.setColor(new Color(d.color & 0xffffff | alpha << 24, true));
            g.fillRect(cp.x, cp.y, (int)Math.ceil(cell), (int)Math.ceil(cell));
            if (cell >= 8) {
                g.setColor(new Color(235,241,250,95)); g.drawRect(cp.x,cp.y,(int)Math.ceil(cell),(int)Math.ceil(cell));
            }
            if (cell >= 8 && amount == max && max >= 10) {
                g.setColor(new Color(255, 215, 0, 205)); g.drawRect(cp.x + 1, cp.y + 1, (int)cell - 2, (int)cell - 2);
            }
            if (cell >= 58) label(g, amount + " L/Op", cp.x + 3, cp.y + (int)cell / 2 + 4);
        }
        g.setColor(new Color(d.color & 0xffffff | 0xcc000000, true));
        g.setStroke(new BasicStroke(2)); g.drawRect(p.x, p.y, field, field); g.setStroke(new BasicStroke(1));
        if (field >= 120) {
            label(g, d.name, p.x + 5, p.y + 16);
            if (cell >= 20 && cell < 58) label(g, min + "–" + max + " L/Op", p.x + 5, p.y + 32);
        }
        hits.add(new Hit(new Rectangle(p.x, p.y, field, field), d));
    }
    private void grid(Graphics2D g, CoordinatesInWorld start, CoordinatesInWorld end, int width, int height) {
        if (zoom.worldToScreen(16) < 6) return;
        g.setColor(new Color(235, 241, 250, 95));
        for (long x = Math.floorDiv(start.getX(), 16) * 16; x <= end.getX(); x += 16) {
            int px = screen(x, 0).x; g.drawLine(px, 0, px, height);
        }
        for (long z = Math.floorDiv(start.getY(), 16) * 16; z <= end.getY(); z += 16) {
            int py = screen(0, z).y; g.drawLine(0, py, width, py);
        }
    }
    private void tooltip(Graphics2D g, Deposit d, Point mouse, int width, int height) {
        List<String> lines = new ArrayList<>();
        lines.add(d.name + (d.depleted ? " · " + I18n.text("depleted") : ""));
        var hovered = translator.screenToWorld(mouse);
        long selectedX = d.amounts == null ? d.x : Math.floorDiv(hovered.getX(), 16) * 16 + 8;
        long selectedZ = d.amounts == null ? d.z : Math.floorDiv(hovered.getY(), 16) * 16 + 8;
        lines.add("X: " + selectedX + "   Z: " + selectedZ + "   · " + I18n.text(d.source));
        if (d.amounts == null) { lines.add("Y: " + d.minY + "–" + d.maxY); lines.add(d.materials); }
        else if (zoom.worldToScreen(16) >= 58) {
            var point = translator.screenToWorld(mouse);
            int x = Math.floorDiv((int)point.getX() - d.x, 16), z = Math.floorDiv((int)point.getY() - d.z, 16);
            if (x >= 0 && x < 8 && z >= 0 && z < 8) {
                boolean current = d.currentAmounts != null && d.currentAmounts[x * 8 + z];
                lines.add(d.amounts[x * 8 + z] + " L/Op · " + I18n.text(current ? "current" : "initial") + " · " + I18n.text("chunk") + " " + Math.floorDiv(point.getX(), 16) + ", " + Math.floorDiv(point.getY(), 16));
            }
        }
        lines.add("Click to copy coordinates");
        int boxWidth = lines.stream().filter(Objects::nonNull).mapToInt(s -> g.getFontMetrics().stringWidth(s)).max().orElse(100) + 16;
        int x = Math.max(0, Math.min(mouse.x + 18, width - boxWidth));
        int y = Math.max(20, Math.min(mouse.y + 20, height - lines.size() * 18 - 10));
        for (String line : lines) { if (line != null) label(g, line, x + 6, y); y += 18; }
    }
    private Hit hit(Point point) {
        for (int i = hits.size() - 1; i >= 0; i--) if (hits.get(i).bounds.contains(point)) {
            Hit hit = hits.get(i);
            var world = translator.screenToWorld(point);
            if (visibleAt(hit.deposit, world.getX(), world.getY(), settings.prospectingMinimumFluid.get())) return hit;
        }
        return null;
    }
    static boolean matches(Deposit deposit, String id, int minimum) {
        return (id.isEmpty() || id.equals(deposit.id)) && (deposit.amounts == null
                || Arrays.stream(deposit.amounts).anyMatch(a -> a >= minimum));
    }
    static boolean visibleAt(Deposit deposit, long x, long z, int minimum) {
        if (deposit.amounts == null) return true;
        long cx = Math.floorDiv(x - deposit.x, 16), cz = Math.floorDiv(z - deposit.z, 16);
        return cx >= 0 && cx < 8 && cz >= 0 && cz < 8 && deposit.amounts[(int)(cx * 8 + cz)] >= minimum;
    }
    public boolean click(MouseEvent event) {
        if (!active()) return false;
        Hit hit = hit(event.getPoint());
        if (hit != null) {
            Deposit d = hit.deposit;
            var point = translator.screenToWorld(event.getPoint());
            long x = d.amounts == null ? d.x : Math.floorDiv(point.getX(), 16) * 16 + 8;
            long z = d.amounts == null ? d.z : Math.floorDiv(point.getY(), 16) * 16 + 8;
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                    d.name + " | " + settings.dimension.get() + " | X=" + x + " Z=" + z + " | " + d.source), null);
        }
        return true;
    }
    private static void label(Graphics2D g, String text, int x, int y) {
        text = I18n.text(text);
        g.setColor(new Color(0, 0, 0, 190)); g.fillRect(x - 3, y - 13, g.getFontMetrics().stringWidth(text) + 6, 17);
        g.setColor(Color.WHITE); g.drawString(text, x, y);
    }
    public void refresh() {
        generation++; tiles.clear(); retryAfter = 0; error = "";
        if (pending != null) pending.cancel(true);
        pending = null;
    }
    @Override public void close() { closed = true; executor.shutdownNow(); tiles.clear(); images.clear(); }
}
