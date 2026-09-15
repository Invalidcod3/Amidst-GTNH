package amidst.gtnh.cache;

import amidst.gtnh.prospecting.MarkerMode;
import amidst.mojangapi.world.Dimension;

import com.google.gson.Gson;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public record MapViewState(
        int version,
        long x,
        long z,
        int zoom,
        String dimension,
        String mode,
        String filter,
        int minimumFluid) {
    public boolean valid() {
        try {
            Dimension.valueOf(dimension);
            MarkerMode.valueOf(mode);
            return version == 1
                    && x >= -30_000_000L
                    && x <= 30_000_000L
                    && z >= -30_000_000L
                    && z <= 30_000_000L
                    && zoom >= -40
                    && zoom <= 160
                    && filter != null
                    && filter.length() <= 512
                    && minimumFluid >= 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static MapViewState read(Path root, String identity) {
        if (identity == null) return null;
        try {
            Path path = root.resolve(MapStorage.hash(identity) + ".json");
            if (Files.size(path) > 8192) return null;
            MapViewState state = new Gson().fromJson(Files.readString(path), MapViewState.class);
            return state != null && state.valid() ? state : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void save(Path root, String identity) {
        if (identity == null || !valid()) return;
        Path tmp = null;
        try {
            Files.createDirectories(root);
            tmp = Files.createTempFile(root, "view-", ".tmp");
            Files.writeString(tmp, new Gson().toJson(this), StandardCharsets.UTF_8);
            MapStorage.move(tmp, root.resolve(MapStorage.hash(identity) + ".json"));
        } catch (Exception e) {
            amidst.logging.AmidstLogger.warn("Cannot save map view: " + e.getMessage());
        } finally {
            if (tmp != null)
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
        }
    }
}
