import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Release check using only JDK APIs; never loads Minecraft classes.
 * Reads actual field/method references, not source strings or reflection names.
 * Usage: java VerifyWorkerJar.java release.jar [reobf.jar developer.jar]
 */
public final class VerifyWorkerJar {
    private static final String MOD_CLASS = "amidst/gtnh/worker/AmidstGtnhBiomeWorkerMod.class";
    private static final Set<String> DEVELOPMENT_NAMES = Set.of(
            "isRemote", "provider", "dimensionId", "xPosition", "zPosition",
            "getSeed", "getWorldChunkManager", "getBiomeGenForCoords");

    public static void main(String[] args) throws IOException {
        if (args.length != 1 && args.length != 3) {
            throw new IllegalArgumentException(
                    "Usage: java VerifyWorkerJar.java release.jar [reobf.jar developer.jar]");
        }
        Path release = Path.of(args[0]);
        verify(release);
        if (args.length == 3) {
            if (Files.mismatch(release, Path.of(args[1])) != -1L) {
                throw new IllegalStateException("Staged Worker differs from reobfJar: " + release);
            }
            // Real-artifact regression: this must reject the exact input that
            // the old staging task accidentally shipped, even after renaming.
            boolean rejected = false;
            try {
                verify(Path.of(args[2]));
            } catch (IllegalStateException expected) {
                rejected = true;
                System.out.println("Developer JAR correctly rejected: " + args[2]);
            }
            if (!rejected) {
                throw new IllegalStateException("Mapping check unexpectedly accepted the developer JAR");
            }
        }
        System.out.println("Verified installable Worker (SRG mappings): " + release.toAbsolutePath());
    }

    private static void verify(Path path) throws IOException {
        List<String> invalid = new ArrayList<>();
        boolean foundRuntimeWorldFlag = false;
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.getJarEntry(MOD_CLASS) == null) {
                throw new IllegalStateException("Worker entry point missing: " + path);
            }
            verifyPausedWorldHook(jar);
            for (var entries = jar.entries(); entries.hasMoreElements();) {
                var entry = entries.nextElement();
                if (!entry.getName().startsWith("amidst/gtnh/worker/")
                        || !entry.getName().endsWith(".class")) {
                    continue;
                }
                List<MemberReference> references;
                try (DataInputStream in = new DataInputStream(jar.getInputStream(entry))) {
                    references = readReferences(in);
                }
                for (MemberReference ref : references) {
                    if (!ref.owner.startsWith("net/minecraft/")) {
                        continue;
                    }
                    if (DEVELOPMENT_NAMES.contains(ref.name)) {
                        invalid.add(entry.getName() + " -> " + ref.owner + "." + ref.name);
                    }
                    if (entry.getName().equals(MOD_CLASS)
                            && ref.owner.equals("net/minecraft/world/World")
                            && ref.name.equals("field_72995_K")) {
                        foundRuntimeWorldFlag = true;
                    }
                }
            }
        }
        if (!invalid.isEmpty()) {
            throw new IllegalStateException("Unmapped Minecraft references in " + path
                    + "; do not install this developer JAR. Stage reobfJar instead:\n"
                    + String.join("\n", invalid));
        }
        if (!foundRuntimeWorldFlag) {
            throw new IllegalStateException("Missing World.field_72995_K runtime reference in " + MOD_CLASS);
        }
    }

    private static void verifyPausedWorldHook(JarFile jar) throws IOException {
        var manifest = jar.getManifest();
        if (manifest == null
                || !"amidst.gtnh.worker.core.WorkerLoadingPlugin".equals(
                        manifest.getMainAttributes().getValue("FMLCorePlugin"))
                || !"true".equalsIgnoreCase(
                        manifest.getMainAttributes().getValue("FMLCorePluginContainsFMLMod"))) {
            throw new IllegalStateException("Missing paused-world core plugin / ordinary mod manifest entries");
        }
        for (String entry : List.of("core/WorkerLoadingPlugin", "core/IntegratedServerTickTransformer",
                "WorkerTickHooks", "RwgRuntimeClasses", "RwgSurfaceEffects")) {
            if (jar.getJarEntry("amidst/gtnh/worker/" + entry + ".class") == null) {
                throw new IllegalStateException("Paused-world Worker class missing: " + entry);
            }
        }
    }

    /** Constant-pool indexes are one-based; long/double values occupy two slots. */
    private static List<MemberReference> readReferences(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("Invalid class file");
        }
        in.readUnsignedShort(); // minor version
        in.readUnsignedShort(); // major version
        int size = in.readUnsignedShort();
        int[] tags = new int[size];
        int[] first = new int[size];
        int[] second = new int[size];
        String[] text = new String[size];
        for (int i = 1; i < size; i++) {
            tags[i] = in.readUnsignedByte();
            switch (tags[i]) {
                case 1 -> text[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> { in.readLong(); i++; }
                case 7, 8, 16, 19, 20 -> first[i] = in.readUnsignedShort();
                case 9, 10, 11, 12, 17, 18 -> {
                    first[i] = in.readUnsignedShort();
                    second[i] = in.readUnsignedShort();
                }
                case 15 -> { in.readUnsignedByte(); in.readUnsignedShort(); }
                default -> throw new IOException("Unsupported constant-pool tag: " + tags[i]);
            }
        }
        List<MemberReference> result = new ArrayList<>();
        for (int i = 1; i < size; i++) {
            if (tags[i] == 9 || tags[i] == 10 || tags[i] == 11) {
                result.add(new MemberReference(text[first[first[i]]], text[first[second[i]]]));
            }
        }
        return result;
    }

    private record MemberReference(String owner, String name) {}
}
