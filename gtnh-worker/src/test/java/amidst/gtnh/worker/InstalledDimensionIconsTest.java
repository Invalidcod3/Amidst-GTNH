package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.jar.JarFile;
import javax.imageio.ImageIO;
import org.junit.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import com.google.gson.Gson;

public class InstalledDimensionIconsTest {
    @Test public void everyInstalledDimensionMarkerHasThreeUsableFaces() throws Exception {
        String path = System.getProperty("gregtech.referenceJar");
        Assume.assumeNotNull(path);
        try (JarFile jar = new JarFile(path)) {
            ClassNode node = new ClassNode();
            try (InputStream in = jar.getInputStream(jar.getJarEntry("gtneioreplugin/util/DimensionHelper.class"))) {
                new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
            }
            List<Map<String, String>> records = new ArrayList<>();
            for (MethodNode method : node.methods) if (method.name.equals("<clinit>")) {
                List<String> literals = new ArrayList<>();
                for (AbstractInsnNode instruction : method.instructions.toArray()) {
                    if (instruction instanceof LdcInsnNode && ((LdcInsnNode) instruction).cst instanceof String)
                        literals.add((String) ((LdcInsnNode) instruction).cst);
                    if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).name.equals("register")
                            && ((MethodInsnNode) instruction).owner.equals(node.name)) {
                        assertEquals(5, literals.size());
                        Map<String, String> record = new LinkedHashMap<>();
                        record.put("name", literals.get(0)); record.put("internal", literals.get(1));
                        record.put("abbreviation", literals.get(3)); records.add(record); literals.clear();
                    }
                }
            }
            assertEquals("43 GT markers; EndAsteroid shares the End's actual dimension", 43, records.size());
            BufferedImage sheet = new BufferedImage(720, 6 * 84, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = sheet.createGraphics(); g.setColor(new Color(35,39,44)); g.fillRect(0,0,sheet.getWidth(),sheet.getHeight());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            try {
                for (int i = 0; i < records.size(); i++) {
                    Map<String, String> record = records.get(i);
                    String encoded = ProspectingTextures.dimensionIcon(record.get("abbreviation"), resource -> {
                        java.util.jar.JarEntry entry = jar.getJarEntry(resource.substring(1));
                        return entry == null ? null : jar.getInputStream(entry);
                    });
                    assertFalse(record.get("name"), encoded.isEmpty());
                    BufferedImage icon = ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(encoded)));
                    assertEquals(32, icon.getWidth()); assertEquals(32, icon.getHeight());
                    assertTrue(record.get("name"), (icon.getRGB(16,16) >>> 24) > 0);
                    int x = (i % 9) * 80, y = (i / 9) * 84;
                    g.drawImage(icon, x+16,y+4,48,48,null); g.setColor(Color.WHITE);
                    g.drawString(record.get("abbreviation"), x+26,y+69);
                    record.put("icon", encoded);
                }
            } finally { g.dispose(); }
            File output = new File("build/reports/prospecting"); assertTrue(output.isDirectory() || output.mkdirs());
            ImageIO.write(sheet, "png", new File(output,"dimension-icons.png"));
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(output,"dimension-icons.json")), "UTF-8")) {
                new Gson().toJson(records, writer);
            }
            // Report actual GT enum constants for the cross-project Viewer coverage audit.
            ClassNode definitions = new ClassNode();
            try (InputStream in = jar.getInputStream(jar.getJarEntry("galacticgreg/api/enums/DimensionDef.class"))) {
                new ClassReader(in).accept(definitions, ClassReader.SKIP_CODE);
            }
            List<String> keys = new ArrayList<>();
            for (FieldNode field : definitions.fields) if ((field.access & Opcodes.ACC_ENUM) != 0) keys.add(field.name);
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(new File(output,"dimension-keys.json")), "UTF-8")) {
                new Gson().toJson(keys, writer);
            }
        }
    }
}
