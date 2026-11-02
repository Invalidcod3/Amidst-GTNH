package amidst.gtnh.worker;

import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.lang.reflect.Method;

/** Reads existing evidence without asking a chunk manager to fill missing biome entries. */
final class StoredBiomeAccess {
    private StoredBiomeAccess() {}

    private static final ClassValue<java.util.Optional<Method>> EXTENDED =
            new ClassValue<java.util.Optional<Method>>() {
                @Override
                protected java.util.Optional<Method> computeValue(Class<?> type) {
                    try {
                        return java.util.Optional.of(type.getMethod("getBiomeShortArray"));
                    } catch (NoSuchMethodException vanilla) {
                        return java.util.Optional.empty();
                    }
                }
            };

    static int at(WorldServer world, int x, int z) {
        if (world == null || !world.blockExists(x, 0, z)) return -1;
        return at(world.getChunkFromBlockCoords(x, z), ((z & 15) << 4) | (x & 15));
    }

    static int at(Chunk chunk, int index) {
        try {
            java.util.Optional<Method> extended = EXTENDED.get(chunk.getClass());
            return extended.isPresent()
                    ? decode(((short[]) extended.get().invoke(chunk))[index])
                    : decode(chunk.getBiomeArray()[index]);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read EndlessIDs biome evidence", e);
        }
    }

    static int decode(short value) {
        return value == (short) -1 ? -1 : value & 0xffff;
    }

    static int decode(byte value) {
        return value == (byte) -1 ? -1 : value & 0xff;
    }
}
