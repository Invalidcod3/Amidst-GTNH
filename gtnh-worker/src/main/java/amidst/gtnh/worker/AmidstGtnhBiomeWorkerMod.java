package amidst.gtnh.worker;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartedEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;

@Mod(
        modid = AmidstGtnhBiomeWorkerMod.MOD_ID,
        name = "Amidst GTNH Biome Worker",
        version = "0.3.0",
        acceptedMinecraftVersions = "[1.7.10]",
        dependencies = "before:RWG",
        acceptableRemoteVersions = "*")
public final class AmidstGtnhBiomeWorkerMod {

    static final String MOD_ID = "amidstgtnhworker";

    private WorkerConfig config;
    // Published by either the client or dedicated-server lifecycle, and read
    // from both tick/event threads when an integrated server is running.
    private volatile BiomeWorkerServer server;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        try {
            RwgRuntimeClasses.install();
        } catch (RuntimeException | LinkageError e) {
            AmidstGtnhWorkerLog.LOG.warn("RWG runtime inspection unavailable; keeping full native replay", e);
        }
        config = WorkerConfig.load(event.getSuggestedConfigurationFile());
        FMLCommonHandler.instance().bus().register(this);
        MinecraftForge.EVENT_BUS.register(this);
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

    private synchronized void startWorker() {
        if (!config.enabled) {
            AmidstGtnhWorkerLog.LOG.info(
                    "GTNH biome worker is disabled. Set worker.enabled=true or "
                            + "-Dgtnh.amidst.worker.enabled=true to enable it.");
            return;
        }
        if (server == null) {
            BiomeWorkerServer candidate = new BiomeWorkerServer(config.port, config.token);
            if (candidate.start()) {
                server = candidate;
                WorkerTickHooks.setWorker(candidate);
                AmidstGtnhWorkerLog.LOG.info(
                        "GTNH biome worker loaded from {}",
                        AmidstGtnhBiomeWorkerMod.class.getProtectionDomain().getCodeSource().getLocation());
            }
        }
    }

    @SubscribeEvent
    public void serverTick(TickEvent.ServerTickEvent event) {
        BiomeWorkerServer currentServer = server;
        if (currentServer != null) {
            if (event.phase == TickEvent.Phase.START) currentServer.beginGameTick();
            else currentServer.onForgeTickEnd();
        }
    }

    @SubscribeEvent
    public void clientTick(TickEvent.ClientTickEvent event) {
        BiomeWorkerServer currentServer = server;
        if (currentServer != null
                && DimensionManager.getWorld(0) == null) {
            if (event.phase == TickEvent.Phase.START) currentServer.beginGameTick();
            else currentServer.executeQueuedQueries();
        }
    }

    @SubscribeEvent
    public void chunkLoaded(ChunkEvent.Load event) {
        recordChunkStateChange(event);
    }

    @SubscribeEvent
    public void chunkUnloaded(ChunkEvent.Unload event) {
        recordChunkStateChange(event);
    }

    private void recordChunkStateChange(ChunkEvent event) {
        BiomeWorkerServer currentServer = server;
        if (currentServer == null) {
            return;
        }
        try {
            if (event.world != null
                    && !event.world.isRemote
                    && event.world.provider != null
                    && event.world.provider.dimensionId == 0) {
                currentServer.recordOverworldChunkChange(
                        event.getChunk().xPosition,
                        event.getChunk().zPosition);
            }
        } catch (LinkageError e) {
            // Last-resort containment for an incorrectly installed developer
            // JAR or incompatible runtime. Never turn a map refresh into a
            // world-load crash; log once, then disable this optional service.
            disableIncompatibleWorker(currentServer, e);
        }
    }

    private synchronized void disableIncompatibleWorker(BiomeWorkerServer failedServer, LinkageError cause) {
        if (server == failedServer) {
            server = null;
            WorkerTickHooks.setWorker(null);
            AmidstGtnhWorkerLog.LOG.error(
                    "GTNH biome worker disabled after a Minecraft linkage failure. "
                            + "Install the verified reobfuscated Worker JAR from build/release. "
                            + "See docs/worker-troubleshooting.md for build and runtime checks.",
                    cause);
            failedServer.close();
        }
    }

    @Mod.EventHandler
    public synchronized void serverStopping(FMLServerStoppingEvent event) {
        if (server != null && FMLCommonHandler.instance().getSide().isServer()) {
            WorkerTickHooks.setWorker(null);
            server.close();
            server = null;
        }
    }
}
