package amidst.gtnh.worker;

import static org.junit.Assert.*;

import org.junit.Test;

public class StoredBiomeAccessTest {
    public static class ExtendedChunk extends net.minecraft.world.chunk.Chunk {
        final short[] biomes = new short[256];

        public ExtendedChunk() {
            super(null, 0, 0);
            java.util.Arrays.fill(biomes, (short) -1);
        }

        public short[] getBiomeShortArray() {
            return biomes;
        }

        @Override
        public byte[] getBiomeArray() {
            throw new AssertionError("EndlessIDs forbids byte access");
        }
    }

    @Test
    public void readsExtendedStorageWithoutFallingBackOrFillingUnknownEntries() {
        ExtendedChunk chunk = new ExtendedChunk();
        chunk.biomes[0] = 300;
        chunk.biomes[255] = 329;
        assertEquals(300, StoredBiomeAccess.at(chunk, 0));
        assertEquals(329, StoredBiomeAccess.at(chunk, 255));
        assertEquals(-1, StoredBiomeAccess.at(chunk, 1));
        assertEquals((short) -1, chunk.biomes[1]);
        net.minecraft.world.chunk.Chunk vanilla = new net.minecraft.world.chunk.Chunk(null, 0, 0);
        vanilla.getBiomeArray()[0] = (byte) 200;
        assertEquals(200, StoredBiomeAccess.at(vanilla, 0));
    }

    @Test
    public void extendedIdsAreUnsignedAndHaveTheirOwnMissingValue() {
        for (int id : new int[] {0, 254, 255, 256, 300, 329, 32768, 65534})
            assertEquals(id, StoredBiomeAccess.decode((short) id));
        assertEquals(-1, StoredBiomeAccess.decode((short) 65535));
        assertEquals(-1, StoredBiomeAccess.decode((byte) 255));
        assertEquals(200, StoredBiomeAccess.decode((byte) 200));
    }
}
