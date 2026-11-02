package amidst.gtnh.worker;

/** Wire/display IDs are ints; the entire EndlessIDs registry is reserved for raw IDs. */
final class DisplayBiomeIds {
    static final int RAW_ID_COUNT = 1 << 16;
    static final int SYNTHETIC_BASE = RAW_ID_COUNT;
    static final int SPACE_BASE = 2 * RAW_ID_COUNT;
    static final int ADDITIONAL_BASE = 16 * RAW_ID_COUNT;

    private DisplayBiomeIds() {}

    static int raw(int biomeId) {
        // 0xffff is an unknown sentinel in chunk storage, but can still occur in a registry.
        if (biomeId < 0 || biomeId >= RAW_ID_COUNT) {
            throw new IllegalArgumentException(
                    "Runtime biome ID is outside the 16-bit range: " + biomeId);
        }
        return biomeId;
    }

    static int scoped(int base, int index, int biomeId) {
        return base + index * RAW_ID_COUNT + raw(biomeId);
    }
}
