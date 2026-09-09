package amidst.gtnh.worker.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

public class IntegratedServerTickTransformerTest {
    public static final String TARGET = "net.minecraft.server.integrated.IntegratedServer";
    public static final String FIXTURE = "amidst.gtnh.worker.fixture.IntegratedServerLoopFixture";
    private static final String HOOK = "amidst/gtnh/worker/WorkerTickHooks";
    private final IntegratedServerTickTransformer transformer = new IntegratedServerTickTransformer();

    @Test
    public void patchesActualForgeMinecraftClassWithoutChangingOriginalInstructions() throws Exception {
        byte[] original = readClass(TARGET);
        ClassNode before = parse(original);
        ClassNode after = parse(transformer.transform(TARGET, TARGET, original));
        assertEquals(before.methods.size(), after.methods.size());
        MethodNode tick = findTick(after);
        assertEveryReturnHasHook(tick);
        // Verification uses generic JVM types, without resolving/initializing Minecraft.
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(after.name, tick);
        for (int i = 0; i < after.methods.size(); i++) {
            MethodNode patched = after.methods.get(i);
            for (AbstractInsnNode instruction : patched.instructions.toArray()) {
                if (isHook(instruction)) {
                    patched.instructions.remove(instruction);
                }
            }
            assertEquals("Original bytecode changed in " + patched.name,
                    trace(before.methods.get(i)), trace(patched));
        }
    }

    @Test
    public void recognizesSrgMethodUsedInInstalledForge() throws Exception {
        ClassNode runtime = parse(readClass(TARGET));
        findTick(runtime).name = "func_71217_p";
        ClassWriter writer = new ClassWriter(0);
        runtime.accept(writer);
        // LaunchWrapper passes the deobfuscated class name as transformedName.
        ClassNode patched = parse(transformer.transform("obfuscated.Class", TARGET, writer.toByteArray()));
        MethodNode tick = findTick(patched);
        assertEquals("func_71217_p", tick.name);
        assertEveryReturnHasHook(tick);
        new Analyzer<BasicValue>(new BasicVerifier()).analyze(patched.name, tick);
    }

    @Test
    public void patchesAllReturnPathsAndIsIdempotent() throws Exception {
        byte[] patched = transformer.transform(FIXTURE, TARGET, readClass(FIXTURE));
        assertEquals(2, assertEveryReturnHasHook(findTick(parse(patched))));
        assertSame(patched, transformer.transform(FIXTURE, TARGET, patched));
    }

    @Test
    public void preservesUnknownMinecraftShapeInsteadOfBreakingWorldStartup() throws Exception {
        ClassNode replaced = parse(readClass(FIXTURE));
        findTick(replaced).name = "anotherCoremodReplacedTick";
        ClassWriter writer = new ClassWriter(0);
        replaced.accept(writer);
        byte[] original = writer.toByteArray();
        assertSame(original, transformer.transform(TARGET, TARGET, original));
    }

    @Test
    public void leavesDedicatedServerAndUnrelatedClassesUntouched() throws Exception {
        String dedicated = "net.minecraft.server.MinecraftServer";
        byte[] original = readClass(dedicated);
        assertSame(original, transformer.transform(dedicated, dedicated, original));
        assertSame(null, transformer.transform(TARGET, TARGET, null));
    }

    public static byte[] readClass(String name) throws Exception {
        try (InputStream in = IntegratedServerTickTransformerTest.class.getResourceAsStream(
                "/" + name.replace('.', '/') + ".class")) {
            assertNotNull("Missing compiled fixture: " + name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        }
    }

    private static ClassNode parse(byte[] bytes) {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode findTick(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ("tick".equals(method.name) || "func_71217_p".equals(method.name)) {
                return method;
            }
        }
        throw new AssertionError("Missing tick method");
    }

    private static int assertEveryReturnHasHook(MethodNode tick) {
        int returns = 0;
        int hooks = 0;
        for (AbstractInsnNode instruction : tick.instructions.toArray()) {
            if (isHook(instruction)) {
                hooks++;
            }
            if (instruction.getOpcode() == Opcodes.RETURN) {
                returns++;
                AbstractInsnNode previous = instruction.getPrevious();
                while (previous != null && previous.getOpcode() < 0) {
                    previous = previous.getPrevious();
                }
                assertTrue("Unpatched return path", isHook(previous));
            }
        }
        assertTrue("No return paths tested", returns > 0);
        assertEquals(returns, hooks);
        return returns;
    }

    private static boolean isHook(AbstractInsnNode instruction) {
        return instruction instanceof MethodInsnNode
                && HOOK.equals(((MethodInsnNode) instruction).owner)
                && "onIntegratedServerTickEnd".equals(((MethodInsnNode) instruction).name);
    }

    private static List<?> trace(MethodNode method) {
        Textifier text = new Textifier();
        method.accept(new TraceMethodVisitor(text));
        return new ArrayList<Object>(text.getText());
    }
}
