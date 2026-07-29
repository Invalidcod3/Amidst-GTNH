package amidst.gtnh.worker;

import java.util.Random;

/**
 * Local replay of GregTech's XSTR. Keeping the implementation here lets the
 * worker reproduce ore-seed rolls without linking its protocol code to a
 * particular GT class package.
 */
final class GtnhXstrRandom extends Random {

    private static final long serialVersionUID = 1L;
    private long seed;

    GtnhXstrRandom(long seed) {
        this.seed = seed;
    }

    GtnhXstrRandom copy() {
        return new GtnhXstrRandom(seed);
    }

    @Override
    public synchronized void setSeed(long seed) {
        this.seed = seed;
    }

    @Override
    protected int next(int bits) {
        long value = seed;
        value ^= value << 21;
        value ^= value >>> 35;
        value ^= value << 4;
        seed = value;
        value &= (1L << bits) - 1L;
        return (int) value;
    }

    @Override
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        long value = seed ^ seed << 21;
        value ^= value >>> 35;
        value ^= value << 4;
        seed = value;
        int result = (int) value % bound;
        return result < 0 ? -result : result;
    }

    @Override
    public long nextLong() {
        return ((long) next(32) << 32) + next(32);
    }
}
