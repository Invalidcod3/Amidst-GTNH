package amidst.mojangapi.world;

import amidst.documentation.Immutable;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.world.coordinates.Resolution;

@Immutable
public enum Dimension {
    EVERGLADES(-2001, "Everglades", "Everglades", Resolution.WORLD),
    ROSS_128BA(-2002, "Ross128ba", "Ross 128ba", Resolution.WORLD),
    TRITON(-2003, "Triton", "Triton", Resolution.WORLD),
    OBERON(-2004, "Oberon", "Oberon", Resolution.WORLD),
    TITAN(-2005, "Titan", "Titan", Resolution.WORLD),
    CALLISTO(-2006, "Callisto", "Callisto", Resolution.WORLD),
    GANYMEDE(-2007, "Ganymede", "Ganymede", Resolution.WORLD),
    DEIMOS(-2008, "Deimos", "Deimos", Resolution.WORLD),
    EUROPA(-2009, "Europa", "Europa", Resolution.WORLD),
    PHOBOS(-2010, "Phobos", "Phobos", Resolution.WORLD),
    VENUS(-2011, "Venus", "Venus", Resolution.WORLD),
    MERCURY(-2012, "Mercury", "Mercury", Resolution.WORLD),
    MAKEMAKE(-2013, "MakeMake", "Makemake", Resolution.WORLD),
    HAUMEA(-2014, "Haumea", "Haumea", Resolution.WORLD),
    CENTAURI_BB(-2015, "CentauriBb", "Alpha Centauri Bb", Resolution.WORLD),
    VEGA_B(-2016, "VegaB", "Vega B", Resolution.WORLD),
    BARNARDA_E(-2017, "BarnardE", "Barnarda E", Resolution.WORLD),
    BARNARDA_F(-2018, "BarnardF", "Barnarda F", Resolution.WORLD),
    TAU_CETI_E(-2019, "TcetiE", "Tau Ceti E", Resolution.WORLD),
    MIRANDA(-2020, "Miranda", "Miranda", Resolution.WORLD),
    KUIPER_BELT(-2021, "KuiperBelt", "Kuiper Belt", Resolution.WORLD),
    NEPER(-2022, "Neper", "Neper", Resolution.WORLD),
    MAAHES(-2023, "Maahes", "Maahes", Resolution.WORLD),
    SETH(-2024, "Seth", "Seth", Resolution.WORLD),
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

    /** Additional entries use stable local preference IDs; the worker resolves the actual configured ID. */
    public boolean isProspectingOnly() { return id <= -2001 && id >= -2024; }

    public String prospectingKey() {
        return switch (this) {
            case OVERWORLD -> "Overworld";
            case NETHER -> "Nether";
            case END -> "TheEnd";
            case MOON -> "Moon";
            case MARS -> "Mars";
            case ASTEROIDS -> "Asteroids";
            case CERES -> "Ceres";
            case IO -> "Io";
            case ENCELADUS -> "Enceladus";
            case PROTEUS -> "Proteus";
            case PLUTO -> "Pluto";
            case MEHEN_BELT -> "MehenBelt";
            case ROSS_128B -> "Ross128b";
            case BARNARDA_C -> "BarnardC";
            case DEEP_DARK -> "DeepDark";
            case ANUBIS -> "Anubis";
            case HORUS -> "Horus";
            case TWILIGHT_FOREST -> "TwilightForest";
            default -> name;
        };
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
		return amidst.i18n.I18n.text(displayName);
	}
}
