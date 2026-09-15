package amidst.gtnh.worker;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;

import net.minecraft.world.WorldServer;
import net.minecraftforge.common.DimensionManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Disk entries are identified by runtime inputs AND the currently loaded biome evidence. */
final class MapCacheIdentity {
    private final MapCachePreparation preparation;

    MapCacheIdentity() {
        this(new MapCachePreparation(MapCacheIdentity::computeEnvironment));
    }

    MapCacheIdentity(MapCachePreparation preparation) {
        this.preparation = preparation;
    }

    private static String computeEnvironment() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (ModContainer mod : Loader.instance().getModList()) {
                add(digest, mod.getModId() + ":" + mod.getVersion());
                java.io.File file = mod.getSource();
                if (file != null)
                    add(
                            digest,
                            file.getCanonicalPath()
                                    + ":"
                                    + file.length()
                                    + ":"
                                    + file.lastModified());
            }
            Path config = Loader.instance().getConfigDir().toPath();
            if (Files.isDirectory(config))
                try (java.util.stream.Stream<Path> paths = Files.walk(config)) {
                    for (Path path :
                            (Iterable<Path>)
                                    paths.filter(Files::isRegularFile).sorted()::iterator) {
                        add(digest, config.relativize(path).toString());
                        try (java.io.InputStream in = Files.newInputStream(path)) {
                            byte[] buffer = new byte[8192];
                            int n;
                            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
                        }
                    }
                }
            return hex(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Cannot identify map cache inputs", e);
        }
    }

    String viewIdentity(long seed) {
        String environment = preparation.getIfReady();
        if (environment == null) return null;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            add(d, environment);
            add(d, "map-v23:" + seed);
            WorldServer world = DimensionManager.getWorld(0);
            if (world != null && world.getSeed() == seed) {
                add(d, world.getSaveHandler().getWorldDirectory().getCanonicalPath());
                add(d, world.getWorldInfo().getTerrainType().getWorldTypeName());
                add(d, world.getWorldInfo().getGeneratorOptions());
            } else add(d, "seed-preview");
            return hex(d.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    String tile(
            long seed, int dimension, String key, int x, int z, int width, int height, int step) {
        String identity = viewIdentity(seed);
        if (identity == null) return null;
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            add(d, identity);
            add(
                    d,
                    dimension + ":" + key + ":" + x + ":" + z + ":" + width + ":" + height + ":"
                            + step);
            WorldServer world = DimensionManager.getWorld(dimension);
            boolean live = world != null && world.getSeed() == seed;
            for (int row = 0; row < height; row++)
                for (int col = 0; col < width; col++) {
                    int offset = dimension == 0 && step == 4 ? 2 : 0;
                    int sx = x + col * step + offset, sz = z + row * step + offset;
                    int biome =
                            live && world.blockExists(sx, 0, sz)
                                    ? world.getBiomeGenForCoords(sx, sz).biomeID
                                    : -1;
                    d.update((byte) (biome >>> 24));
                    d.update((byte) (biome >>> 16));
                    d.update((byte) (biome >>> 8));
                    d.update((byte) biome);
                }
            return hex(d.digest());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void add(MessageDigest digest, String s) {
        digest.update(s.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static String hex(byte[] bytes) {
        StringBuilder b = new StringBuilder();
        for (byte v : bytes) b.append(String.format("%02x", v & 255));
        return b.toString();
    }
}
