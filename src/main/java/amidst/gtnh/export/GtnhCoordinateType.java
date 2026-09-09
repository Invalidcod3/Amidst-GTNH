package amidst.gtnh.export;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import amidst.gtnh.structure.GtnhEndStructureType;
import amidst.gtnh.structure.GtnhMoonStructureType;
import amidst.gtnh.structure.GtnhNetherStructureType;
import amidst.gtnh.structure.GtnhOverworldStructureType;
import amidst.gtnh.structure.GtnhRoguelikeDungeonType;
import amidst.gtnh.structure.GtnhSpaceStructureType;
import amidst.gtnh.structure.GtnhTwilightForestFeatureType;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.icon.producer.WorldIconProducer;

/**
 * A menu-facing catalogue of the coordinate layers available in the GTNH
 * build. It deliberately resolves the same producers used by the map so an
 * exported prediction cannot drift from the marker shown on screen.
 */
public final class GtnhCoordinateType {
	private enum Family {
        PROSPECTING,
		ROGUELIKE,
		OVERWORLD,
		NETHER,
		NETHER_FORTRESS,
		END,
		MOON,
		SPACE,
		TWILIGHT
	}

	private static final List<GtnhCoordinateType> ALL = createCatalogue();

	private final Dimension dimension;
	private final String displayName;
	private final Family family;
	private final String enumName;
	private final Color color;

	private GtnhCoordinateType(
			Dimension dimension,
			String displayName,
			Family family,
			String enumName) {
		this.dimension = dimension;
		this.displayName = displayName;
		this.family = family;
		this.enumName = enumName;
		float hue = (enumName.hashCode() & 0x7fffffff) / (float) Integer.MAX_VALUE;
		this.color = Color.getHSBColor(hue, 0.72F, 1.0F);
	}

	public static List<GtnhCoordinateType> all() {
		return ALL;
	}

	public static List<GtnhCoordinateType> forDimension(Dimension dimension) {
		return ALL.stream().filter(type -> type.dimension == dimension).toList();
	}

    public boolean isAvailable(List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> catalog) {
        return family != Family.PROSPECTING || catalog.stream().anyMatch(info -> dimension.prospectingKey().equals(info.key)
                && (enumName.equals("ORES") ? info.ores : info.fluids));
    }
    public boolean isProspecting() { return family == Family.PROSPECTING; }
    public String prospectingMode() { return enumName; }

    public static boolean hasContent(Dimension dimension, List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> catalog) {
        return forDimension(dimension).stream().anyMatch(type -> type.isAvailable(catalog));
    }

	public Dimension getDimension() {
		return dimension;
	}

	public String getDisplayName() {
		return displayName;
	}

	public Color getColor() {
		return color;
	}

