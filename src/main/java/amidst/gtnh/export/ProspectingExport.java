package amidst.gtnh.export;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BiConsumer;
import amidst.gtnh.prospecting.ProspectingData.*;

/** Bounded, cancellable tile scan. A filter is sent to the worker before any export objects are built. */
public final class ProspectingExport {
    public record Options(QueryFilter filter, boolean allFluidChunks, int limit) {
        public Options {
            Objects.requireNonNull(filter);
            if (limit < 1 || limit > 10000) throw new IllegalArgumentException("Export limit must be between 1 and 10000.");
        }
    }
    public record Result(List<GtnhCoordinate> coordinates, boolean reachedLimit) {}
    @FunctionalInterface public interface Source { Tile load(int x, int z, QueryFilter filter) throws Exception; }
    public static Result locate(Source source, Options options, int x1, int z1, int x2, int z2,
            BiConsumer<Integer,Integer> progress) throws Exception {
        int minX = Math.min(x1,x2), maxX = Math.max(x1,x2), minZ = Math.min(z1,z2), maxZ = Math.max(z1,z2);
        long firstX = Math.floorDiv(minX,512) * 512L, firstZ = Math.floorDiv(minZ,512) * 512L;
        long lastX = Math.floorDiv(maxX,512) * 512L, lastZ = Math.floorDiv(maxZ,512) * 512L;
        long total = ((lastX-firstX)/512+1)*((lastZ-firstZ)/512+1);
        if (total > GtnhCoordinateLocator.MAX_FRAGMENT_QUERIES) throw new IllegalArgumentException("Reduce the export range (maximum 4096 map queries).");
        Map<String,GtnhCoordinate> found = new LinkedHashMap<>(); int completed = 0;
        for (long z = firstZ; z <= lastZ; z += 512) for (long x = firstX; x <= lastX; x += 512) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            Tile tile = source.load((int)x,(int)z,options.filter);
            for (Deposit deposit : tile.deposits) {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException();
                if (!options.filter.accepts(deposit)) continue;
                for (GtnhCoordinate point : points(deposit, options, minX,minZ,maxX,maxZ)) {
                    found.putIfAbsent(point.x()+","+point.z(), point);
                    if (found.size() >= options.limit) return new Result(List.copyOf(found.values()),true);
                }
            }
            progress.accept(++completed,(int)total);
        }
        return new Result(List.copyOf(found.values()),false);
    }
    public static List<GtnhCoordinate> points(Deposit d, Options options, int minX, int minZ, int maxX, int maxZ) {
        List<GtnhCoordinate> result = new ArrayList<>();
        if (d.amounts == null) {
            if (inside(d.x,d.z,minX,minZ,maxX,maxZ)) result.add(new GtnhCoordinate(d.x,d.z,
                    d.name + " [" + d.source + (d.kind == null ? "" : ", " + amidst.i18n.I18n.text(
                            "ASTEROID_SMALL".equals(d.kind) ? "Small-ore asteroid" : "Ore-mix asteroid")) + "] Y="
                            + (d.y == null ? d.minY + "–" + d.maxY : d.y), d.y));
            return result;
        }
        int bestAmount = -1; GtnhCoordinate best = null;
        for (int i = 0; i < 64; i++) {
            int amount = d.amounts[i], x = d.x + i/8*16+8, z = d.z + i%8*16+8;
            if (amount < options.filter.minimumFluid || !inside(x,z,minX,minZ,maxX,maxZ)) continue;
            String origin = d.currentAmounts != null && d.currentAmounts[i] ? "CURRENT" : "INITIAL";
            GtnhCoordinate point = new GtnhCoordinate(x,z,d.name + " · " + amount + " L/Op [" + origin + "]");
            if (options.allFluidChunks) result.add(point);
            else if (amount > bestAmount) { bestAmount = amount; best = point; }
        }
        if (best != null) result.add(best);
        return result;
    }
    private static boolean inside(int x,int z,int minX,int minZ,int maxX,int maxZ) {
        return x>=minX && x<=maxX && z>=minZ && z<=maxZ;
    }
}
