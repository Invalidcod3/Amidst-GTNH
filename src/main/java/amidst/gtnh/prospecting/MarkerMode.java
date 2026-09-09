package amidst.gtnh.prospecting;

public enum MarkerMode {
    STRUCTURE("Structure"), ORES("Ores"), FLUID("Fluid");
    private final String label;
    MarkerMode(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
