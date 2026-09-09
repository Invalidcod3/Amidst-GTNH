package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.*;
import org.objectweb.asm.tree.*;
import org.apache.commons.lang3.tuple.Pair;
import net.minecraftforge.fluids.Fluid;

/** Runs the installed GT method after substituting only the surrounding world and config objects. */
public class InstalledProspectingTest {
    public static class Provider { public int field_76574_g; }
    public static class World {
        public Provider field_73011_w = new Provider();
        public long seed;
        public long func_72905_C() { return seed; }
    }
    public static class Mod { public static Proxy proxy = new Proxy(); }
    public static class Proxy { public Dimensions mUndergroundOil = new Dimensions(); }
    public static class Dimensions { public Config GetDimension(int id) { return new Config(); } }
    public static class Config {
        public Oil getRandomFluid(Random random) { return new Oil(100_000 + random.nextInt(3_000_000)); }
    }
    public static class Oil {
        private final int base;
        Oil(int base) { this.base = base; }
        public Fluid getFluid() { return new Fluid("fixture"); }
        public int getRandomAmount(Random random) { return base + random.nextInt(1_000_000); }
    }
    public static class Xstr extends Random {
        final GtnhXstrRandom delegate;
        public Xstr(long seed) { delegate = new GtnhXstrRandom(seed); }
        @Override public int next(int bits) { return delegate.next(bits); }
        @Override public int nextInt(int bound) { return delegate.nextInt(bound); }
    }
    @Test public void batchedFieldsMatchInstalledMethodIncludingNegativeCoordinates() throws Exception {
        String path = System.getProperty("gregtech.referenceJar");
        Assume.assumeNotNull(path);
        Class<?> reference = extract(path);
        java.lang.reflect.Method method = reference.getMethod("getPristineAmount", World.class, int.class, int.class);
        for (long seed : new long[] {0, 1, -1, 123456789012345L, Long.MIN_VALUE}) {
            for (int dimension : new int[] {0, -1, 64, -1019}) {
                for (int base : new int[] {-65536, -16, -8, 0, 8, 1024}) {
                    World world = new World(); world.seed = seed; world.field_73011_w.field_76574_g = dimension;
                    int cx = base, cz = -base - 8;
                    GtnhXstrRandom rng = new GtnhXstrRandom(seed + dimension * 2L + (cx >> 3) + 8267L * (cz >> 3));
                    Oil oil = new Config().getRandomFluid(rng);
                    int[] batch = ProspectingService.fieldAmounts(oil.getRandomAmount(rng), rng);
                    for (int i = 0; i < 64; i++) {
                        Pair<?, ?> expected = (Pair<?, ?>) method.invoke(null, world, cx + i / 8, cz + i % 8);
                        assertEquals("chunk " + (cx + i / 8) + "," + (cz + i % 8), expected.getRight(), batch[i]);
                    }
                }
            }
        }
    }
    private static Class<?> extract(String path) throws Exception {
        String original = "gregtech/common/UndergroundOil", generated = "amidst/gtnh/worker/ExtractedUndergroundOil";
        ClassNode node = new ClassNode();
        try (JarFile jar = new JarFile(path); InputStream in = jar.getInputStream(jar.getJarEntry(original + ".class"))) {
            new ClassReader(in).accept(node, ClassReader.SKIP_FRAMES);
        }
        Map<String, String> names = new HashMap<>();
        names.put(original, generated);
        String[] from = {"net/minecraft/world/World", "net/minecraft/world/WorldProvider", "gregtech/GTMod",
                "gregtech/common/GTProxy", "gregtech/api/objects/GTUODimensionList", "gregtech/api/objects/GTUODimension",
                "gregtech/api/objects/GTUOFluid", "gregtech/api/objects/XSTR"};
        Class<?>[] to = {World.class, Provider.class, Mod.class, Proxy.class, Dimensions.class, Config.class, Oil.class, Xstr.class};
        for (int i = 0; i < from.length; i++) names.put(from[i], Type.getInternalName(to[i]));
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        ClassVisitor out = new RemappingClassAdapter(writer, new SimpleRemapper(names));
        out.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, original, null, "java/lang/Object", null);
        boolean found = false;
        for (Object item : node.methods) {
            MethodNode method = (MethodNode)item;
            if (method.name.equals("getPristineAmount")) { method.accept(out); found = true; }
        }
        assertTrue("The installed GT method must remain available", found);
        out.visitEnd(); byte[] bytes = writer.toByteArray();
        class Loader extends ClassLoader {
            Loader() { super(InstalledProspectingTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new Loader().define();
    }
}
