package amidst.gtnh.worker;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.gen.MapGenBase;
import net.minecraft.world.gen.MapGenCaves;
import net.minecraftforge.event.terraingen.InitMapGenEvent.EventType;
import net.minecraftforge.event.terraingen.TerrainGen;

import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/** RWG's post-surface cave stage, confined to a seed-only world and disposable blocks. */
final class RwgSpawnCaves {
    private final World world;
    private final MapGenBase generator;
    private final BooleanSupplier enabled;

    static RwgSpawnCaves create(World world) throws ReflectiveOperationException {
        if (world == null)
            throw new IllegalArgumentException("Spawn cave replay needs a seed-only world");
        Field setting = Class.forName("rwg.config.ConfigRWG").getField("generateCaves");
        // Same registration hook as ChunkGeneratorRealistic's constructor. Never borrow a live
        // world's mutable map generator or dispatch population/structure generation.
        MapGenBase generator = TerrainGen.getModdedMapGen(new MapGenCaves(), EventType.CAVE);
        return new RwgSpawnCaves(
                world,
                generator,
                () -> {
                    try {
                        return setting.getBoolean(null);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Cannot read RWG cave setting", e);
                    }
                });
    }

    RwgSpawnCaves(World world, MapGenBase generator, BooleanSupplier enabled) {
        this.world = world;
        this.generator = generator;
        this.enabled = enabled;
    }

    void carve(int chunkX, int chunkZ, Block[] blocks) {
        if (enabled.getAsBoolean()) generator.func_151539_a(null, world, chunkX, chunkZ, blocks);
    }
}
