package amidst.gtnh.worker;

import java.util.Random;

/**
 * Seed-only prefix of GT5U 5.09.54.133 WorldGeneratorSpace.AsteroidGenerator.forChunk. Registry
 * lookups use cloned RNGs: consuming the coordinate RNG there moves every asteroid. No world reads,
 * population callbacks or block placement belong in this class.
 */
final class AsteroidOrePredictor {
    interface Registry {
        Object stone(Random random) throws ReflectiveOperationException;

        Object ore(Object stone, boolean small, Random random) throws ReflectiveOperationException;
    }

    static final class Config {
        final boolean enabled;
        final int probability, minY, maxY, minRadius, maxRadius;

        Config(boolean enabled, int probability, int minY, int maxY, int minRadius, int maxRadius) {
            this.enabled = enabled;
            this.probability = probability;
            this.minY = minY;
            this.maxY = maxY;
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
        }
    }

    static final class Candidate {
        final Object layer;
        final boolean small;
        final int x, y, z, radius;

        Candidate(Object layer, boolean small, int x, int y, int z, int radius) {
            this.layer = layer;
            this.small = small;
            this.x = x;
            this.y = y;
            this.z = z;
            this.radius = radius;
        }
    }

    static Candidate select(
            long seed, int dimension, int cx, int cz, Config config, Registry registry)
            throws ReflectiveOperationException {
        if (!config.enabled) return null;
        if (config.maxY <= config.minY
                || config.minRadius < 1
                || config.maxRadius < config.minRadius)
            throw new IllegalArgumentException("Invalid asteroid height or size configuration");
        long randomSeed = cx * 341873128712L + cz * 132897987541L + dimension + 588283L + seed;
        // Upstream uses <=, so probability zero still accepts roll zero.
        if (new GtnhXstrRandom(randomSeed).nextInt(100) > config.probability) return null;
        GtnhXstrRandom random = new GtnhXstrRandom(randomSeed);
        Object stone = registry.stone(random.copy());
        if (stone == null) return null;
        boolean small = random.nextInt(5) == 0;
        Object layer = registry.ore(stone, small, random.copy());
        if (layer == null) return null;
        int x = cx * 16 + random.nextInt(16);
        int y = config.minY + random.nextInt(config.maxY - config.minY);
        int z = cz * 16 + random.nextInt(16);
        int radius = config.minRadius + random.nextInt(config.maxRadius - config.minRadius + 1);
        return new Candidate(layer, small, x, y, z, radius);
    }
}
