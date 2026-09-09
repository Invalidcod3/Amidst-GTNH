package amidst.gui.main.viewer.widget;

import java.awt.Point;
import java.util.Arrays;
import java.util.List;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.Fragment;
import amidst.fragment.FragmentGraph;
import amidst.gtnh.worker.GtnhCursorBiomeLookup;
import amidst.gui.main.viewer.FragmentGraphToScreenTranslator;
import amidst.logging.AmidstLogger;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.biome.Biome;
import amidst.mojangapi.world.biome.BiomeList;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.mojangapi.world.versionfeatures.DefaultBiomes;
import amidst.settings.Setting;

@NotThreadSafe
public class CursorInformationWidget extends TextWidget {
	private static final String UNKNOWN_BIOME_NAME = "Unknown";

	private final FragmentGraph graph;
	private final FragmentGraphToScreenTranslator translator;
	private final Setting<Dimension> dimensionSetting;
	private final BiomeList biomeList;
	private final GtnhCursorBiomeLookup exactLookup;

	@CalledOnlyBy(AmidstThread.EDT)
	public CursorInformationWidget(
			CornerAnchorPoint anchor,
			FragmentGraph graph,
			FragmentGraphToScreenTranslator translator,
			Setting<Dimension> dimensionSetting,
			BiomeList biomeList) {
		this(anchor, graph, translator, dimensionSetting, biomeList, null);
	}

	public CursorInformationWidget(
			CornerAnchorPoint anchor, FragmentGraph graph, FragmentGraphToScreenTranslator translator,
			Setting<Dimension> dimensionSetting, BiomeList biomeList, GtnhCursorBiomeLookup exactLookup) {
		super(anchor);
		this.graph = graph;
		this.translator = translator;
		this.dimensionSetting = dimensionSetting;
		this.biomeList = biomeList;
		this.exactLookup = exactLookup;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	@Override
	protected List<String> updateTextLines() {
		Point mousePosition = getMousePosition();
		if (mousePosition != null) {
			CoordinatesInWorld coordinates = translator.screenToWorld(mousePosition);
			String biomeName = getBiomeNameAt(coordinates);
			if (exactLookup != null && dimensionSetting.get() == Dimension.OVERWORLD) {
				try {
					String exact = exactLookup.poll(Dimension.OVERWORLD,
							Math.toIntExact(coordinates.getX()), Math.toIntExact(coordinates.getY()));
					if (exact != null) {
						return Arrays.asList(amidst.i18n.I18n.text("At block: ") + exact + " " + coordinates,
								amidst.i18n.I18n.text("Map sample: ") + biomeName + amidst.i18n.I18n.text(" (4x4 blocks)"));
					}
				} catch (ArithmeticException outsideWorld) {
					// Panning past the integer coordinate range cannot be queried.
				}
				return Arrays.asList(amidst.i18n.I18n.text("Map sample: ") + biomeName + " " + coordinates + amidst.i18n.I18n.text(" (4x4 blocks)"));
			}
			return Arrays.asList(biomeName + " " + coordinates.toString());
		} else {
			return null;
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private String getBiomeNameAt(CoordinatesInWorld coordinates) {
		Dimension dimension = dimensionSetting.get();
        if (dimension.isProspectingOnly()) return amidst.i18n.I18n.text("Prospecting");
		if (!dimension.equals(Dimension.END)) {
			return getLoadedBiomeNameAt(coordinates);
		} else if (dimension.equals(Dimension.END)) {
			return biomeList.getByIdOrNull(DefaultBiomes.theEnd).getName();
		} else {
			AmidstLogger.warn("unsupported dimension");
			return UNKNOWN_BIOME_NAME;
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private String getLoadedBiomeNameAt(CoordinatesInWorld coordinates) {
		Fragment fragment = graph.getFragmentAt(coordinates);
		if (fragment != null
				&& fragment.getState().equals(Fragment.State.LOADED)
				&& dimensionSetting.get().equals(fragment.getLoadedDimension())) {
			long x = coordinates.getXRelativeToFragmentAs(Resolution.QUARTER);
			long y = coordinates.getYRelativeToFragmentAs(Resolution.QUARTER);
			short biome = fragment.getBiomeDataAt((int) x, (int) y);
			Biome b = biomeList.getByIdOrNull(biome);
			if (b != null) {
				return b.getName();
			} else if (biome != -1) {
				return UNKNOWN_BIOME_NAME + " (ID: " + biome + ")";
			}
		}
		return UNKNOWN_BIOME_NAME;
	}
}
