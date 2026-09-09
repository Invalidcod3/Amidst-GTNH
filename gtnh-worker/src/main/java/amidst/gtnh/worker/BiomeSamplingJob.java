package amidst.gtnh.worker;

/** Cooperative rectangular sampling; all coordinates remain on the original lattice. */
final class BiomeSamplingJob {
    interface Source { int[] sample(int x, int z, int width, int height); }
    private static final int PATCH_SIZE = 16;
    final int[] result;
    private final int x, z, width, height, step;
    private final Source source;
    private int column, row;

    BiomeSamplingJob(int x, int z, int width, int height, int step, Source source) {
        this.x = x; this.z = z; this.width = width; this.height = height; this.step = step;
        this.source = source;
        result = new int[Math.multiplyExact(width, height)];
    }

    boolean advance(long deadline) { return advance(deadline, System::nanoTime); }

    boolean advance(long deadline, java.util.function.LongSupplier clock) {
        while (row < height) {
            if (clock.getAsLong() >= deadline) return false;
            int w = Math.min(PATCH_SIZE, width - column), h = Math.min(PATCH_SIZE, height - row);
            int[] patch = source.sample(x + column * step, z + row * step, w, h);
            if (patch.length != w * h) throw new IllegalStateException("Biome patch size mismatch");
            for (int i = 0; i < h; i++) System.arraycopy(patch, i * w, result, (row + i) * width + column, w);
            column += w;
            if (column == width) { column = 0; row += h; }
        }
        return true;
    }
}
