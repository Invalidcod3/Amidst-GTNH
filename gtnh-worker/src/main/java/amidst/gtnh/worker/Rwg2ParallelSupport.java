package amidst.gtnh.worker;

import java.io.InputStream;
import java.lang.reflect.*;
import java.util.*;

/** Only the audited 2.0.6 callbacks may share their read-only biome/surface parameters. */
final class Rwg2ParallelSupport {
    private static Boolean supported;

    static synchronized boolean available() {
        if (supported != null) return supported;
        try {
            verify();
            supported = true;
            AmidstGtnhWorkerLog.LOG.info(
                    "RWG2 2.0.6 detached biome sampling can run on background threads");
        } catch (Exception | LinkageError failure) {
            supported = false;
            AmidstGtnhWorkerLog.LOG.warn(
                    "RWG2 parallel replay could not be verified; retaining game-thread sampling",
                    failure);
        }
        return supported;
    }

    private static void verify() throws Exception {
        Properties approved = new Properties();
        ClassLoader loader = Rwg2ParallelSupport.class.getClassLoader();
        try (InputStream in = loader.getResourceAsStream("rwg2-parallel-sha256.properties")) {
            if (in == null) throw new IllegalStateException("Missing RWG2 parallel audit manifest");
            approved.load(in);
        }
        // Source review covers sampler helpers as well as the manager/generator entry points.
        // Read resource bytes without initializing optional support/decoration classes.
        for (String name : approved.stringPropertyNames()) {
            try (InputStream in = loader.getResourceAsStream(name)) {
                if (in == null) throw new IllegalStateException("Missing RWG2 class " + name);
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                Rwg2Runtime.verify(bytes.toByteArray(), approved.getProperty(name));
            }
        }
        Class<?> biome = Class.forName("rwg2.biomes.realistic.RealisticBiomeBase");
        Class<?> terrain = Class.forName("rwg2.terrain.TerrainBase");
        Class<?> surface = Class.forName("rwg2.surface.SurfaceBase");
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        Method get = biome.getMethod("getBiome", int.class);
        for (int i = 0; i < 256; i++) {
            Object entry = get.invoke(null, i);
            if (entry != null) inspect(entry, approved, visited, terrain, surface);
        }
    }

    private static void inspect(
            Object value,
            Properties approved,
            Set<Object> visited,
            Class<?> terrain,
            Class<?> surface)
            throws Exception {
        if (!visited.add(value)) return;
        if (!approved.containsKey(value.getClass().getName().replace('.', '/') + ".class"))
            throw new IllegalStateException(
                    "Unaudited RWG2 callback " + value.getClass().getName());
        for (Class<?> type = value.getClass(); type != Object.class; type = type.getSuperclass())
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object child = field.get(value);
                if (child instanceof Object[])
                    for (Object item : (Object[]) child)
                        inspectHelper(item, approved, visited, terrain, surface);
                else inspectHelper(child, approved, visited, terrain, surface);
            }
    }

    private static void inspectHelper(
            Object child,
            Properties approved,
            Set<Object> visited,
            Class<?> terrain,
            Class<?> surface)
            throws Exception {
        if (child != null && (terrain.isInstance(child) || surface.isInstance(child)))
            inspect(child, approved, visited, terrain, surface);
    }
}
