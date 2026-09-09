package rwg.surface;

/** Reflection-shape fixture only; real painter bytecode is tested separately. */
public final class SurfaceDuneValley {
    private final float valley;
    private final boolean mix;

    public SurfaceDuneValley(float valley, boolean mix) {
        this.valley = valley;
        this.mix = mix;
    }
}
