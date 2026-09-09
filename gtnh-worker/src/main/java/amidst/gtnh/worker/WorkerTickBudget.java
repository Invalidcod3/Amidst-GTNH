package amidst.gtnh.worker;

/** Budget the current game tick, never gate the next tick on a wall-clock interval. */
final class WorkerTickBudget {
    private final long maximum;
    private long tickStart;
    WorkerTickBudget(long maximum) { this.maximum = maximum; }
    void begin(long now) { tickStart = now; }
    long finish(long now) {
        long spent = tickStart == 0 ? 0 : Math.max(0, now - tickStart);
        tickStart = 0;
        // Keep 5 ms spare in a normal 50 ms tick; a busy world gets at most 1 ms.
        return Math.min(maximum, Math.max(1_000_000L, 45_000_000L - spent));
    }
}