	public WorldIconProducer<Void> getProducer(World world) {
		return switch (family) {
            case PROSPECTING -> new WorldIconProducer<Void>() {
                public void produce(amidst.mojangapi.world.coordinates.CoordinatesInWorld corner,
                        java.util.function.Consumer<amidst.mojangapi.world.icon.WorldIcon> consumer, Void ignored) {
                    try {
                        var info = world.prospectingCatalog().stream().filter(d -> d.key.equals(dimension.prospectingKey())).findFirst().orElse(null);
                        if (info == null || !(enumName.equals("ORES") ? info.ores : info.fluids)) return;
                        var tile = world.prospect(dimension, Math.toIntExact(corner.getX()), Math.toIntExact(corner.getY()), 512, 512, enumName);
                        var image = amidst.mojangapi.world.icon.WorldIconImage.from(amidst.ResourceLoader.getImage(
                                "/amidst/gui/main/icon/" + (enumName.equals("ORES") ? "prospecting_ores.png" : "prospecting_fluid.png")));
                        for (var deposit : tile.deposits) consumer.accept(new amidst.mojangapi.world.icon.WorldIcon(
                                amidst.mojangapi.world.coordinates.CoordinatesInWorld.from(deposit.x, deposit.z),
                                deposit.name + " [" + deposit.source + "]", image, dimension, true));
                    } catch (amidst.mojangapi.minecraftinterface.MinecraftInterfaceException e) {
                        throw new IllegalStateException("Prospecting coordinate query failed", e);
                    }
                }
            };
			case ROGUELIKE -> world.getGtnhRoguelikeDungeonProducer(
					GtnhRoguelikeDungeonType.valueOf(enumName));
			case OVERWORLD -> world.getGtnhOverworldStructureProducer(
					GtnhOverworldStructureType.valueOf(enumName));
			case NETHER -> world.getGtnhNetherStructureProducer(
					GtnhNetherStructureType.valueOf(enumName));
			case NETHER_FORTRESS -> world.getGtnhNetherFortressProducer();
			case END -> world.getGtnhEndStructureProducer(
					GtnhEndStructureType.valueOf(enumName));
			case MOON -> world.getGtnhMoonStructureProducer(
					GtnhMoonStructureType.valueOf(enumName));
			case SPACE -> world.getGtnhSpaceStructureProducer(
					GtnhSpaceStructureType.valueOf(enumName));
			case TWILIGHT -> world.getGtnhTwilightForestFeatureProducer(
					GtnhTwilightForestFeatureType.valueOf(enumName));
		};
	}

	@Override
	public String toString() {
		return amidst.i18n.I18n.text(displayName);
	}

	private static List<GtnhCoordinateType> createCatalogue() {
		List<GtnhCoordinateType> result = new ArrayList<>();
        for (Dimension dimension : Dimension.values()) {
            result.add(new GtnhCoordinateType(dimension, "Ore veins", Family.PROSPECTING, "ORES"));
            result.add(new GtnhCoordinateType(dimension, "Underground fluids", Family.PROSPECTING, "FLUID"));
        }
		for (GtnhOverworldStructureType type : GtnhOverworldStructureType.values()) {
			result.add(new GtnhCoordinateType(
					Dimension.OVERWORLD,
					type.getDisplayName(),
					Family.OVERWORLD,
					type.name()));
		}
		for (GtnhRoguelikeDungeonType type : GtnhRoguelikeDungeonType.values()) {
			result.add(new GtnhCoordinateType(
					Dimension.OVERWORLD,
					"Roguelike Dungeon - " + type.getDisplayName(),
					Family.ROGUELIKE,
					type.name()));
		}
		result.add(new GtnhCoordinateType(
				Dimension.NETHER,
				"Nether Fortress",
				Family.NETHER_FORTRESS,
				"NETHER_FORTRESS"));
		for (GtnhNetherStructureType type : GtnhNetherStructureType.values()) {
			result.add(new GtnhCoordinateType(
					Dimension.NETHER,
					type.getDisplayName(),
					Family.NETHER,
					type.name()));
		}
		for (GtnhEndStructureType type : GtnhEndStructureType.values()) {
			result.add(new GtnhCoordinateType(
					Dimension.END,
					type.getDisplayName(),
					Family.END,
					type.name()));
		}
		for (GtnhMoonStructureType type : GtnhMoonStructureType.values()) {
			result.add(new GtnhCoordinateType(
					Dimension.MOON,
					type.getDisplayName(),
					Family.MOON,
					type.name()));
		}
		for (GtnhSpaceStructureType type : GtnhSpaceStructureType.values()) {
			result.add(new GtnhCoordinateType(
					type.getDimension(),
					type.getDisplayName(),
					Family.SPACE,
					type.name()));
		}
		for (GtnhTwilightForestFeatureType type : GtnhTwilightForestFeatureType.values()) {
			result.add(new GtnhCoordinateType(
					Dimension.TWILIGHT_FOREST,
					type.getDisplayName(),
					Family.TWILIGHT,
					type.name()));
		}
		return Collections.unmodifiableList(result);
	}
}
