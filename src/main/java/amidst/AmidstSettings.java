package amidst;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.prefs.Preferences;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.ThreadSafe;
import amidst.gui.main.AmidstLookAndFeel;
import amidst.gtnh.structure.GtnhRoguelikeDungeonType;
import amidst.gtnh.structure.GtnhEndStructureType;
import amidst.gtnh.structure.GtnhSpaceStructureType;
import amidst.gtnh.structure.GtnhTwilightForestFeatureType;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldType;
import amidst.settings.Setting;
import amidst.settings.biomeprofile.BiomeProfile;
import amidst.settings.biomeprofile.BiomeProfileSelection;

@ThreadSafe
public class AmidstSettings {
    public final Setting<amidst.i18n.Language> language;
    public final Setting<amidst.gtnh.prospecting.MarkerMode> markerMode =
            Setting.createDummy(amidst.gtnh.prospecting.MarkerMode.STRUCTURE);
    public final Setting<String> prospectingFilter = Setting.createDummy("");
    public final Setting<Integer> prospectingMinimumFluid = Setting.createDummy(0);
	public final Setting<Dimension> dimension;
	public final Setting<Boolean> showGrid;
	public final Setting<Boolean> showSlimeChunks;
	public final Setting<Boolean> showSpawn;
	public final Setting<Boolean> showStrongholds;
	public final Setting<Boolean> showPlayers;
	public final Setting<Boolean> showVillages;
	public final Setting<Boolean> showTemples;
	public final Setting<Boolean> showMineshafts;
	public final Setting<Boolean> showOceanMonuments;
	public final Setting<Boolean> showWoodlandMansions;
	public final Setting<Boolean> showOceanFeatures;
	public final Setting<Boolean> showNetherFortresses;
	public final Setting<Boolean> showEndCities;
	public final Setting<Boolean> showGtnhRoguelikeDesert;
	public final Setting<Boolean> showGtnhRoguelikeForest;
	public final Setting<Boolean> showGtnhRoguelikeIce;
	public final Setting<Boolean> showGtnhRoguelikeJungle;
	public final Setting<Boolean> showGtnhRoguelikeMesa;
	public final Setting<Boolean> showGtnhRoguelikeMountain;
	public final Setting<Boolean> showGtnhRoguelikePlains;
	public final Setting<Boolean> showGtnhRoguelikeSwamp;
	public final Setting<Boolean> showGtnhStrongholds;
	public final Setting<Boolean> showGtnhVillages;
	public final Setting<Boolean> showGtnhMineshafts;
	public final Setting<Boolean> showGtnhLootGamesDungeons;
	public final Setting<Boolean> showGtnhTinkersSlimeIslands;
	public final Setting<Boolean> showGtnhVanillaSpawnerDungeons;
	public final Setting<Boolean> showGtnhThaumcraftAuraNodes;
	public final Setting<Boolean> showGtnhThaumcraftEldritchAltars;
	public final Setting<Boolean> showGtnhAe2Meteorites;
	public final Setting<Boolean> showGtnhWorldSpawn;
	public final Setting<Boolean> showGtnhTinkersNetherSlimeIslands;
	public final Setting<Boolean> showGtnhAutomagyNetherSpires;
	public final Setting<Boolean> showGtnhHeeBiomeIslands;
	public final Setting<Boolean> showGtnhHeeDungeonTowers;
	public final Setting<Boolean> showGtnhDraconicChaosIslands;
	public final Setting<Boolean> showGtnhMoonDungeons;
	public final Setting<Boolean> showGtnhMoonVillages;
	private final Map<GtnhEndStructureType, Setting<Boolean>>
			showGtnhEndStructures;
	private final Map<GtnhTwilightForestFeatureType, Setting<Boolean>>
			showGtnhTwilightForestFeatures;
	private final Map<GtnhSpaceStructureType, Setting<Boolean>>
			showGtnhSpaceStructures;

	public final Setting<Boolean> smoothScrolling;
	public final Setting<Boolean> fragmentFading;
	public final Setting<Boolean> maxZoom;
	public final Setting<Boolean> showFPS;
	public final Setting<Boolean> showScale;
	public final Setting<Boolean> showDebug;
	public final Setting<Boolean> useHybridScaling;
	public final Setting<Integer> threads;
	public final Setting<AmidstLookAndFeel> lookAndFeel;

	public final Setting<String> lastProfile;
	public final Setting<String> worldType;
	
	public final Setting<String> lastBiomeExportPath;
	public final Setting<String> lastScreenshotPath;

