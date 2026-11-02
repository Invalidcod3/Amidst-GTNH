package amidst.gtnh.cache;

import amidst.logging.AmidstLogger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
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
    private static final Pattern CACHE_FILE = Pattern.compile("[0-9a-f]{64}\\.bin");
    private static final ThreadPoolExecutor WRITER = createWriter();
    private final Path directory;
    private final Executor writer;
    private final AtomicInteger writes = new AtomicInteger();
    private final Map<String, int[]> pendingWrites = new ConcurrentHashMap<>();

    public MapStorage(Path directory) {
        this(directory, WRITER);
    }

    MapStorage(Path directory, Executor writer) {
        this.directory = directory;
        this.writer = writer;
    }

    private static ThreadPoolExecutor createWriter() {
        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        15,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(64),
                        task -> {
                            Thread thread = new Thread(task, "Amidst map cache writer");
                            thread.setDaemon(true);
                            thread.setPriority(Thread.NORM_PRIORITY - 1);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy());
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    /** Cache persistence is optional. Never run disk work on a caller when the queue is full. */
    public synchronized void writeAsync(String key, int[] data) {
        if (!enabled || key == null || data.length < 1 || data.length > MAX_SAMPLES) return;
        int[] snapshot = data.clone();
        pendingWrites.put(key, snapshot);
        try {
            writer.execute(
                    () -> {
                        try {
                            write(key, snapshot);
                        } finally {
                            pendingWrites.remove(key, snapshot);
                        }
                    });
        } catch (RejectedExecutionException full) {
            pendingWrites.remove(key, snapshot);
        }
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

    public int[] read(String key, int size) {
        if (!enabled || key == null || size < 1 || size > MAX_SAMPLES) return null;
        int[] pending = pendingWrites.get(key);
        if (pending != null && pending.length == size) return pending.clone();
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

    public void write(String key, int[] data) {
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
            if (writes.incrementAndGet() % TRIM_INTERVAL == 1) trim(MAX_BYTES);
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

    private record CacheEntry(Path path, long modified, long size) {}

    void trim(long maxBytes) throws IOException {
        List<CacheEntry> entries = new ArrayList<>();
        long bytes = 0;
        try (var paths = Files.list(directory)) {
            for (Iterator<Path> iterator = paths.iterator(); iterator.hasNext(); ) {
                Path path = iterator.next();
                if (!CACHE_FILE.matcher(path.getFileName().toString()).matches()) continue;
                try {
                    // Read once per file. Sorting must never repeatedly ask Windows for metadata.
                    BasicFileAttributes attributes =
                            Files.readAttributes(
                                    path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!attributes.isRegularFile()) continue;
                    entries.add(
                            new CacheEntry(
                                    path,
                                    attributes.lastModifiedTime().toMillis(),
                                    attributes.size()));
                    bytes += attributes.size();
                } catch (IOException ignored) {
                    /* Another instance may have evicted it. */
                }
            }
        }
        if (bytes <= maxBytes) return;
        entries.sort(Comparator.comparingLong(CacheEntry::modified));
        for (CacheEntry entry : entries) {
            if (bytes <= maxBytes) break;
            try {
                Files.deleteIfExists(entry.path());
                bytes -= entry.size();
            } catch (IOException ignored) {
                /* A busy cache entry is disposable later. */
            }
        }
    }
}
