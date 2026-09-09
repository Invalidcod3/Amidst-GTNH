package amidst.gtnh.worker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertArrayEquals;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.jar.JarFile;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.commons.SimpleRemapper;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.block.Block;
import net.minecraft.world.World;

/**
 * Executes methods extracted from the user's ACTUAL RWG JAR. Only referenced
 * input types are adapted to fixtures; the method instructions are not rewritten
 * from our sampler. No game/save generation and no bundled third-party mod JAR.
 */
public class InstalledRwgReferenceTest {
    private String jarPath;

    @Before
    public void requireReferenceJar() throws Exception {
        jarPath = System.getProperty("rwg.referenceJar");
        Assume.assumeNotNull(jarPath);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(jarPath)));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b & 255));
        assertEquals("Update the reference adapter deliberately when RWG changes",
                "9be5c54b56cf2a08d507a5568dec1537ca6f5e958a2ce9d39e32207870a6bd6f", hex.toString());
    }

    @Test
    public void biomeSelectionAndBlendedHeightMatchInstalledGenerator() throws Exception {
        Class<?> reference = extract("rwg/world/ChunkGeneratorRealistic", "getNewNoise", "mix4");
        int points = 0;
        for (long seed : new long[] { 0, 47 }) {
            for (boolean uniform : new boolean[] { false, true }) {
                SurfaceSamplerFixture.Manager manager = new SurfaceSamplerFixture.Manager(seed, uniform);
                SurfaceBiomeSampler sampler = SurfaceSamplerFixture.current(manager);
                Object generator = reference.getConstructor().newInstance();
                initializeGenerator(generator, manager);
                Method generate = reference.getMethod("getNewNoise", SurfaceSamplerFixture.Manager.class,
                        int.class, int.class, SurfaceSamplerFixture.RealisticBiome[].class);
                Method terrain = sampler.getClass().getDeclaredMethod("getBlendedTerrainHeight", int.class, int.class, float.class);
                terrain.setAccessible(true);
                Method getChunk = sampler.getClass().getDeclaredMethod("getBlendChunk", int.class, int.class);
                getChunk.setAccessible(true);
                for (int chunk : new int[] { -13, -1, 0, 2, 19 }) {
                    int cx = chunk, cz = 3 - chunk;
                    SurfaceSamplerFixture.RealisticBiome[] expected = new SurfaceSamplerFixture.RealisticBiome[256];
                    float[] heights = (float[]) generate.invoke(generator, manager, cx * 16, cz * 16, expected);
                    Object blend = getChunk.invoke(sampler, cx, cz);
                    Method select = blend.getClass().getDeclaredMethod("getRealisticBiome", int.class, int.class);
                    select.setAccessible(true);
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            SurfaceSamplerFixture.RealisticBiome actual =
                                    (SurfaceSamplerFixture.RealisticBiome) select.invoke(blend, x, z);
                            assertSame(expected[z * 16 + x], actual);
                            int bx = cx * 16 + x, bz = cz * 16 + z;
                            assertEquals(heights[x * 16 + z], (Float) terrain.invoke(sampler, bx, bz,
                                    manager.getRiverStrength(bx, bz)), 0.0F);
                            points++;
                        }
                    }
                }
            }
        }
        System.out.println("Installed RWG getNewNoise: " + points + " biome/height points matched exactly");
    }

    @Test
    public void nativeReplayMatchesInstalledRiverAndSurfaceDispatchForArbitraryBiomes() throws Exception {
        Class<?> reference = extract("rwg/world/ChunkGeneratorRealistic", "replaceBlocksForBiome");
        SurfaceSamplerFixture.Manager manager = new SurfaceSamplerFixture.Manager(47, false);
        RwgChunkBiomeReplay replay = new RwgChunkBiomeReplay(null, 47, manager, manager.perlin, manager.cell,
                SurfaceSamplerFixture.RealisticBiome.class, SurfaceSamplerFixture.Manager.class,
                SurfaceSamplerFixture.Noise.class, Object.class);
        Method replace = Arrays.stream(reference.getMethods())
                .filter(m -> m.getName().equals("replaceBlocksForBiome")).findFirst().get();
        int points = 0;
        for (int cx : new int[] { -7, -1, 0, 11 }) {
            for (int cz : new int[] { -3, 5 }) {
                Object original = reference.getConstructor().newInstance();
                set(original, "rand", new Random((long) cx * 341873128712L + (long) cz * 132897987541L));
                set(original, "perlin", manager.perlin);
                set(original, "cell", manager.cell);
                float[] heights = new float[256], rivers = new float[256];
                for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                    heights[x * 16 + z] = 50 + x * 3.5F + z;
                    rivers[x * 16 + z] = manager.getRiverStrength(cx * 16 + x, cz * 16 + z);
                }
                set(original, "riverStrength", rivers);
                SurfaceSamplerFixture.RealisticBiome[] selected = new SurfaceSamplerFixture.RealisticBiome[256];
                Arrays.fill(selected, new ArbitraryPainterBiome());
                BiomeGenBase[] expected = new BiomeGenBase[256], actual = new BiomeGenBase[256], riverBiomes = new BiomeGenBase[256];
                Arrays.fill(expected, BiomeGenBase.forest);
                Arrays.fill(actual, BiomeGenBase.forest);
                Arrays.fill(riverBiomes, BiomeGenBase.river);
                replace.invoke(original, cx, cz, new ReferenceBlock[65536], new byte[65536], selected, expected, heights);
                replay.replay(cx, cz, selected, actual, riverBiomes, heights, rivers, new Object[0]);
                assertArrayEquals(expected, actual);
                points += 256;
            }
        }
        System.out.println("Installed RWG replaceBlocksForBiome: " + points + " arbitrary callback results matched exactly");
    }

    @Test
    public void biomeOverrideMatchesInstalledSurfacePainterIncludingBopParameters() throws Exception {
        Class<?> reference = extract("rwg/surface/SurfaceDuneValley", "paintTerrain");
        Method paint = Arrays.stream(reference.getMethods()).filter(m -> m.getName().equals("paintTerrain")).findFirst().get();
        SurfaceSamplerFixture.Noise noise = new SurfaceSamplerFixture.Noise(47);
        RwgSurfaceBiomeOverrides rules = new RwgSurfaceBiomeOverrides(noise,
                noise.getClass().getMethod("noise2", float.class, float.class));
        ReferenceBlock[] blocks = new ReferenceBlock[65536];
        byte[] metadata = new byte[65536];
        int cases = 0, desertCases = 0;
        for (float valley : new float[] { 220, 300 }) {
            for (boolean mix : new boolean[] { false, true }) {
                Object painter = reference.getConstructor().newInstance();
                set(painter, "valley", valley);
                set(painter, "mix", mix);
                set(painter, "topBlock", ReferenceBlocks.grass);
                set(painter, "fillerBlock", ReferenceBlocks.field_150346_d);
                Object bop = new RwgSurfaceBiomeOverridesTest.SupportBiome(valley, mix);
                for (int coordinate = -8; coordinate <= 8; coordinate++) {
                    int bx = coordinate * 127 + 3, bz = 19 - coordinate * 73;
                    for (float height : new float[] { -2, 0, 59, 62, 73.99F, 90, 140, 255, 280 }) {
                        for (BiomeGenBase initial : new BiomeGenBase[] { BiomeGenBase.forest, BiomeGenBase.river }) {
                            int localZ = 5, localX = 9, column = (localX * 16 + localZ) * 256;
                            for (int y = 0; y < 256; y++) {
                                blocks[column + y] = y <= (int) height ? ReferenceBlocks.field_150348_b
                                        : y < 63 ? ReferenceBlocks.water : ReferenceBlocks.field_150350_a;
                            }
                            BiomeGenBase[] biomes = new BiomeGenBase[256];
                            biomes[localZ * 16 + localX] = initial;
                            paint.invoke(painter, blocks, metadata, bx, bz, localZ, localX, -1, null,
                                    new Random(0), noise, null, new float[256], 0.0F, biomes);
                            BiomeGenBase actual = rules.apply(bop, initial, bx, bz, () -> height);
                            assertSame(biomes[localZ * 16 + localX], actual);
                            if (actual == BiomeGenBase.desert) desertCases++;
                            cases++;
                        }
                    }
                }
            }
        }
        System.out.println("Installed RWG SurfaceDuneValley: " + cases + " cases matched; "
                + desertCases + " desert replacements missed by v32");
    }

    private Class<?> extract(String owner, String... methodNames) throws Exception {
        ClassNode node = new ClassNode(Opcodes.ASM5);
        try (JarFile jar = new JarFile(jarPath); InputStream in = jar.getInputStream(jar.getJarEntry(owner + ".class"))) {
            new ClassReader(in).accept(node, ClassReader.EXPAND_FRAMES);
        }
        Set<String> keepMethods = new HashSet<>(Arrays.asList(methodNames));
        node.methods.removeIf(method -> !keepMethods.contains(method.name));
        Set<String> keepFields = new HashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions.toArray()) {
                // The installed NoiseGenerator is an interface; the controlled
                // test Noise is a concrete class. Adapt only this input call.
                if (instruction instanceof MethodInsnNode
                        && "rwg/util/NoiseGenerator".equals(((MethodInsnNode) instruction).owner)) {
                    ((MethodInsnNode) instruction).setOpcode(Opcodes.INVOKEVIRTUAL);
                    ((MethodInsnNode) instruction).itf = false;
                }
                if (instruction instanceof FieldInsnNode && owner.equals(((FieldInsnNode) instruction).owner)) {
                    keepFields.add(((FieldInsnNode) instruction).name);
                }
            }
        }
        node.fields.removeIf(field -> !keepFields.contains(field.name));
        for (FieldNode field : node.fields) field.access &= ~Opcodes.ACC_FINAL;
        if (owner.endsWith("SurfaceDuneValley")) {
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "topBlock", "Lnet/minecraft/block/Block;", null, null));
            node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC, "fillerBlock", "Lnet/minecraft/block/Block;", null, null));
        }
        node.superName = "java/lang/Object";
        node.interfaces.clear();
        MethodNode constructor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(Opcodes.RETURN));
        constructor.maxLocals = 1;
        constructor.maxStack = 1;
        node.methods.add(constructor);
        String fixture = "amidst/gtnh/worker/Installed" + owner.substring(owner.lastIndexOf('/') + 1);
        Map<String, String> names = new HashMap<>();
        names.put(owner, fixture);
        names.put("rwg/world/ChunkManagerRealistic", "amidst/gtnh/worker/SurfaceSamplerFixture$Manager");
        names.put("rwg/biomes/realistic/RealisticBiomeBase", "amidst/gtnh/worker/SurfaceSamplerFixture$RealisticBiome");
        names.put("rwg/util/NoiseGenerator", "amidst/gtnh/worker/SurfaceSamplerFixture$Noise");
        names.put("rwg/util/CellNoise", "java/lang/Object");
        names.put("net/minecraft/block/Block", "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceBlock");
        names.put("net/minecraft/block/BlockSand", "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceBlock");
        names.put("net/minecraft/init/Blocks", "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceBlocks");
        names.put("net/minecraft/world/chunk/IChunkProvider", "java/lang/Object");
        names.put("net/minecraftforge/common/MinecraftForge", "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceForge");
        names.put("cpw/mods/fml/common/eventhandler/EventBus", "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceEventBus");
        names.put("cpw/mods/fml/common/eventhandler/Event", "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceEvent");
        names.put("net/minecraftforge/event/terraingen/ChunkProviderEvent$ReplaceBiomeBlocks",
                "amidst/gtnh/worker/InstalledRwgReferenceTest$ReferenceEvent");
        ClassWriter writer = new ClassWriter(0);
        node.accept(new RemappingClassAdapter(writer, new SimpleRemapper(names)));
        byte[] bytes = writer.toByteArray();
        return new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(fixture.replace('/', '.'), bytes, 0, bytes.length); }
        }.define();
    }

    private static void initializeGenerator(Object instance, SurfaceSamplerFixture.Manager manager) throws Exception {
        set(instance, "perlin", manager.perlin);
        set(instance, "cell", manager.cell);
        set(instance, "sampleArraySize", 21);
        set(instance, "parabolicSize", 8);
        set(instance, "parabolicArraySize", 17);
        float[] kernel = new float[289];
        float total = 0;
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                float weight = 0.445F / (float) Math.sqrt((float) (x * x + z * z) + 0.3F);
                kernel[x + 8 + (z + 8) * 17] = weight;
                total += weight;
            }
        }
        for (int i = 0; i < kernel.length; i++) kernel[i] /= total;
        set(instance, "parabolicField", kernel);
        set(instance, "biomeData", new int[441]);
        set(instance, "hugeRender", new float[81][256]);
        set(instance, "smallRender", new float[625][256]);
        set(instance, "testHeight", new float[256]);
        set(instance, "riverStrength", new float[256]);
        set(instance, "mapGenBiomes", new float[258]);
        set(instance, "mix4Src", new float[4][]);
    }

    private static void set(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(instance, value);
    }

    public static final class ReferenceBlock {}
    public static final class ReferenceForge {
        public static final ReferenceEventBus EVENT_BUS = new ReferenceEventBus();
    }
    public static final class ReferenceEventBus {
        public boolean post(ReferenceEvent event) { return false; }
    }
    public static final class ReferenceEvent {
        public ReferenceEvent(Object generator, int cx, int cz, ReferenceBlock[] blocks,
                byte[] metadata, BiomeGenBase[] biomes, World world) {}
        public cpw.mods.fml.common.eventhandler.Event.Result getResult() {
            return cpw.mods.fml.common.eventhandler.Event.Result.DEFAULT;
        }
    }
    public static final class ArbitraryPainterBiome extends SurfaceSamplerFixture.RealisticBiome {
        ArbitraryPainterBiome() { super(204, BiomeGenBase.forest); }
        @Override public void rReplace(Block[] blocks, byte[] metadata, int bx, int bz, int z, int x,
                int depth, World world, Random random, SurfaceSamplerFixture.Noise noise, Object cell,
                float[] heights, float river, BiomeGenBase[] biomes) {
            int choice = random.nextInt(7);
            if (choice < 2) biomes[z * 16 + x] = BiomeGenBase.icePlains;
            if (choice == 2 && heights[x * 16 + z] > 70) biomes[z * 16 + x] = BiomeGenBase.mushroomIsland;
            if (choice == 3 && biomes[z * 16 + x] == BiomeGenBase.river) biomes[z * 16 + x] = BiomeGenBase.swampland;
            if (choice == 4) biomes[(z * 16 + x + 1) % 256] = BiomeGenBase.extremeHills;
        }
    }
    public static final class ReferenceBlocks {
        public static final ReferenceBlock field_150350_a = new ReferenceBlock(); // air
        public static final ReferenceBlock field_150348_b = new ReferenceBlock(); // stone
        public static final ReferenceBlock field_150346_d = new ReferenceBlock(); // dirt
        public static final ReferenceBlock field_150354_m = new ReferenceBlock(); // sand
        public static final ReferenceBlock field_150322_A = new ReferenceBlock(); // sandstone
        public static final ReferenceBlock field_150357_h = new ReferenceBlock(); // bedrock
        public static final ReferenceBlock grass = new ReferenceBlock();
        public static final ReferenceBlock water = new ReferenceBlock();
    }
}
