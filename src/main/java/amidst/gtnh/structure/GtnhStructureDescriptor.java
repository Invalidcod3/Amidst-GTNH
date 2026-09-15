package amidst.gtnh.structure;

public record GtnhStructureDescriptor(
		String kind,
		String subtype,
		int x,
		int z,
		String certainty,
        Integer y) {
    public GtnhStructureDescriptor(String kind, String subtype, int x, int z, String certainty) {
        this(kind, subtype, x, z, certainty, null);
    }
}
