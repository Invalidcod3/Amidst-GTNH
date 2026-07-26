package amidst.gtnh.structure;

import java.util.Locale;

import amidst.mojangapi.world.icon.WorldIconImage;
import amidst.mojangapi.world.icon.type.DefaultWorldIconTypes;

public enum GtnhRoguelikeDungeonType {
	DESERT("Desert", "Pyramid entrance", "desert.png", DefaultWorldIconTypes.DESERT),
	FOREST("Forest", "House entrance", "woodland_mansion.png", DefaultWorldIconTypes.WOODLAND_MANSION),
	ICE("Ice", "Bunker entrance", "igloo.png", DefaultWorldIconTypes.IGLOO),
	JUNGLE("Jungle", "Jungle entrance", "jungle.png", DefaultWorldIconTypes.JUNGLE),
	MESA("Mesa", "Etho entrance", "desert.png", DefaultWorldIconTypes.DESERT),
	MOUNTAIN("Mountain", "Eniko entrance", "stronghold.png", DefaultWorldIconTypes.STRONGHOLD),
	PLAINS("Plains", "Rogue tower entrance", "village.png", DefaultWorldIconTypes.VILLAGE),
	SWAMP("Swamp", "Witch-house entrance", "witch.png", DefaultWorldIconTypes.WITCH);

	public static GtnhRoguelikeDungeonType fromWireName(String value) {
		if (value == null) {
			return null;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private final String displayName;
	private final String entranceName;
	private final String menuIcon;
	private final DefaultWorldIconTypes iconType;

	GtnhRoguelikeDungeonType(
			String displayName,
			String entranceName,
			String menuIcon,
			DefaultWorldIconTypes iconType) {
		this.displayName = displayName;
		this.entranceName = entranceName;
		this.menuIcon = menuIcon;
		this.iconType = iconType;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String getEntranceName() {
		return entranceName;
	}

	public String getMenuIcon() {
		return menuIcon;
	}

	public WorldIconImage getIcon() {
		return iconType.getImage();
	}

	public String getIconLabel() {
		return "Possible Roguelike Dungeon — " + displayName + " (" + entranceName + ")";
	}
}
