package amidst.gtnh.worker;

import java.lang.reflect.*;

final class ProspectingReflection {
    private ProspectingReflection() {}
    static Object field(Object target, String name) throws ReflectiveOperationException {
        Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
        Field f = c.getField(name);
        return f.get(target instanceof Class ? null : target);
    }
    static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        Class<?> c = target instanceof Class ? (Class<?>) target : target.getClass();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            boolean match = true;
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                Class<?> t = types[i];
                if (t == int.class) t = Integer.class;
                else if (t == long.class) t = Long.class;
                else if (t == float.class) t = Float.class;
                else if (t == boolean.class) t = Boolean.class;
                if (args[i] != null && !t.isInstance(args[i])) match = false;
            }
            if (match) {
                m.setAccessible(true);
                return m.invoke(target instanceof Class ? null : target, args);
            }
        }
        throw new NoSuchMethodException(c.getName() + "." + name);
    }
    static Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name); }
}
