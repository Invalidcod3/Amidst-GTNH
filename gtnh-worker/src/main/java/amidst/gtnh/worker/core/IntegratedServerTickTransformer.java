package amidst.gtnh.worker.core;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * One narrowly scoped 1.7.10 patch: drain Worker requests after the integrated
 * server's tick, including its paused path. The original pause/save/world-tick
 * logic is untouched. See docs/archive/worker-troubleshooting.md before changing this.
 */
public final class IntegratedServerTickTransformer implements IClassTransformer {
    private static final Logger LOG = LogManager.getLogger("amidstgtnhworker-core");
    private static final String TARGET = "net.minecraft.server.integrated.IntegratedServer";
    private static final String HOOK_OWNER = "amidst/gtnh/worker/WorkerTickHooks";
    private static final String HOOK_METHOD = "onIntegratedServerTickEnd";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null || !TARGET.equals(transformedName)) {
            return basicClass;
        }
        try {
            ClassNode node = new ClassNode(Opcodes.ASM5);
            new ClassReader(basicClass).accept(node, 0);
            MethodNode tick = null;
            for (MethodNode method : node.methods) {
                // MCP in development; SRG after FML deobfuscation in a modpack.
                if ("()V".equals(method.desc)
                        && ("tick".equals(method.name) || "func_71217_p".equals(method.name))) {
                    if (tick != null) {
                        throw new IllegalStateException("Multiple integrated-server tick methods");
                    }
                    tick = method;
                }
            }
            if (tick == null) {
                throw new IllegalStateException("IntegratedServer tick()V / func_71217_p()V missing");
            }
            int returns = 0;
            int inserted = 0;
            for (AbstractInsnNode instruction : tick.instructions.toArray()) {
                if (instruction.getOpcode() == Opcodes.RETURN) {
                    returns++;
                    AbstractInsnNode previous = instruction.getPrevious();
                    while (previous != null && previous.getOpcode() < 0) {
                        previous = previous.getPrevious();
                    }
                    if (!isHook(previous)) {
                        tick.instructions.insertBefore(instruction, new MethodInsnNode(
                                Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_METHOD, "()V", false));
                        inserted++;
                    }
                }
            }
            if (returns == 0) {
                throw new IllegalStateException("Integrated-server tick has no normal return");
            }
            if (inserted == 0) {
                return basicClass; // Idempotent if another launch pass sees our patch.
            }
            // A static ()V call neither consumes nor adds stack slots. Preserve
            // existing frames/maxima; COMPUTE_FRAMES could load Minecraft too early.
            ClassWriter writer = new ClassWriter(0);
            node.accept(writer);
            LOG.info("Installed paused-world Worker hook in {}.{}{} ({} return paths)",
                    TARGET, tick.name, tick.desc, returns);
            return writer.toByteArray();
        } catch (RuntimeException failure) {
            // This optional service must not prevent a world from loading if
            // another coremod has replaced the method with an unsupported shape.
            LOG.error("Cannot install paused-world Worker hook; ESC prediction may stall. "
                    + "Ordinary Forge tick processing remains available. "
                    + "See docs/archive/worker-troubleshooting.md.", failure);
            return basicClass;
        }
    }

    private static boolean isHook(AbstractInsnNode instruction) {
        if (!(instruction instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode call = (MethodInsnNode) instruction;
        return call.getOpcode() == Opcodes.INVOKESTATIC && HOOK_OWNER.equals(call.owner)
                && HOOK_METHOD.equals(call.name) && "()V".equals(call.desc);
    }
}
