package amidst.gtnh.worker;

/** Resumable game-thread overlay; pure seed data is never overwritten by save data. */
final class PredictedBiomeOverlay {
    final int[] result;
    private final int x, z, width, step, offset;
    private final OverworldTileCache.LoadedBiome loaded;
    private int index;

    PredictedBiomeOverlay(
            int[] prediction,
            int x,
            int z,
            int width,
            int step,
            OverworldTileCache.LoadedBiome loaded) {
        result = prediction.clone();
        this.x = x;
        this.z = z;
        this.width = width;
        this.step = step;
        offset = step == 4 ? 2 : 0;
        this.loaded = loaded;
    }

    boolean advance(long deadline) {
        while (index < result.length) {
            if (System.nanoTime() >= deadline) return false;
            int actual =
                    loaded.getOrMissing(
                            x + index % width * step + offset, z + index / width * step + offset);
            if (actual >= 0) result[index] = actual;
            index++;
        }
        return true;
    }
}
