package amidst.gtnh.worker;

import net.minecraft.world.biome.WorldChunkManager;

import org.objectweb.asm.*;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.*;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;

/** Version-pinned, detached RWG2 calculations. Never defines or transforms a game class. */
public final class Rwg2Runtime {
    static final String MANAGER = "rwg2.world.ChunkManagerRealistic";
    private static final String GENERATOR = "rwg2/world/ChunkGeneratorRealistic";
    private static final String COPY = "amidst/gtnh/worker/isolated/Rwg2Generator";
    private static final ThreadLocal<Boolean> CONSTRUCTING = new ThreadLocal<>();
    private static final ThreadLocal<Rwg2BiomeCache> SAMPLING = new ThreadLocal<>();
    private static Class<?> generatorCopy;

    private Rwg2Runtime() {}

    static Rwg2BiomeCache sampling(Rwg2BiomeCache next) {
        Rwg2BiomeCache previous = SAMPLING.get();
        if (next == null) SAMPLING.remove();
        else SAMPLING.set(next);
        return previous;
    }

    public static Object cachedBiome(Object manager, int x, int z) {
        Rwg2BiomeCache cache = SAMPLING.get();
        if (cache == null) throw new IllegalStateException("RWG2 sample outside detached replay");
        try {
            return cache.sample(manager, x, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot sample RWG2 biome", e);
        }
    }

    static boolean openSimplex() throws ReflectiveOperationException {
        // Reading fields does not run RWG2's getter, which marks saved data dirty.
        Class<?> data = Class.forName("rwg2.world.RwgWorldSavedData");
        Object instance = field(data, "INSTANCE").get(null);
        String mode =
                instance == null
                        ? "UNKNOWN"
                        : String.valueOf(field(data, "noiseImplementation").get(instance));
        if (mode.equals("PERLIN")) return false;
        if (mode.equals("OPENSIMPLEX")) return true;
        if (mode.equals("UNKNOWN") || mode.startsWith("DYNAMIC"))
            return Class.forName("rwg2.util.NoiseGeneratorWrapper")
                    .getField("useOpenSimplex")
                    .getBoolean(null);
        throw new IllegalStateException("Unsupported RWG2 noise mode: " + mode);
    }

    public static boolean optimizeMountainInfluence() {
        Rwg2BiomeCache cache = SAMPLING.get();
        return cache != null && cache.optimizeMountainInfluence;
    }

    public static float mountainInfluence(int[] biomes, int x, int z) {
        try {
            return SAMPLING.get().mountainInfluence(biomes, x, z);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot sample RWG2 mountain influence", e);
        }
    }

    public static Object newNoise(long seed) {
        Boolean simplex = CONSTRUCTING.get();
        if (simplex == null)
            throw new IllegalStateException("RWG2 noise requested outside detached construction");
        try {
            return noise(seed, simplex);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Object noise(long seed, boolean simplex) throws ReflectiveOperationException {
        return Class.forName("rwg2.util." + (simplex ? "OpenSimplexNoise" : "PerlinNoise"))
                .getConstructor(long.class)
                .newInstance(seed);
    }

    public static boolean skipSurfaceEvent(Object bus, Object event) {
        return false;
    }

    static WorldChunkManager manager(long seed, boolean simplex) throws Exception {
        Class<?> type = Class.forName(MANAGER);
        verify(bytes(type), "21bc1a0084737671659624dd15044ee1032ec5ca26157bf688a115ac14b272db");
        Constructor<?> empty = type.getDeclaredConstructor();
        empty.setAccessible(true);
        Object manager = empty.newInstance();
        // Reproduce 2.0.6's public constructor, using explicit immutable noise instances.
        set(manager, "continental", true);
        set(manager, "perlin", noise(seed, simplex));
        Class<?> cell = Class.forName("rwg2.util.CellNoise");
        Object distance = cell.getConstructor(long.class, short.class).newInstance(seed, (short) 0);
        cell.getMethod("setUseDistance", boolean.class).invoke(distance, true);
        set(manager, "cell", distance);
        set(
                manager,
                "biomecell",
                cell.getConstructor(long.class, short.class).newInstance(seed, (short) 0));
        set(manager, "climateWarp", noise(seed ^ 0xBB67AE8584CAA73BL, simplex));
        set(
                manager,
                "smallBiomePoints",
                Class.forName("rwg2.util.PoissonPointNoise")
                        .getConstructor(long.class, double.class, int.class)
                        .newInstance(seed ^ 0x510E527FADE682D1L, 1020D, 4));
        set(
                manager,
                "continents",
                Class.forName("rwg2.util.ContinentalNoise")
                        .getConstructor(long.class)
                        .newInstance(seed ^ 0x6A09E667F3BCC909L));
        Class<?> support = Class.forName("rwg2.support.Support");
        String[] climates = {"snow", "cold", "hot", "wet"};
        for (String placement :
                new String[] {
                    "border",
                    "coldBorder",
                    "hotBorder",
                    "veryColdBorder",
                    "veryHotBorder",
                    "littoral",
                    "small",
                    "island",
                    "smallIsland",
                    "largeIsland"
                }) {
            ArrayList<?>[] lists = new ArrayList<?>[4];
            for (int i = 0; i < 4; i++) {
                Object climate = support.getField(climates[i]).get(null);
                lists[i] =
                        new ArrayList<>(
                                (Collection<?>)
                                        climate.getClass().getField(placement).get(climate));
            }
            set(manager, placement + "Biomes", lists);
        }
        Class<?> biome = Class.forName("rwg2.biomes.realistic.RealisticBiomeBase");
        String[][] names = {
            {"polar", "snowHills", "snowRivers", "snowLakes", "redwoodSnow"},
            {
                "tundraHills",
                "tundraPlains",
                "taigaHills",
                "taigaPlains",
                "redwood",
                "darkRedwood",
                "darkRedwoodPlains",
                "woodhills",
                "woodmountains",
                "woodhills",
                "woodmountains"
            },
            {
                "duneValleyForest",
                "savanna",
                "savannaForest",
                "savannaDunes",
                "stoneMountains",
                "stoneMountainsCactus",
                "hotForest",
                "hotRedwood",
                "canyonForest",
                "mesaPlains",
                "desert",
                "desertMountains",
                "duneValley",
                "oasis",
                "redDesertMountains",
                "redDesertOasis",
                "canyon",
                "mesa"
            },
            {"rainForestHigh", "jungleHills", "jungleCanyon", "redwoodJungle"},
            {},
            {}
        };
        String[] categories = {"snow", "cold", "hot", "wet", "small", "test"};
        for (int i = 0; i < categories.length; i++) {
            List<Object> list = new ArrayList<>();
            for (String name : names[i]) list.add(biome.getField(name).get(null));
            list.addAll((Collection<?>) support.getField("biomes_" + categories[i]).get(null));
            set(manager, "biomes_" + categories[i], list);
            set(manager, "biomes_" + categories[i] + "Length", list.size());
            if (i == 3) set(manager, "wetEnabled", !list.isEmpty());
            if (i == 4) set(manager, "smallEnabled", list.size() > 1);
        }
        return (WorldChunkManager) manager;
    }

    static synchronized Object generator(WorldChunkManager manager, long seed, boolean simplex)
            throws Exception {
        if (generatorCopy == null) {
            Class<?> original = Class.forName(GENERATOR.replace('/', '.'));
            byte[] code = bytes(original);
            verify(code, "1517b2306a0db3c220a0afb63569c4b5e95658ba5c9d593c950ae273ca675d55");
            byte[] detached = detach(code);
            generatorCopy =
                    new ClassLoader(original.getClassLoader()) {
                        Class<?> define() {
                            return defineClass(
                                    COPY.replace('/', '.'), detached, 0, detached.length);
                        }
                    }.define();
        }
        CONSTRUCTING.set(simplex);
        try {
            return generatorCopy
                    .getConstructor(Class.forName(MANAGER), long.class, boolean.class)
                    .newInstance(manager, seed, true);
        } finally {
            CONSTRUCTING.remove();
        }
    }

    static byte[] detach(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
        int noiseCalls = 0, colorCalls = 0, eventCalls = 0, biomeCalls = 0;
        Set<String> allowed =
                new HashSet<>(
                        Arrays.asList(
                                "<init>",
                                "getNewNoise",
                                "mix4",
                                "generateTerrain",
                                "carveMountainChainRivers",
                                "carveRiverColumn",
                                "setRiverCaveBlock",
                                "replaceBlocksForBiome",
                                "markLavaCaveOpeningBiome",
                                "nearbyMountainChainInfluence",
                                "smoothstep",
                                "clearActiveBiomes",
                                "copyActiveBiomes"));
        for (MethodNode method : node.methods) {
            if (!allowed.contains(method.name)) {
                method.instructions.clear();
                method.tryCatchBlocks.clear();
                if (method.localVariables != null) method.localVariables.clear();
                method.instructions.add(
                        new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException"));
                method.instructions.add(new InsnNode(Opcodes.DUP));
                method.instructions.add(
                        new MethodInsnNode(
                                Opcodes.INVOKESPECIAL,
                                "java/lang/UnsupportedOperationException",
                                "<init>",
                                "()V",
                                false));
                method.instructions.add(new InsnNode(Opcodes.ATHROW));
                continue;
            }
            if (method.name.equals("nearbyMountainChainInfluence")) {
                // Only the detached, fingerprint-verified copy changes. Retain the original
                // body for independent parity/performance comparisons and non-sampling calls.
                InsnList fast = new InsnList();
                LabelNode original = new LabelNode();
                fast.add(
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "amidst/gtnh/worker/Rwg2Runtime",
                                "optimizeMountainInfluence",
                                "()Z",
                                false));
                fast.add(new JumpInsnNode(Opcodes.IFEQ, original));
                fast.add(new VarInsnNode(Opcodes.ALOAD, 0));
                fast.add(new FieldInsnNode(Opcodes.GETFIELD, GENERATOR, "biomeData", "[I"));
                fast.add(new VarInsnNode(Opcodes.ILOAD, 1));
                fast.add(new VarInsnNode(Opcodes.ILOAD, 2));
                fast.add(
                        new MethodInsnNode(
                                Opcodes.INVOKESTATIC,
                                "amidst/gtnh/worker/Rwg2Runtime",
                                "mountainInfluence",
                                "([III)F",
                                false));
                fast.add(new InsnNode(Opcodes.FRETURN));
                fast.add(original);
                fast.add(
                        new FrameNode(
                                Opcodes.F_NEW,
                                3,
                                new Object[] {GENERATOR, Opcodes.INTEGER, Opcodes.INTEGER},
                                0,
                                new Object[0]));
                method.instructions.insert(fast);
            }
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode)) continue;
                MethodInsnNode call = (MethodInsnNode) instruction;
                if (call.owner.equals("rwg2/world/ChunkManagerRealistic")
                        && call.name.equals("getBiomeDataAt")
                        && call.desc.equals("(II)Lrwg2/biomes/realistic/RealisticBiomeBase;")) {
                    biomeCalls++;
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = "amidst/gtnh/worker/Rwg2Runtime";
                    call.name = "cachedBiome";
                    call.desc = "(Ljava/lang/Object;II)Ljava/lang/Object;";
                    method.instructions.insert(
                            call,
                            new TypeInsnNode(
                                    Opcodes.CHECKCAST, "rwg2/biomes/realistic/RealisticBiomeBase"));
                } else if (call.owner.equals("rwg2/util/NoiseSelector")) {
                    noiseCalls++;
                    call.owner = "amidst/gtnh/worker/Rwg2Runtime";
                    call.name = "newNoise";
                    call.desc = "(J)Ljava/lang/Object;";
                    method.instructions.insert(
                            call, new TypeInsnNode(Opcodes.CHECKCAST, "rwg2/util/NoiseGenerator"));
                } else if (call.owner.equals("rwg2/util/CanyonColor") && call.name.equals("init")) {
                    // Clay colours only affect metadata, never the requested biome IDs/heights.
                    // Leave the game's colour table unchanged when previewing a different seed.
                    colorCalls++;
                    method.instructions.set(call, new InsnNode(Opcodes.POP2));
                } else if (call.owner.equals("cpw/mods/fml/common/eventhandler/EventBus")
                        && call.name.equals("post")) {
                    eventCalls++;
                    call.setOpcode(Opcodes.INVOKESTATIC);
                    call.owner = "amidst/gtnh/worker/Rwg2Runtime";
                    call.name = "skipSurfaceEvent";
                    call.desc = "(Ljava/lang/Object;Ljava/lang/Object;)Z";
                }
            }
        }
        if (noiseCalls != 1 || colorCalls != 1 || eventCalls != 1 || biomeCalls != 2)
            throw new IllegalStateException(
                    "Unsupported RWG2 generator calls: noise="
                            + noiseCalls
                            + ", color="
                            + colorCalls
                            + ", event="
                            + eventCalls
                            + ", biome="
                            + biomeCalls);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(new RemappingClassAdapter(writer, new SimpleRemapper(GENERATOR, COPY)));
        return writer.toByteArray();
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Object get(Object object, String name) throws ReflectiveOperationException {
        return field(object.getClass(), name).get(object);
    }

    static void set(Object object, String name, Object value) throws ReflectiveOperationException {
        field(object.getClass(), name).set(object, value);
    }

    static byte[] bytes(Class<?> type) throws Exception {
        try (InputStream input =
                type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            if (input == null) throw new IllegalStateException("Missing RWG2 bytecode");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) >= 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    static void verify(byte[] bytes, String expected) throws Exception {
        StringBuilder actual = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
            actual.append(String.format("%02x", b & 255));
        if (!expected.equals(actual.toString()))
            throw new IllegalStateException("RWG2 bytecode is not the supported 2.0.6 build");
    }
}
