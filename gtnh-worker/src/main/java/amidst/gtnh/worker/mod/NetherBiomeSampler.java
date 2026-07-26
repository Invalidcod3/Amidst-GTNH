package amidst.gtnh.worker.mod;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldType;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.biome.WorldChunkManagerHell;
import net.minecraftforge.common.DimensionManager;

/**
 * Samples the Nether through the dimension provider registered by the running
 * GTNH instance. This deliberately does not share any code with the RWG
 * overworld sampler: Nether provider replacements and their configured biome
 * managers remain authoritative.
 */
interface NetherBiomeSampler {

    BiomeGenBase getBiomeAt(int x, int z);
}

final class NetherBiomeSamplers {

    private static final int NETHER = -1;
    private static final String BOP_NETHER_PROVIDER =
            "biomesoplenty.common.world.WorldProviderBOPHell";
    private static final String BOP_NETHER_MANAGER =
            "biomesoplenty.common.world.WorldChunkManagerBOPHell";

    private NetherBiomeSamplers() {}

    static NetherBiomeSampler create(final WorldServer world) {
        final WorldChunkManager manager = world.getWorldChunkManager();
        return new NetherBiomeSampler() {

            @Override
            public BiomeGenBase getBiomeAt(int x, int z) {
                /*
                 * A loaded chunk's biome array is authoritative. blockExists
                 * only checks the loaded provider and does not generate or load
                 * the requested chunk.
                 */
                if (world.blockExists(x, 0, z)) {
                    return world.getBiomeGenForCoords(x, z);
                }
                return manager.getBiomeGenAt(x, z);
            }
        };
    }

    static NetherBiomeSampler create(final long seed) {
        /*
         * The old implementation used World(ISaveHandler, String,
         * WorldProvider, WorldSettings, Profiler) to initialise a provider.
         * That constructor is @SideOnly(CLIENT) in 1.7.10 and is stripped from
         * a dedicated server, so the first Nether request failed with a
         * NoSuchMethodError.
         *
         * BOP exposes the same save-free constructor used by its Nether
         * provider: WorldChunkManagerBOPHell(long, WorldType). Instantiate it
         * directly so arbitrary seeds run BOP's complete BiomeLayerHell chain
         * without creating a client-only temporary World.
         */
        WorldProvider provider = DimensionManager.createProviderFor(NETHER);
        final WorldChunkManager manager;
        if (hasTypeNamed(provider.getClass(), BOP_NETHER_PROVIDER)) {
            manager = createBopManager(seed);
            AmidstGtnhWorkerLog.LOG.info(
                    "Prepared BOP Nether biome preview context for seed {}",
                    Long.valueOf(seed));
        } else {
            manager = new WorldChunkManagerHell(BiomeGenBase.hell, 0.0F);
            AmidstGtnhWorkerLog.LOG.warn(
                    "Nether dimension -1 uses provider {}; falling back to the vanilla Hell biome manager",
                    provider.getClass().getName());
        }
        return new NetherBiomeSampler() {

            @Override
            public BiomeGenBase getBiomeAt(int x, int z) {
                return manager.getBiomeGenAt(x, z);
            }
        };
    }

    private static WorldChunkManager createBopManager(long seed) {
        try {
            Class<?> managerClass = Class.forName(BOP_NETHER_MANAGER);
            Constructor<?> constructor =
                    managerClass.getConstructor(Long.TYPE, WorldType.class);
            WorldType worldType = WorldType.parseWorldType("RWG");
            if (worldType == null) {
                throw new IllegalStateException("RWG world type is not registered");
            }
            Object manager = constructor.newInstance(Long.valueOf(seed), worldType);
            if (!(manager instanceof WorldChunkManager)) {
                throw new IllegalStateException(
                        BOP_NETHER_MANAGER + " is not a Minecraft WorldChunkManager");
            }
            return (WorldChunkManager) manager;
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException(
                    "BOP Nether biome layers failed to initialize for seed " + seed,
                    cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "BOP Nether provider is active, but "
                            + BOP_NETHER_MANAGER
                            + "(long, WorldType) is unavailable",
                    e);
        }
    }

    private static boolean hasTypeNamed(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            if (name.equals(current.getName())) {
                return true;
            }
            current = current.getSuperclass();
        }
        return false;
    }
}
