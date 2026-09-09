package amidst.gtnh.worker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;

/** Observes, but never changes, the bytecode that will actually be defined. */
public final class RwgRuntimeClasses implements IClassTransformer {
    private static final Map<String, byte[]> CLASSES = new ConcurrentHashMap<>();

    static void install() {
        Launch.classLoader.registerTransformer(RwgRuntimeClasses.class.getName());
    }

    @Override public byte[] transform(String name, String transformedName, byte[] bytes) {
        if (bytes != null && transformedName != null && transformedName.startsWith("rwg.")) {
            List<IClassTransformer> chain = Launch.classLoader.getTransformers();
            // An earlier observer cannot certify the result of a later coremod.
            // Classes loaded before installation are deliberately not certified.
            if (!chain.isEmpty() && chain.get(chain.size() - 1) == this) {
                CLASSES.put(transformedName, bytes.clone());
            }
        }
        return bytes;
    }

    static byte[] get(Class<?> type) {
        return type.getClassLoader() == Launch.classLoader ? CLASSES.get(type.getName()) : null;
    }
}
