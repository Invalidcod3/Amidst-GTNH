package amidst.gtnh.worker;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import net.minecraft.world.biome.BiomeGenBase;

/**
 * Conservative dependency check for RWG's final biome-array argument.
 * A callback is eligible only if it ignores that argument, or forwards it
 * directly to equally eligible callbacks on the actual registered objects.
 * Reading, writing, aliasing or escaping the array requires native replay.
 * No biome IDs, climate thresholds or names decide the result.
 *
 * This analyzes array effects in RWG's isolated prediction pipeline, not general
 * Java purity. As with full replay, arbitrary global side effects / Forge
 * post-generation events are outside the predictor's contract.
 */
final class RwgSurfaceEffects {
    private final Function<Class<?>, byte[]> bytecode;
    private final Map<Object, Boolean> results = new IdentityHashMap<>();
    private final Map<Class<?>, ClassNode> classes = new IdentityHashMap<>();
    private String reason;

    RwgSurfaceEffects(Function<Class<?>, byte[]> bytecode) { this.bytecode = bytecode; }

    boolean leavesBiomesUntouched(Object biome) {
        Boolean cached = results.get(biome);
        if (cached != null) return cached;
        boolean safe = false;
        reason = "no supported rReplace signature";
        try {
            for (Method method : biome.getClass().getMethods()) {
                Class<?>[] args = method.getParameterTypes();
                if (method.getName().equals("rReplace") && args.length == 14
                        && args[0] == net.minecraft.block.Block[].class
                        && args[13] == BiomeGenBase[].class) {
                    safe = inspect(biome, method, 0);
                    break;
                }
            }
        } catch (Exception | LinkageError e) {
            reason = e.getClass().getSimpleName();
        }
        results.put(biome, safe);
        String id = "?";
        try { id = String.valueOf(biome.getClass().getField("biomeID").get(biome)); }
        catch (ReflectiveOperationException ignored) { /* Diagnostics only. */ }
        AmidstGtnhWorkerLog.LOG.info("RWG adaptive {}: {} realisticId={} ({})",
                safe ? "biome-only" : "native replay", biome.getClass().getName(),
                id,
                safe ? "surface callbacks leave biome array untouched" : reason);
        return safe;
    }

    private boolean inspect(Object instance, Method method, int depth) throws Exception {
        if (depth > 8) return reject("recursive/unknown delegation");
        ClassNode owner = classes.get(method.getDeclaringClass());
        if (owner == null) {
            byte[] bytes = bytecode.apply(method.getDeclaringClass());
            if (bytes == null) return reject("final runtime bytecode unavailable");
            owner = new ClassNode();
            new ClassReader(bytes).accept(owner, ClassReader.SKIP_DEBUG);
            classes.put(method.getDeclaringClass(), owner);
        }
        MethodNode code = null;
        for (Object entry : owner.methods) {
            MethodNode candidate = (MethodNode) entry;
            if (candidate.name.equals(method.getName()) && candidate.desc.equals(Type.getMethodDescriptor(method))) {
                code = candidate;
                break;
            }
        }
        if (code == null || (code.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_STATIC)) != 0)
            return reject("unsupported callback body");
        Type[] args = Type.getArgumentTypes(code.desc);
        if (args.length == 0 || !args[args.length - 1].equals(Type.getType(BiomeGenBase[].class)))
            return reject("unsupported biome argument");
        int arrayLocal = 1;
        for (int i = 0; i < args.length - 1; i++) arrayLocal += args[i].getSize();
        Frame[] frames = null;
        for (AbstractInsnNode insn = code.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.PUTFIELD || insn.getOpcode() == Opcodes.PUTSTATIC
                    || insn.getOpcode() == Opcodes.INVOKEDYNAMIC)
                return reject("callback changes object state or has dynamic dispatch");
            if (insn instanceof VarInsnNode && ((VarInsnNode) insn).var == 0 && insn.getOpcode() != Opcodes.ALOAD)
                return reject("callback aliases receiver");
            if (!(insn instanceof VarInsnNode) || ((VarInsnNode) insn).var != arrayLocal) continue;
            if (insn.getOpcode() != Opcodes.ALOAD) return reject("biome argument aliases another local");
            AbstractInsnNode next = insn.getNext();
            while (next != null && next.getOpcode() < 0) next = next.getNext();
            if (!(next instanceof MethodInsnNode)) return reject("callback reads/writes/escapes biome array");
            MethodInsnNode call = (MethodInsnNode) next;
            Type[] forwarded = Type.getArgumentTypes(call.desc);
            if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || forwarded.length == 0
                    || !Type.getReturnType(call.desc).equals(Type.VOID_TYPE)
                    || !forwarded[forwarded.length - 1].equals(Type.getType(BiomeGenBase[].class)))
                return reject("unsupported biome-array delegation");
            if (frames == null) frames = new Analyzer(new SourceInterpreter()).analyze(owner.name, code);
            Frame frame = frames[code.instructions.indexOf(call)];
            if (frame == null) return reject("unreachable/unknown delegation");
            SourceValue receiver = (SourceValue) frame.getStack(frame.getStackSize() - forwarded.length - 1);
            List<Object> targets = resolve(instance, receiver, code, frames, 0);
            if (targets.isEmpty()) return reject("cannot resolve callback receiver");
            for (Object target : targets) {
                Method dispatch = null;
                for (Method candidate : target.getClass().getMethods()) {
                    if (candidate.getName().equals(call.name) && Type.getMethodDescriptor(candidate).equals(call.desc)) {
                        dispatch = candidate;
                        break;
                    }
                }
                if (dispatch == null || !inspect(target, dispatch, depth + 1)) return false;
            }
        }
        return true;
    }

    // Resolve only this, instance fields, and elements of an instance array.
    // Dynamic factory calls, arbitrary aliases, merged sources etc. fall back.
    private List<Object> resolve(Object instance, SourceValue value, MethodNode code, Frame[] frames, int depth)
            throws Exception {
        if (depth > 12 || value.insns.size() != 1) return Collections.emptyList();
        AbstractInsnNode source = (AbstractInsnNode) value.insns.iterator().next();
        Frame frame = frames[code.instructions.indexOf(source)];
        if (source.getOpcode() == Opcodes.ALOAD) {
            int local = ((VarInsnNode) source).var;
            return local == 0 ? Collections.singletonList(instance)
                    : resolve(instance, (SourceValue) frame.getLocal(local), code, frames, depth + 1);
        }
        List<Object> result = new ArrayList<>();
        if (source.getOpcode() == Opcodes.GETFIELD) {
            FieldInsnNode field = (FieldInsnNode) source;
            for (Object parent : resolve(instance, (SourceValue) frame.getStack(frame.getStackSize() - 1), code, frames, depth + 1)) {
                Class<?> declaring = parent.getClass();
                while (declaring != null && !Type.getInternalName(declaring).equals(field.owner)) declaring = declaring.getSuperclass();
                if (declaring == null) return Collections.emptyList();
                Field reflected = declaring.getDeclaredField(field.name);
                reflected.setAccessible(true);
                Object child = reflected.get(parent);
                if (child == null) return Collections.emptyList();
                result.add(child);
            }
        } else if (source.getOpcode() == Opcodes.AALOAD) {
            for (Object array : resolve(instance, (SourceValue) frame.getStack(frame.getStackSize() - 2), code, frames, depth + 1)) {
                for (int i = 0; i < Array.getLength(array); i++) {
                    Object child = Array.get(array, i);
                    if (child == null) return Collections.emptyList();
                    result.add(child);
                }
            }
        }
        return result;
    }

    private boolean reject(String description) { reason = description; return false; }
}
