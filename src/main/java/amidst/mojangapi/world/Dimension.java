package amidst.mojangapi.world;

import amidst.documentation.Immutable;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.world.coordinates.Resolution;

@Immutable
public enum Dimension {
	// @formatter:off
	NETHER(  -1, "minecraft:the_nether"	, "Nether",    Resolution.NETHER),
	OVERWORLD(0, "minecraft:overworld",   "Overworld", Resolution.WORLD),
	END(      1, "minecraft:the_end",     "End",       Resolution.WORLD),
	MOON(
			-28,
			"galacticraftcore:moon",
			"Moon",
			Resolution.WORLD),
	MARS(
			-29,
			"galacticraftmars:mars",
			"Mars",
			Resolution.WORLD),
	ASTEROIDS(
			-30,
			"galacticraftasteroids:asteroids",
			"Asteroids",
			Resolution.WORLD),
	CERES(
			-1007,
			"galaxyspace:ceres",
			"Ceres",
			Resolution.WORLD),
	IO(
			-1013,
			"galaxyspace:io",
			"Io",
			Resolution.WORLD),
	ENCELADUS(
			-1016,
			"galaxyspace:enceladus",
			"Enceladus",
			Resolution.WORLD),
	PROTEUS(
			-1019,
			"galaxyspace:proteus",
			"Proteus (海卫八)",
			Resolution.WORLD),
	PLUTO(
			-1008,
			"galaxyspace:pluto",
			"Pluto",
			Resolution.WORLD),
	MEHEN_BELT(
			25,
			"amunra:asteroidbeltmehen",
			"Mehen Belt",
			Resolution.WORLD),
	ROSS_128B(
			64,
			"bartworks:ross128b",
			"Ross 128b",
			Resolution.WORLD),
	BARNARDA_C(
			-1022,
			"galaxyspace:barnarda_c",
			"Barnarda C",
			Resolution.WORLD),
	DEEP_DARK(
			100,
			"extrautilities:deep_dark",
			"Deep Dark",
			Resolution.WORLD),
	ANUBIS(
			22,
			"amunra:anubis",
			"Anubis",
			Resolution.WORLD),
	HORUS(
			23,
			"amunra:horus",
			"Horus",
			Resolution.WORLD),
	TWILIGHT_FOREST(
			 7,
			"twilightforest:twilight_forest",
			"Twilight Forest",
			Resolution.WORLD);
	// @formatter:on

	public static Dimension fromId(int id) {
		for (Dimension dimension : values()) {
			if (id == dimension.getId()) {
				return dimension;
			}
		}
		AmidstLogger.warn("Unsupported dimension id: {}. Falling back to Overworld.", id);
		return OVERWORLD;
	}

	public static Dimension fromName(String name) {
		for (Dimension dimension : values()) {
			if (dimension.getName().equals(name)) {
				return dimension;
			}
		}
		AmidstLogger.warn("Unsupported dimension name: {}. Falling back to Overworld.", name);
		return OVERWORLD;
	}

	public static String[] getSelectable() {
		return new String[] {
				OVERWORLD.getDisplayName(),
				NETHER.getDisplayName(),
				END.getDisplayName(),
				MOON.getDisplayName(),
				MARS.getDisplayName(),
				ASTEROIDS.getDisplayName(),
				CERES.getDisplayName(),
				IO.getDisplayName(),
				ENCELADUS.getDisplayName(),
				PROTEUS.getDisplayName(),
				PLUTO.getDisplayName(),
				MEHEN_BELT.getDisplayName(),
				ROSS_128B.getDisplayName(),
				BARNARDA_C.getDisplayName(),
				DEEP_DARK.getDisplayName(),
				ANUBIS.getDisplayName(),
				HORUS.getDisplayName(),
				TWILIGHT_FOREST.getDisplayName()
		};
	}

	private final int id;
	private final String name;
	private final String displayName;
	private final Resolution resolution;

	private Dimension(int id, String name, String displayName, Resolution resolution) {
		this.id = id;
		this.name = name;
		this.displayName = displayName;
		this.resolution = resolution;
	}

	public int getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Resolution getResolution() {
		return resolution;
	}

	@Override
	public String toString() {
		return displayName;
	}
}
