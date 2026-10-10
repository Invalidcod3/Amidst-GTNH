import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Executes original selector instructions with save-free, controlled input APIs. */
public class AuditVillageSelectors {
    public static class Biome {
        public String field_76791_y = "Plains";
        public String biomeName = "Plains";
    }

    public static class World {
        public long seed;
        public final Random rand = new Random();
        public final Biome biome = new Biome();

        public Random func_72843_D(int x, int z, int salt) {
            rand.setSeed((long) x * 341873128712L + (long) z * 132897987541L + seed + salt);
            return rand;
        }

        public Biome func_72807_a(int x, int z) {
            return biome;
        }
    }

    public static class Config {
        public static int newVillageSpacingMedian = 40;
        public static int newVillageSpacingSpread = 10;
        public static int noVillagesRadius = 0;
        public static String[] spawnBiomesNames = {"Plains"};
    }

    public static class BiomeManager {
        public static void addVillageBiome(Biome biome, boolean allowed) {}
    }

    public interface Sampler {
        boolean isVillageLocationViable(int x, int z);

        default Biome getBiomeAt(int x, int z) {
            vnWorld.biome.biomeName = vnWorld.biome.field_76791_y;
            return vnWorld.biome;
        }

        default Biome getPredictedBiomeAt(int x, int z) {
            return getBiomeAt(x, z);
        }
    }

    public static class Descriptor {
        public final String kind;
        public final int x, z;

        public Descriptor(String kind, String type, int x, int z, String certainty) {
            this.kind = kind;
            this.x = x;
            this.z = z;
        }
    }

    static class Loader extends ClassLoader {
        Loader() {
            super(AuditVillageSelectors.class.getClassLoader());
        }

        Class<?> define(byte[] code) {
            return defineClass(null, code, 0, code.length);
        }
    }

    static final Loader loader = new Loader();

    static byte[] entry(Path jar, String name) throws Exception {
        try (JarFile file = new JarFile(jar.toFile())) {
            try (var in = file.getInputStream(file.getJarEntry(name + ".class"))) {
                return in.readAllBytes();
            }
        }
    }