	/**
	 * This is not persisted.
	 */
	public final BiomeProfileSelection biomeProfileSelection;

	@CalledOnlyBy(AmidstThread.EDT)
	public AmidstSettings(Preferences preferences) {
        language = Setting.createEnum(preferences,"language",amidst.i18n.Language.systemDefault());
		// @formatter:off
		dimension                  = Setting.createDimension(preferences, "dimension",            Dimension.OVERWORLD);
		showGrid                   = Setting.createBoolean(  preferences, "grid",                 false);
		showSlimeChunks            = Setting.createBoolean(  preferences, "slimeChunks",          false);
		showSpawn                  = Setting.createBoolean(  preferences, "spawnIcon",            true);
		showStrongholds            = Setting.createBoolean(  preferences, "strongholdIcons",      true);
		showPlayers                = Setting.createBoolean(  preferences, "playerIcons",          true);
		showVillages               = Setting.createBoolean(  preferences, "villageIcons",         true);
		showTemples                = Setting.createBoolean(  preferences, "templeIcons",          true);
		showMineshafts             = Setting.createBoolean(  preferences, "mineshaftIcons",       false);
		showOceanMonuments         = Setting.createBoolean(  preferences, "oceanMonumentIcons",   true);
		showWoodlandMansions       = Setting.createBoolean(  preferences, "woodlandMansionIcons", true);
		showOceanFeatures          = Setting.createBoolean(  preferences, "oceanFeaturesIcons",   true);
		showNetherFortresses       = Setting.createBoolean(  preferences, "netherFortressIcons",  false);
		showEndCities              = Setting.createBoolean(  preferences, "endCityIcons",         false);
		showGtnhRoguelikeDesert    = Setting.createBoolean(  preferences, "gtnhRoguelikeDesert",  true);
		showGtnhRoguelikeForest    = Setting.createBoolean(  preferences, "gtnhRoguelikeForest",  true);
		showGtnhRoguelikeIce       = Setting.createBoolean(  preferences, "gtnhRoguelikeIce",     true);
		showGtnhRoguelikeJungle    = Setting.createBoolean(  preferences, "gtnhRoguelikeJungle",  true);
		showGtnhRoguelikeMesa      = Setting.createBoolean(  preferences, "gtnhRoguelikeMesa",    true);
		showGtnhRoguelikeMountain  = Setting.createBoolean(  preferences, "gtnhRoguelikeMountain",true);
		showGtnhRoguelikePlains    = Setting.createBoolean(  preferences, "gtnhRoguelikePlains",  true);
		showGtnhRoguelikeSwamp     = Setting.createBoolean(  preferences, "gtnhRoguelikeSwamp",   true);
		showGtnhStrongholds        = Setting.createBoolean(  preferences, "gtnhStrongholdIcons",  true);
		showGtnhVillages           = Setting.createBoolean(  preferences, "gtnhVillageIcons",     true);
		showGtnhMineshafts         = Setting.createBoolean(  preferences, "gtnhMineshaftIcons",   false);
		showGtnhLootGamesDungeons  = Setting.createBoolean(  preferences, "gtnhLootGamesDungeons",true);
		showGtnhTinkersSlimeIslands= Setting.createBoolean(  preferences, "gtnhTinkersSlimeIslands", true);
		showGtnhVanillaSpawnerDungeons= Setting.createBoolean(preferences, "gtnhVanillaSpawnerDungeons", false);
		showGtnhThaumcraftAuraNodes= Setting.createBoolean(  preferences, "gtnhThaumcraftAuraNodes", true);
		showGtnhThaumcraftEldritchAltars= Setting.createBoolean(preferences, "gtnhThaumcraftEldritchAltars", true);
		showGtnhAe2Meteorites      = Setting.createBoolean(  preferences, "gtnhAe2Meteorites", true);
		showGtnhWorldSpawn         = Setting.createBoolean(  preferences, "gtnhWorldSpawn", true);
		showGtnhTinkersNetherSlimeIslands= Setting.createBoolean(preferences, "gtnhTinkersNetherSlimeIslands", true);
		showGtnhAutomagyNetherSpires= Setting.createBoolean( preferences, "gtnhAutomagyNetherSpires", true);
		showGtnhHeeBiomeIslands     = Setting.createBoolean( preferences, "gtnhHeeBiomeIslands", true);
		showGtnhHeeDungeonTowers    = Setting.createBoolean( preferences, "gtnhHeeDungeonTowers", true);
		showGtnhDraconicChaosIslands= Setting.createBoolean( preferences, "gtnhDraconicChaosIslands", true);
		showGtnhMoonDungeons        = Setting.createBoolean( preferences, "gtnhMoonDungeons", true);
		showGtnhMoonVillages        = Setting.createBoolean( preferences, "gtnhMoonVillages", true);
		EnumMap<GtnhEndStructureType, Setting<Boolean>> endSettings =
				new EnumMap<>(GtnhEndStructureType.class);
		endSettings.put(GtnhEndStructureType.HEE_BIOME_ISLAND, showGtnhHeeBiomeIslands);
		endSettings.put(GtnhEndStructureType.HEE_DUNGEON_TOWER, showGtnhHeeDungeonTowers);
		endSettings.put(
				GtnhEndStructureType.DRACONIC_CHAOS_ISLAND,
				showGtnhDraconicChaosIslands);
		for (GtnhEndStructureType type : GtnhEndStructureType.values()) {
			endSettings.computeIfAbsent(
					type,
					ignored -> Setting.createBoolean(
							preferences,
							type.getPreferenceKey(),
							true));
		}
		showGtnhEndStructures = Collections.unmodifiableMap(endSettings);
		EnumMap<GtnhTwilightForestFeatureType, Setting<Boolean>> twilightSettings =
				new EnumMap<>(GtnhTwilightForestFeatureType.class);
		for (GtnhTwilightForestFeatureType type : GtnhTwilightForestFeatureType.values()) {
			twilightSettings.put(
					type,
					Setting.createBoolean(preferences, type.getPreferenceKey(), true));
		}
		showGtnhTwilightForestFeatures = Collections.unmodifiableMap(twilightSettings);
		EnumMap<GtnhSpaceStructureType, Setting<Boolean>> spaceSettings =
				new EnumMap<>(GtnhSpaceStructureType.class);
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			spaceSettings.put(
					type,
					Setting.createBoolean(preferences, type.getPreferenceKey(), true));
		}
		showGtnhSpaceStructures = Collections.unmodifiableMap(spaceSettings);

