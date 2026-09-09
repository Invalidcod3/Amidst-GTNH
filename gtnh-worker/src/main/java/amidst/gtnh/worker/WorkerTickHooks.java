package amidst.gtnh.worker;

/**
 * Entry point injected at IntegratedServer.tick's normal returns. That method
 * still runs on the server thread while ESC pauses the world, whereas Forge's
 * ServerTickEvent only runs inside the skipped MinecraftServer.tick call.
 *
 * Keep this class free of client-only types: the mod also runs on dedicated
 * servers. Network threads must never call this hook or query a WorldServer.
 */
public final class WorkerTickHooks {
    private static volatile BiomeWorkerServer worker;

    private WorkerTickHooks() {}

    // Called only by the mod's synchronized start/stop lifecycle methods.
    static void setWorker(BiomeWorkerServer currentWorker) {
        worker = currentWorker;
    }

    public static void onIntegratedServerTickEnd() {
        BiomeWorkerServer currentWorker = worker;
        if (currentWorker != null) {
            // Forge END and this hook share one time budget. A yielded query
            // must not receive a second budget in the same tick.
            // No pause flag, world tick, or client thread is involved here.
            currentWorker.onIntegratedTickEnd();
        }
    }
}
