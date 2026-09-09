package amidst.gtnh.worker;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.Method;
import java.util.Base64;
import javax.imageio.ImageIO;

final class ProspectingTextures {
    private ProspectingTextures() {}
    static String oreIcon(Object location) throws ReflectiveOperationException {
        Class<?> marker = Class.forName("com.sinthoras.visualprospecting.integration.model.render.OreVeinMapMarker");
        Method sprite = marker.getDeclaredMethod("spriteImage", net.minecraft.util.IIcon.class, int.class);
        sprite.setAccessible(true);
        Object stone = ProspectingReflection.call(Class.forName(
                "com.sinthoras.visualprospecting.integration.model.render.DimensionStoneBackground"),
                "getBackgroundIcon", ProspectingReflection.call(location, "getDimensionId"));
        Object ore = ProspectingReflection.call(location, "getIconFromPrimaryOre");
        BufferedImage background = (BufferedImage) sprite.invoke(null, stone, 0xffffff);
        BufferedImage mineral = (BufferedImage) sprite.invoke(null, ProspectingReflection.call(ore, "getIcon"),
                ProspectingReflection.call(location, "getColor"));
        BufferedImage overlay = (BufferedImage) sprite.invoke(null, ProspectingReflection.call(ore, "getOverlayIcon"), 0xffffff);
        if (background == null || mineral == null) throw new IllegalStateException("Visual Prospecting ore textures are unavailable");
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(background,0,0,32,32,null); g.drawImage(mineral,0,0,32,32,null);
            if (overlay != null) g.drawImage(overlay,0,0,32,32,null);
            if ((Boolean) ProspectingReflection.call(location, "isDepleted")) {
                g.setColor(new Color(0,0,0,150)); g.fillRect(0,0,32,32);
                try (InputStream in = ProspectingTextures.class.getResourceAsStream("/assets/visualprospecting/textures/depleted.png")) {
                    if (in != null) g.drawImage(ImageIO.read(in),0,0,32,32,null);
                } catch (IOException e) { throw new IllegalStateException(e); }
            }
        } finally { g.dispose(); }
        return encode(image);
    }
    static String dimensionIcon(String abbreviation) {
        return dimensionIcon(abbreviation, ProspectingTextures.class::getResourceAsStream);
    }
    interface Resources { InputStream open(String path) throws IOException; }
    static String dimensionIcon(String abbreviation, Resources resources) {
        if (abbreviation == null || abbreviation.isEmpty()) return "";
        BufferedImage front = load(abbreviation, "front", resources), top = load(abbreviation, "top", resources),
                right = load(abbreviation, "right", resources);
        if (front == null || top == null || right == null) return "";
        BufferedImage result = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = result.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        face(g, top, new AffineTransform(1, .5, -1, .5, 16, 0));
        face(g, front, new AffineTransform(1, .5, 0, 1, 0, 8));
        face(g, right, new AffineTransform(1, -.5, 0, 1, 16, 16));
        g.dispose();
        return encode(result);
    }
    private static void face(Graphics2D g, BufferedImage img, AffineTransform transform) {
        transform.scale(16.0 / img.getWidth(), 16.0 / img.getHeight());
        g.drawImage(img, transform, null);
    }
    private static BufferedImage load(String abbr, String face, Resources resources) {
        String path = "/assets/gtneioreplugin/textures/blocks/" + abbr + "_" + face + ".png";
        try (InputStream in = resources.open(path)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) { throw new IllegalStateException("Cannot read dimension icon " + path, e); }
    }
    static String encode(BufferedImage image) {
        if (image == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
