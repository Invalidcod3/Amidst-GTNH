package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.jar.JarFile;
import com.google.common.collect.*;
import org.junit.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.tree.*;

/** Reproduces the unloaded-world crash with the installed GT method, then compares loaded behavior. */
public class InstalledOilDimensionTest {
    public static class Config {
        public String Dimension;
        Config(String value) { Dimension = value; }
    }
    public static class WorldProviderMoon {}
    public static class Manager {
        static WorldProviderMoon provider;
        public static WorldProviderMoon getProvider(int id) { return provider; }
    }
    public static class Base {
        public BiMap<String, Config> fDimensionList = HashBiMap.create();
        public int[] blackList = {-1,1};
        public boolean CheckBlackList(int id) { return Arrays.binarySearch(blackList, id) >= 0; }
    }
    @Test public void installedGetterCrashesUnloadedButNewResolverMatchesItsLoadedResults() throws Exception {
        String path = System.getProperty("gregtech.referenceJar"); Assume.assumeNotNull(path);
        ClassNode node = new ClassNode();
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry("gregtech/api/objects/GTUODimensionList.class"))) {
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
        }
        String generated = "amidst/gtnh/worker/ExtractedOilDimensions";
        Map<String,String> mapping = new HashMap<>(); mapping.put(node.name, generated);
        mapping.put("gregtech/api/objects/GTUODimension", Type.getInternalName(Config.class));
        mapping.put("net/minecraft/world/WorldProvider", Type.getInternalName(WorldProviderMoon.class));
        mapping.put("net/minecraftforge/common/DimensionManager", Type.getInternalName(Manager.class));
        node.fields.clear(); node.interfaces.clear(); node.innerClasses.clear();
        node.methods.removeIf(method -> !method.name.equals("GetDimension"));
        node.superName = Type.getInternalName(Base.class); node.version = Opcodes.V1_8;
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, node.superName, "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN); node.methods.add(constructor);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(new RemappingClassAdapter(writer, new SimpleRemapper(mapping)));
        byte[] bytes = writer.toByteArray();
        Class<?> extracted = new ClassLoader(getClass().getClassLoader()) {
            Class<?> load() { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }.load();
        Base config = (Base) extracted.getConstructor().newInstance();
        Config moon = new Config("WorldProviderMoon");
        config.fDimensionList.put("Moon", moon); config.fDimensionList.put("-777", new Config("-777"));
        Method method = extracted.getMethod("GetDimension", int.class);
        Manager.provider = null;
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> method.invoke(config, -28));
        assertTrue(failure.getCause() instanceof NullPointerException);
        String providerName = WorldProviderMoon.class.getName();
        assertSame(moon, ProspectingDimensions.resolveFluid(config.fDimensionList, config.blackList, -28, providerName));
        Manager.provider = new WorldProviderMoon();
        try {
            for (int id : new int[]{-1,1,0,-28,-777}) assertSame(method.invoke(config, id),
                    ProspectingDimensions.resolveFluid(config.fDimensionList, config.blackList, id, providerName));
        } finally { Manager.provider = null; }
    }
}
