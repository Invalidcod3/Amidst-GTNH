package amidst.gtnh.worker;

import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.WorldServer;

/** Reads the provider's saved spawn on the game thread; never loads a chunk. */
final class SavedWorldSpawn {
    static ChunkCoordinates read(WorldServer world, long seed) {
        if (world == null || world.getSeed() != seed) return null;
        ChunkCoordinates spawn = world.getSpawnPoint();
        return spawn == null ? null : new ChunkCoordinates(spawn.posX, spawn.posY, spawn.posZ);
    }
}
