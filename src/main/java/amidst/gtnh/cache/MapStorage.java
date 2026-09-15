package amidst.gtnh.cache;

import amidst.logging.AmidstLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/** Disposable map data only. Corrupt or unwritable caches always fall back to the worker. */
public final class MapStorage {
    public static volatile boolean enabled = true;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAGIC =
            0x414d4331; // AMC1: big-endian header, count, CRC32, biome IDs.
    private static final int HEADER_BYTES = 16;
    private static final int MAX_SAMPLES = 65_536;
    private static final int TRIM_INTERVAL = 32;
    private final Path directory;
    private int writes;

    public MapStorage(Path directory) {
        this.directory = directory;
    }

    public static Path root() {
        return Path.of(
                System.getProperty(
                        "amidst.cacheDir",
                        Path.of(System.getProperty("user.home"), ".amidst-gtnh").toString()));
    }

    public static String hash(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public synchronized int[] read(String key, int size) {
        if (!enabled || key == null || size < 1 || size > MAX_SAMPLES) return null;
        Path path = directory.resolve(hash(key) + ".bin");
        try {
            if (Files.size(path) != HEADER_BYTES + size * 4L) return null;
            try (DataInputStream in =
                    new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
                if (in.readInt() != MAGIC || in.readInt() != size) return null;
                long checksum = in.readLong();
                int[] data = new int[size];
                CRC32 crc = new CRC32();
                for (int i = 0; i < size; i++) {
                    data[i] = in.readInt();
                    update(crc, data[i]);
                }
                return checksum == crc.getValue() ? data : null;
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public synchronized void write(String key, int[] data) {
        if (!enabled || key == null || data.length < 1 || data.length > MAX_SAMPLES) return;
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, "tile-", ".tmp");
            CRC32 crc = new CRC32();
            for (int v : data) update(crc, v);
            try (DataOutputStream out =
                    new DataOutputStream(
                            new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                out.writeInt(MAGIC);
                out.writeInt(data.length);
                out.writeLong(crc.getValue());
                for (int v : data) out.writeInt(v);
            }
            move(temporary, directory.resolve(hash(key) + ".bin"));
            if (++writes % TRIM_INTERVAL == 1) trim();
        } catch (IOException | RuntimeException e) {
            AmidstLogger.warn("Map cache write failed: " + e.getMessage());
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
        }
    }

    public static void move(Path from, Path to) throws IOException {
        try {
            Files.move(
                    from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void update(CRC32 crc, int v) {
        crc.update(v >>> 24);
        crc.update(v >>> 16);
        crc.update(v >>> 8);
        crc.update(v);
    }

    private void trim() throws IOException {
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files =
                    paths.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.bin"))
                            .toList();
        }
        var sorted = new ArrayList<>(files);
        sorted.sort(
                Comparator.comparingLong(
                        p -> {
                            try {
                                return Files.getLastModifiedTime(p).toMillis();
                            } catch (IOException e) {
                                return 0L;
                            }
                        }));
        long bytes = 0;
        for (Path p : sorted) bytes += Files.size(p);
        for (Path p : sorted) {
            if (bytes <= MAX_BYTES) break;
            long size = Files.size(p);
            Files.deleteIfExists(p);
            bytes -= size;
        }
    }
}