    static Class<?> extract(
            byte[] bytes,
            String newName,
            Set<String> methods,
            boolean fields,
            boolean world,
            Map<String, String> aliases) {
        ClassNode original = new ClassNode();
        new ClassReader(bytes).accept(original, 0);
        ClassNode isolated = new ClassNode();
        isolated.version = Opcodes.V1_8;
        isolated.access = Opcodes.ACC_PUBLIC;
        isolated.name = original.name;
        isolated.superName = "java/lang/Object";
        if (fields) {
            for (FieldNode field : original.fields) {
                // Full mod/config initialization is outside this audit. Retain only selection
                // inputs and populate them explicitly without that initializer.
                if (!original.name.endsWith("/VillageGeneratorConfigHandler")
                        || Set.of(
                                        "newVillageSpacingMedian",
                                        "newVillageSpacingSpread",
                                        "noVillagesRadius",
                                        "spawnBiomesNames")
                                .contains(field.name)) isolated.fields.add(field);
            }
        }
        if (world)
            isolated.fields.add(
                    new FieldNode(
                            Opcodes.ACC_PUBLIC,
                            "field_75039_c",
                            "Lnet/minecraft/world/World;",
                            null,
                            null));
        for (MethodNode method : original.methods) {
            if (methods.contains(method.name)) isolated.methods.add(method);
        }
        if (!methods.contains("<init>")) {
            MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            ctor.instructions.add(
                    new MethodInsnNode(
                            Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            ctor.instructions.add(new InsnNode(Opcodes.RETURN));
            ctor.maxStack = 1;
            ctor.maxLocals = 1;
            isolated.methods.add(ctor);
        } else {
            isolated.methods.removeIf(m -> m.name.equals("<init>") && !m.desc.equals("()V"));
        }
        Map<String, String> mapping = new HashMap<>(aliases);
        mapping.put(original.name, newName);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        isolated.accept(new ClassRemapper(writer, new SimpleRemapper(mapping)));
        return loader.define(writer.toByteArray());
    }

    static Class<?> remapAll(byte[] bytes, String newName, Map<String, String> aliases) {
        ClassReader reader = new ClassReader(bytes);
        Map<String, String> mapping = new HashMap<>(aliases);
        mapping.put(reader.getClassName(), newName);
        ClassWriter writer = new ClassWriter(0);
        reader.accept(new ClassRemapper(writer, new SimpleRemapper(mapping)), 0);
        return loader.define(writer.toByteArray());
    }

    static MethodHandle selector(Class<?> type, Object instance) throws Exception {
        Method m = type.getDeclaredMethod("func_75047_a", int.class, int.class);
        m.setAccessible(true);
        return MethodHandles.lookup().unreflect(m).bindTo(instance);
    }

    static String coord(long key) {
        return "(" + (int) (key >> 32) + "," + (int) key + ")";
    }

    static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }

    static MethodHandle moonWorker, moonNative, vnNative;
    static Method rwgWorker, readRules;
    static Class<?> nativeConfig;
    static Object vnObject;
    static World moonWorld, vnWorld;

    static Set<Long> nativeVillages(long seed, int min, int max) throws Throwable {
        syncConfig();
        vnWorld.seed = seed;
        Set<Long> result = new TreeSet<>();
        for (int z = min; z <= max; z++)
            for (int x = min; x <= max; x++) {
                if ((boolean) vnNative.invokeExact(x, z)) result.add(key(x, z));
            }
        return result;
    }

    static Set<Long> workerVillages(long seed, int min, int max, boolean viable) throws Exception {
        syncConfig();
        List<Descriptor> result = new ArrayList<>();
        Sampler sampler = (x, z) -> viable;
        rwgWorker.invoke(
                null,
                seed,
                min * 16 + 4,
                min * 16 + 4,
                (long) max * 16 + 5,
                (long) max * 16 + 5,
                sampler,
                readRules.invoke(null, vnObject),
                false,
                result);
        Set<Long> coords = new TreeSet<>();
        for (Descriptor d : result) coords.add(key(Math.floorDiv(d.x, 16), Math.floorDiv(d.z, 16)));
        return coords;
    }

    static void syncConfig() throws ReflectiveOperationException {
        for (String name :
                List.of(
                        "newVillageSpacingMedian",
                        "newVillageSpacingSpread",
                        "noVillagesRadius",
                        "spawnBiomesNames")) {
            nativeConfig.getField(name).set(null, Config.class.getField(name).get(null));
        }
    }

    static void compareVillages(String label, long seed, int min, int max, boolean viable)
            throws Throwable {
        Set<Long> original = nativeVillages(seed, min, max);
        Set<Long> prediction = workerVillages(seed, min, max, viable);
        Set<Long> missed = new TreeSet<>(original);
        missed.removeAll(prediction);
        Set<Long> extra = new TreeSet<>(prediction);
        extra.removeAll(original);
        System.out.println(
                "Overworld CONTROLLED "
                        + label
                        + "; seed="
                        + seed
                        + " checked="
                        + ((long) max - min + 1) * ((long) max - min + 1)
                        + " VN="
                        + original.size()
                        + " Worker="
                        + prediction.size()
                        + " missed="
                        + missed.size()
                        + " extra="
                        + extra.size());
        if (!missed.isEmpty() || !extra.isEmpty()) {
            throw new AssertionError(
                    "Overworld mismatch: native-only="
                            + (missed.isEmpty() ? "none" : coord(missed.iterator().next()))
                            + " worker-only="
                            + (extra.isEmpty() ? "none" : coord(extra.iterator().next())));
        }
    }

    public static void main(String[] args) throws Throwable {
        if (args.length != 3 && args.length != 6) {
            throw new IllegalArgumentException(
                    "Usage: AuditVillageSelectors <worker-classes> <VillageNames-runtime.jar>"
                            + " <Galacticraft-runtime.jar> [median spread radius]");
        }
        Path classes = Path.of(args[0]);
        if (args.length == 6) {
            Config.newVillageSpacingMedian = Integer.parseInt(args[3]);
            Config.newVillageSpacingSpread = Integer.parseInt(args[4]);
            Config.noVillagesRadius = Integer.parseInt(args[5]);
        }
        if (Config.newVillageSpacingMedian < 1
                || Config.newVillageSpacingMedian > 100
                || Config.newVillageSpacingSpread < 1
                || Config.newVillageSpacingSpread >= Config.newVillageSpacingMedian
                || Config.noVillagesRadius < 0
                || Config.noVillagesRadius > 100000) {
            throw new IllegalArgumentException(
                    "Invalid controlled Village Names spacing/radius inputs");
        }
        System.out.println(
                "CONTROLLED config inputs: median="
                        + Config.newVillageSpacingMedian
                        + " spread="
                        + Config.newVillageSpacingSpread
                        + " radius="
                        + Config.noVillagesRadius);
        for (int i : new int[] {1, 2}) {
            Path jar = Path.of(args[i]);
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(Files.readAllBytes(jar)));
            System.out.println("Reference jar=" + jar.getFileName() + " SHA-256=" + hash);
        }
        Map<String, String> aliases = new HashMap<>();
        aliases.put("net/minecraft/world/World", "AuditVillageSelectors$World");
        aliases.put("net/minecraft/world/biome/BiomeGenBase", "AuditVillageSelectors$Biome");
        aliases.put("net/minecraft/world/gen/structure/MapGenVillage", "java/lang/Object");
        aliases.put("net/minecraftforge/common/BiomeManager", "AuditVillageSelectors$BiomeManager");
        nativeConfig =
                extract(
                        entry(
                                Path.of(args[1]),
                                "astrotibs/villagenames/config/village/VillageGeneratorConfigHandler"),
                        "astrotibs/villagenames/config/village/VillageGeneratorConfigHandler",
                        Set.of("unpackBiomes"),
                        true,
                        false,
                        aliases);
        syncConfig();
        Class<?> vn =
                extract(
                        entry(Path.of(args[1]), "astrotibs/villagenames/village/MapGenVillageVN"),
                        "astrotibs/villagenames/village/MapGenVillageVN",
                        Set.of("<init>", "func_75047_a"),
                        true,
                        true,
                        aliases);
        vnObject = vn.getConstructor().newInstance();
        vnWorld = new World();
        vn.getField("field_75039_c").set(vnObject, vnWorld);
        vnNative = selector(vn, vnObject);
        for (String field : List.of("field_82665_g", "field_82666_h")) {
            Field f = vn.getDeclaredField(field);
            f.setAccessible(true);
            System.out.println("Native VN " + field + "=" + f.get(vnObject));
        }
        Class<?> nativeMoon =
                extract(
                        entry(
                                Path.of(args[2]),
                                "micdoodle8/mods/galacticraft/core/world/gen/MapGenVillageMoon"),
                        "audit/Moon",
                        Set.of("func_75047_a"),
                        false,
                        true,
                        aliases);
        Object moonObject = nativeMoon.getConstructor().newInstance();
        moonWorld = new World();
        nativeMoon.getField("field_75039_c").set(moonObject, moonWorld);
        moonNative = selector(nativeMoon, moonObject);
        Class<?> workerMoon = Class.forName("amidst.gtnh.worker.MoonStructurePredictor");
        Method workerStart =
                workerMoon.getDeclaredMethod("isVillageStart", long.class, int.class, int.class);
        workerStart.setAccessible(true);
        moonWorker = MethodHandles.lookup().unreflect(workerStart);
        aliases.put("amidst/gtnh/worker/SurfaceBiomeSampler", "AuditVillageSelectors$Sampler");
        aliases.put(
                "amidst/gtnh/worker/RoguelikeDungeonPredictor$StructureDescriptor",
                "AuditVillageSelectors$Descriptor");
        // Keep the real rule adapter and candidate loop in the same isolated package/loader.
        Class<?> rules =
                remapAll(
                        Files.readAllBytes(
                                classes.resolve("amidst/gtnh/worker/VillageGenerationRules.class")),
                        "audit/Rules",
                        aliases);
        aliases.put("amidst/gtnh/worker/VillageGenerationRules", "audit/Rules");
        readRules = rules.getDeclaredMethod("read", Object.class);
        readRules.setAccessible(true);
        Class<?> workerRwg =
                extract(
                        Files.readAllBytes(
                                classes.resolve("amidst/gtnh/worker/RwgStructurePredictor.class")),
                        "audit/Rwg",
                        Set.of("addVillages", "seededRandom", "floorDiv", "inside", "descriptor"),
                        false,
                        false,
                        aliases);
        rwgWorker =
                workerRwg.getDeclaredMethod(
                        "addVillages",
                        long.class,
                        int.class,
                        int.class,
                        long.class,
                        long.class,
                        Sampler.class,
                        rules,
                        boolean.class,
                        List.class);
        rwgWorker.setAccessible(true);
        long checked = 0, accepted = 0;
        long[] seeds = {
            0, 1, -1, 5942749603128060499L, -8138049151491905853L, Long.MIN_VALUE, Long.MAX_VALUE
        };
        for (long seed : seeds) {
            moonWorld.seed = seed;
            int count = 0;
            Map<Long, Integer> regions = new HashMap<>();
            for (int z = -256; z <= 255; z++)
                for (int x = -256; x <= 255; x++) {
                    boolean original = (boolean) moonNative.invokeExact(x, z);
                    boolean prediction = (boolean) moonWorker.invokeExact(seed, x, z);
                    if (original != prediction)
                        throw new AssertionError(
                                "Moon mismatch seed=" + seed + " chunk=" + x + "," + z);
                    if (original) {
                        count++;
                        regions.merge(
                                key(Math.floorDiv(x, 32), Math.floorDiv(z, 32)), 1, Integer::sum);
                    }
                    checked++;
                }
            accepted += count;
            System.out.println(
                    "Moon seed=" + seed + " checked=262144 accepted=" + count + " mismatches=0");
            if (seed == 0) {
                long multiple = regions.values().stream().filter(n -> n > 1).count();
                System.out.println(
                        "Moon seed=0: 32x32 regions=256 empty="
                                + (256 - regions.size())
                                + " multiple-start="
                                + multiple
                                + " max-starts="
                                + Collections.max(regions.values()));
            }
        }
        System.out.println(
                "Moon total checked=" + checked + " accepted=" + accepted + " mismatches=0");
        var moonCtor = workerMoon.getDeclaredConstructor();
        moonCtor.setAccessible(true);
        Object moonPredictor = moonCtor.newInstance();
        Method query =
                workerMoon.getDeclaredMethod(
                        "predict",
                        long.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        int.class,
                        boolean.class);
        query.setAccessible(true);
        long windowChecks = 0;
        for (long seed : new long[] {0, 1, -1}) {
            moonWorld.seed = seed;
            for (int base :
                    new int[] {-513, -512, -17, -16, -9, -8, -1, 0, 1, 7, 8, 15, 16, 511, 512}) {
                for (int width : new int[] {1, 15, 16, 17, 511, 512}) {
                    int x0 = base, z0 = -base - 3, height = 512;
                    Set<Long> expected = new TreeSet<>();
                    for (int z = Math.floorDiv(z0 - 8, 16) - 1;
                            z <= Math.floorDiv(z0 + height - 1 - 8, 16) + 1;
                            z++)
                        for (int x = Math.floorDiv(x0 - 8, 16) - 1;
                                x <= Math.floorDiv(x0 + width - 1 - 8, 16) + 1;
                                x++) {
                            int bx = x * 16 + 8, bz = z * 16 + 8;
                            if (bx >= x0
                                    && bx < x0 + width
                                    && bz >= z0
                                    && bz < z0 + height
                                    && (boolean) moonNative.invokeExact(x, z))
                                expected.add(key(bx, bz));
                        }
                    for (boolean enabled : new boolean[] {true, false}) {
                        Set<Long> actual = new TreeSet<>();
                        List<?> descriptors =
                                (List<?>)
                                        query.invoke(
                                                moonPredictor,
                                                seed,
                                                -28,
                                                x0,
                                                z0,
                                                width,
                                                height,
                                                enabled);
                        for (Object d : descriptors) {
                            Class<?> type = d.getClass();
                            Field kind = type.getDeclaredField("kind"),
                                    x = type.getDeclaredField("x"),
                                    z = type.getDeclaredField("z");
                            kind.setAccessible(true);
                            x.setAccessible(true);
                            z.setAccessible(true);
                            if ("MOON_VILLAGE".equals(kind.get(d)))
                                actual.add(key(x.getInt(d), z.getInt(d)));
                        }
                        if (!actual.equals(enabled ? expected : Collections.emptySet()))
                            throw new AssertionError(
                                    "Marker window seed="
                                            + seed
                                            + " origin="
                                            + x0
                                            + ","
                                            + z0
                                            + " width="
                                            + width
                                            + " enabled="
                                            + enabled);
                        windowChecks++;
                    }
                }
            }
        }
        System.out.println(
                "Moon full-query marker windows and generation gate checked="
                        + windowChecks
                        + " mismatches=0");
        for (long seed : new long[] {0, 1, -1, 5942749603128060499L}) {
            compareVillages("Plains / all RWG terrain accepted", seed, -256, 255, true);
        }
        // Change live generator fields separately from config to exercise shadow-field reads.
        Field maxSpacing = vn.getDeclaredField("field_82665_g");
        maxSpacing.setAccessible(true);
        maxSpacing.setInt(vnObject, 24);
        Field minSpacing = vn.getDeclaredField("field_82666_h");
        minSpacing.setAccessible(true);
        minSpacing.setInt(vnObject, 8);
        Config.spawnBiomesNames = new String[] {"Plains"};
        vnWorld.biome.field_76791_y = "Plains";
        Config.noVillagesRadius = 0;
        compareVillages("24/8 Plains / RWG terrain rejected", 0, -64, 63, false);
        vnWorld.biome.field_76791_y = "DisallowedBiome";
        compareVillages("24/8 disallowed biome / RWG terrain accepted", 0, -64, 63, true);
        vnWorld.biome.field_76791_y = "Plains";
        Config.spawnBiomesNames = new String[] {"plains"};
        compareVillages("case-sensitive biome names", 0, -64, 63, true);
        Config.spawnBiomesNames = new String[] {"  |plains|oak", " Plains |plains|oak|nosub "};
        compareVillages("native parser / empty names / metadata", 0, -64, 63, false);
        Config.spawnBiomesNames = null;
        compareVillages("null biome list", 0, -64, 63, true);
        Config.spawnBiomesNames = new String[] {"Plains"};
        maxSpacing.setInt(vnObject, 73);
        minSpacing.setInt(vnObject, 21);
        for (int radius : new int[] {5, 80, 50000}) {
            Config.noVillagesRadius = radius;
            compareVillages("73/21 radius=" + radius, -1, -256, 255, false);
        }
        System.out.println("No Minecraft world, chunks, saves or full mod initializers were used.");
    }
}