		smoothScrolling            = Setting.createBoolean(  preferences, "mapFlicking",          true);
		fragmentFading             = Setting.createBoolean(  preferences, "mapFading",            true);
		maxZoom                    = Setting.createBoolean(  preferences, "maxZoom",              true);
		showFPS                    = Setting.createBoolean(  preferences, "showFPS",              true);
		showScale                  = Setting.createBoolean(  preferences, "showScale",            true);
		showDebug                  = Setting.createBoolean(  preferences, "showDebug",            false);
		useHybridScaling           = Setting.createBoolean(  preferences, "useHybridScaling",     true);
		threads                    = Setting.createInteger(  preferences, "threads",              (Runtime.getRuntime().availableProcessors() / 2) + 1);
		lookAndFeel                = Setting.createEnum(     preferences, "lookAndFeel",          AmidstLookAndFeel.DEFAULT);

		lastProfile                = Setting.createString(   preferences, "profile",              "");
		worldType                  = Setting.createString(   preferences, "worldType",            WorldType.PROMPT_EACH_TIME);
		
		lastBiomeExportPath        = Setting.createString(   preferences, "lastBiomeExportPath",  "");
		lastScreenshotPath         = Setting.createString(   preferences, "lastScreenshotPath",   "");
		
		biomeProfileSelection = new BiomeProfileSelection(BiomeProfile.getDefaultProfile());
		// @formatter:on
	}

	public Setting<Boolean> getShowGtnhRoguelike(GtnhRoguelikeDungeonType type) {
		return switch (type) {
			case DESERT -> showGtnhRoguelikeDesert;
			case FOREST -> showGtnhRoguelikeForest;
			case ICE -> showGtnhRoguelikeIce;
			case JUNGLE -> showGtnhRoguelikeJungle;
			case MESA -> showGtnhRoguelikeMesa;
			case MOUNTAIN -> showGtnhRoguelikeMountain;
			case PLAINS -> showGtnhRoguelikePlains;
			case SWAMP -> showGtnhRoguelikeSwamp;
		};
	}

	public Setting<Boolean> getShowGtnhTwilightForestFeature(
			GtnhTwilightForestFeatureType type) {
		return showGtnhTwilightForestFeatures.get(type);
	}

	public Setting<Boolean> getShowGtnhSpaceStructure(GtnhSpaceStructureType type) {
		return showGtnhSpaceStructures.get(type);
	}

	public Setting<Boolean> getShowGtnhEndStructure(GtnhEndStructureType type) {
		return showGtnhEndStructures.get(type);
	}
}
