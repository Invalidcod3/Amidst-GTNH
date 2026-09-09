package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

/** Inspect actual RWG callback instructions, adapting only input types to fixtures. */
public class InstalledRwgEffectsTest {
    private final Map<Class<?>, byte[]> definitions = new HashMap<>();

    @Test public void actualBopDelegationRecognizesOrdinaryAndBiomeWritingSurfaces() throws Exception {
        Assume.assumeNotNull(System.getProperty("rwg.referenceJar"));
        // Retain the same deliberate version/hash gate as the native-call tests.
        new InstalledRwgReferenceTest().requireReferenceJar();
        Class<?> support = extract("rwg/support/RealisticBiomeSupport", "rReplace");
        Object grass = extract("rwg/surface/SurfaceGrassland", "paintTerrain").getConstructor().newInstance();
        Object dune = extract("rwg/surface/SurfaceDuneValley", "paintTerrain").getConstructor().newInstance();
        Object ordinary = support.getConstructor().newInstance(), sensitive = support.getConstructor().newInstance();
        set(ordinary, "surfaces", new RwgSurfaceEffectsTest.Painter[] {(RwgSurfaceEffectsTest.Painter) grass});
        set(ordinary, "surfacesLength", 1);
        set(sensitive, "surfaces", new RwgSurfaceEffectsTest.Painter[] {
                (RwgSurfaceEffectsTest.Painter) grass, (RwgSurfaceEffectsTest.Painter) dune});
        set(sensitive, "surfacesLength", 2);
        RwgSurfaceEffects effects = new RwgSurfaceEffects(definitions::get);
        assertTrue(effects.leavesBiomesUntouched(ordinary));
        assertFalse(effects.leavesBiomesUntouched(sensitive));
        int eligible = 0, sensitiveCount = 0;
        try (JarFile jar = new JarFile(System.getProperty("rwg.referenceJar"))) {
            java.util.Enumeration<java.util.jar.JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith("rwg/surface/Surface") || !name.endsWith(".class") || name.contains("$")) continue;
                Object painter = extract(name.substring(0, name.length() - 6), "paintTerrain").getConstructor().newInstance();
                Object biome = support.getConstructor().newInstance();
                set(biome, "surfaces", new RwgSurfaceEffectsTest.Painter[] {(RwgSurfaceEffectsTest.Painter) painter});
                set(biome, "surfacesLength", 1);
                if (effects.leavesBiomesUntouched(biome)) eligible++; else sensitiveCount++;
            }
        }
        assertTrue("Check actual RWG coverage instead of silently falling back everywhere", eligible >= 10);
        assertTrue(sensitiveCount >= 1);
        System.out.println("Installed RWG surface dependency coverage: biome-only=" + eligible + ", replay=" + sensitiveCount);
    }

    private Class<?> extract(String owner, String methodName) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        try (JarFile jar = new JarFile(System.getProperty("rwg.referenceJar"));
                InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            new ClassReader(in).accept(node, ClassReader.EXPAND_FRAMES);
        }
        node.methods.removeIf(method -> !method.name.equals(methodName));
        Set<String> fields = new HashSet<>();
        for (MethodNode method : node.methods) for (AbstractInsnNode instruction : method.instructions.toArray()) {
            if (instruction instanceof FieldInsnNode && ((FieldInsnNode) instruction).owner.equals(owner))
                fields.add(((FieldInsnNode) instruction).name);
            if (instruction instanceof MethodInsnNode && ((MethodInsnNode) instruction).owner.equals("rwg/util/NoiseGenerator")) {
                ((MethodInsnNode) instruction).setOpcode(Opcodes.INVOKEVIRTUAL);
                ((MethodInsnNode) instruction).itf = false;
            }
        }
        node.fields.removeIf(field -> !fields.contains(field.name));
        for (FieldNode field : node.fields) field.access &= ~Opcodes.ACC_FINAL;
        node.access &= ~Opcodes.ACC_ABSTRACT;
        node.superName = methodName.equals("paintTerrain") ? Type.getInternalName(RwgSurfaceEffectsTest.Painter.class) : "java/lang/Object";
        node.interfaces.clear();
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = init.maxLocals = 1;
        node.methods.add(init);
        String name = "amidst/gtnh/worker/Effect" + owner.substring(owner.lastIndexOf('/') + 1);
        Map<String, String> mapping = new HashMap<>();
        mapping.put("rwg/util/NoiseGenerator", Type.getInternalName(SurfaceSamplerFixture.Noise.class));
        mapping.put("rwg/util/CellNoise", "java/lang/Object");
        mapping.put("rwg/surface/SurfaceBase", Type.getInternalName(RwgSurfaceEffectsTest.Painter.class));
        mapping.put(owner, name);
        ClassWriter writer = new ClassWriter(0);
        node.accept(new RemappingClassAdapter(writer, new SimpleRemapper(mapping)));
        byte[] bytes = writer.toByteArray();
        Class<?> result = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(name.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
        definitions.put(result, bytes);
        return result;
    }

    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }
}
