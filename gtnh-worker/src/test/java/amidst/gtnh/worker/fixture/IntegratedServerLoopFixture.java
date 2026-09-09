package amidst.gtnh.worker.fixture;

/** Small executable model of 1.7.10's server loop; no Minecraft boot required. */
public class IntegratedServerLoopFixture {
    public boolean paused;
    public int worldTicks;
    public Runnable forgeEndTick;

    public void tick() {
        if (paused) {
            return;
        }
        worldTicks++;
        forgeEndTick.run();
    }
}
