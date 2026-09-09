package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.JarFile;
import org.junit.Assume;
import org.junit.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;
import net.minecraft.world.biome.BiomeGenBase;

/** Execute the substitution loop extracted from the installed GTNH JAR, not a copied algorithm. */
public class InstalledRossRulesTest {
    public enum Mods {
        Thaumcraft;
        static boolean active;
        public boolean isModLoaded() { return active; }
    }
    public static class Configuration { public static Options crossModInteractions = new Options(); }
    public static class Options { public boolean disableMagicalForest; }
    public static class Handler {
        static int taint, magical;
        public static boolean isTaintBiome(int id) { return id == taint; }
        public static boolean isMagicalForestBiome(int id) { return id == magical; }
    }

    @Test public void runtimeGeneratorLoopMatchesRulesForEveryBiomeAndConfiguration() throws Exception {
        String path = System.getProperty("gregtech.referenceJar");
        Assume.assumeNotNull(path);
        Class<?> reference = extract(path);
        Object fixture = reference.getConstructor().newInstance();
        BiomeGenBase[] inputs = Arrays.stream(BiomeGenBase.getBiomeGenArray()).filter(Objects::nonNull).toArray(BiomeGenBase[]::new);
        for (boolean loaded : new boolean[] {false, true}) for (boolean disabled : new boolean[] {false, true}) {
            for (int taint : new int[] {BiomeGenBase.desert.biomeID, BiomeGenBase.mushroomIslandShore.biomeID}) {
                Mods.active = loaded;
                Configuration.crossModInteractions.disableMagicalForest = disabled;
                Handler.taint = taint;
                Handler.magical = BiomeGenBase.forest.biomeID;
                Ross128bBiomeRules rules = new Ross128bBiomeRules(loaded ? taint : -1,
                        loaded ? Handler.magical : -1, disabled);
                BiomeGenBase[] expected = (BiomeGenBase[]) reference.getMethod("apply", BiomeGenBase[].class)
                        .invoke(fixture, (Object) inputs.clone());
                for (int i = 0; i < inputs.length; i++) assertSame(expected[i], rules.apply(inputs[i]));
            }
        }
    }

    private static Class<?> extract(String path) throws Exception {
        String original = "bwcrossmod/galacticraft/planets/ross128b/ChunkProviderRoss128b";
        ClassNode node = new ClassNode();
        try (JarFile jar = new JarFile(Paths.get(path).toFile()); InputStream in = jar.getInputStream(jar.getJarEntry(original + ".class"))) {
            new ClassReader(in).accept(node, 0);
        }
        MethodNode provide = null;
        for (Object m : node.methods) if (((MethodNode) m).name.equals("func_73154_d")) provide = (MethodNode) m;
        assertNotNull(provide);
        AbstractInsnNode start = null;
        LabelNode end = null;
        for (AbstractInsnNode insn : provide.instructions.toArray()) {
            if (start == null && insn.getOpcode() == Opcodes.ICONST_0 && insn.getNext() instanceof VarInsnNode
                    && ((VarInsnNode) insn.getNext()).var == 5) start = insn;
            if (start != null && insn.getOpcode() == Opcodes.IF_ICMPGE) { end = ((JumpInsnNode) insn).label; break; }
        }
        assertNotNull(start); assertNotNull(end);
        String generated = "amidst/gtnh/worker/ExtractedRossRules";
        String biome = "net/minecraft/world/biome/BiomeGenBase", array = "[L" + biome + ";";
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        Map<String, String> names = new HashMap<>();
        names.put(original, generated);
        names.put("gregtech/api/enums/Mods", Type.getInternalName(Mods.class));
        names.put("bartworks/common/configs/Configuration", Type.getInternalName(Configuration.class));
        names.put("bartworks/common/configs/Configuration$CrossModInteractions", Type.getInternalName(Options.class));
        names.put("bwcrossmod/thaumcraft/util/ThaumcraftHandler", Type.getInternalName(Handler.class));
        String[] fields = {"field_76756_M", "biomeID", "field_76789_p", "mushroomIsland", "field_76788_q", "mushroomIslandShore",
                "field_76768_g", "taiga", "field_150576_N", "stoneBeach", "field_150583_P", "birchForest"};
        for (int i = 0; i < fields.length; i += 2) names.put(biome + "." + fields[i], fields[i + 1]);
        ClassVisitor out = new RemappingClassAdapter(writer, new SimpleRemapper(names));
        out.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, original, null, "java/lang/Object", null);
        out.visitField(Opcodes.ACC_PUBLIC, "biomesForGeneration", array, null, null).visitEnd();
        MethodVisitor init = out.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode(); init.visitVarInsn(Opcodes.ALOAD, 0); init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN); init.visitMaxs(0, 0); init.visitEnd();
        MethodVisitor apply = out.visitMethod(Opcodes.ACC_PUBLIC, "apply", "(" + array + ")" + array, null, null);
        apply.visitCode(); apply.visitVarInsn(Opcodes.ALOAD, 0); apply.visitVarInsn(Opcodes.ALOAD, 1);
        apply.visitFieldInsn(Opcodes.PUTFIELD, original, "biomesForGeneration", array);
        for (AbstractInsnNode insn = start; insn != end; insn = insn.getNext())
            if (!(insn instanceof FrameNode) && !(insn instanceof LineNumberNode)) insn.accept(apply);
        end.accept(apply);
        apply.visitVarInsn(Opcodes.ALOAD, 0); apply.visitFieldInsn(Opcodes.GETFIELD, original, "biomesForGeneration", array);
        apply.visitInsn(Opcodes.ARETURN); apply.visitMaxs(0, 0); apply.visitEnd(); out.visitEnd();
        byte[] bytes = writer.toByteArray();
        class ExtractLoader extends ClassLoader {
            ExtractLoader() { super(InstalledRossRulesTest.class.getClassLoader()); }
            Class<?> define() { return defineClass(generated.replace('/', '.'), bytes, 0, bytes.length); }
        }
        return new ExtractLoader().define();
    }
}
