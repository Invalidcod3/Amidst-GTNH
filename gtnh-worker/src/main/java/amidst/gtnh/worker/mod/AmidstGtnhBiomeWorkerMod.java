package amidst.gtnh.worker.mod;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.DimensionManager;

@Mod(
        modid = AmidstGtnhBiomeWorkerMod.MOD_ID,
        name = "Amidst GTNH Biome Worker",
        version = "0.1.0",
        acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "*")
public final class AmidstGtnhBiomeWorkerMod {

    static final String MOD_ID = "amidstgtnhworker";

    private WorkerConfig config;
    private BiomeWorkerServer server;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        config = WorkerConfig.load(event.getSuggestedConfigurationFile());
        FMLCommonHandler.instance().bus().register(this);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        if (FMLCommonHandler.instance().getSide().isClient()) {
            startWorker();
        }
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        startWorker();
    }

    private void startWorker() {
        if (!config.enabled) {
            AmidstGtnhWorkerLog.LOG.info(
                    "GTNH biome worker is disabled. Set worker.enabled=true or "
                            + "-Dgtnh.amidst.worker.enabled=true to enable it.");
            return;
        }
        if (server == null) {
            server = new BiomeWorkerServer(config.port, config.token);
            server.start();
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        if (server != null && event.phase == TickEvent.Phase.END) {
            server.executeQueuedQueries();
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        if (server != null
                && event.phase == TickEvent.Phase.END
                && DimensionManager.getWorld(0) == null) {
            server.executeQueuedQueries();
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        if (server != null && FMLCommonHandler.instance().getSide().isServer()) {
            server.close();
            server = null;
        }
    }
}
