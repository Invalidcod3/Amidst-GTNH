package amidst.gtnh.export;

public record GtnhCoordinate(int x, int z, String name, Integer y) {
    public GtnhCoordinate(int x, int z, String name) { this(x, z, name, null); }
}
