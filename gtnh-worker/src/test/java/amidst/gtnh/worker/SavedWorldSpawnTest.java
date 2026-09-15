package amidst.gtnh.worker;

import static org.junit.Assert.*;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;

import org.junit.Test;

public class SavedWorldSpawnTest {
    public static class WorldDouble extends WorldServer {
        ChunkCoordinates spawn;

        private WorldDouble() {
            super(null, null, "unused", 0, null, null);
        }

        @Override
        public long getSeed() {
            return 71;
        }

        @Override
        public ChunkCoordinates getSpawnPoint() {
            return spawn;
        }

        @Override
        public net.minecraft.world.chunk.Chunk getChunkFromChunkCoords(int x, int z) {
            throw new AssertionError("Must not load terrain");
        }
    }

    private WorldDouble world(int x, int z) throws Exception {
        java.lang.reflect.Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        WorldDouble world =
                (WorldDouble)
                        ((sun.misc.Unsafe) field.get(null)).allocateInstance(WorldDouble.class);
        world.spawn = new ChunkCoordinates(x, 75, z);
        return world;
    }

    @Test
    public void readsMatchingProviderSpawnAndTracksSameSeedSaveOrCommandChanges() throws Exception {
        WorldDouble first = world(1200, -870), second = world(-1900, 710);
        assertNull(SavedWorldSpawn.read(null, 71));
        assertNull(SavedWorldSpawn.read(first, 72));
        ChunkCoordinates snapshot = SavedWorldSpawn.read(first, 71);
        assertEquals(1200, snapshot.posX);
        assertEquals(75, snapshot.posY);
        assertEquals(-870, snapshot.posZ);
        assertEquals(-1900, SavedWorldSpawn.read(second, 71).posX);
        first.spawn.posX = 1600;
        assertEquals(1200, snapshot.posX);
        assertEquals(1600, SavedWorldSpawn.read(first, 71).posX);
    }
}
