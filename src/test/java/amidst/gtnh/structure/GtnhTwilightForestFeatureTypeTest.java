package amidst.gtnh.structure;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

import amidst.fragment.layer.LayerIds;

public class GtnhTwilightForestFeatureTypeTest {
	@Test
	public void resolvesMagicMapFeatureWireNames() {
		assertEquals(
				GtnhTwilightForestFeatureType.NAGA_COURTYARD,
				GtnhTwilightForestFeatureType.fromWireName("NAGA_COURTYARD"));
		assertEquals(
				"twilight_feature_05.png",
				GtnhTwilightForestFeatureType.NAGA_COURTYARD.getIconFile());
	}

	@Test
	public void eachFeatureLoadsItsOwnMagicMapMarker() {
		for (GtnhTwilightForestFeatureType type : GtnhTwilightForestFeatureType.values()) {
			assertNotNull(type.getIcon());
		}
		assertNotSame(
				GtnhTwilightForestFeatureType.NAGA_COURTYARD.getIcon(),
				GtnhTwilightForestFeatureType.LICH_TOWER.getIcon());
	}

	@Test
	public void eachFeatureHasItsOwnStableLayerAndPreference() {
		Set<Integer> layerIds = new HashSet<>();
		Set<String> preferenceKeys = new HashSet<>();
		for (GtnhTwilightForestFeatureType type :
				GtnhTwilightForestFeatureType.values()) {
			assertTrue(layerIds.add(type.getLayerId()));
			assertTrue(preferenceKeys.add(type.getPreferenceKey()));
		}
		assertEquals(
				LayerIds.GTNH_TWILIGHT_FOREST_FEATURE_FIRST,
				GtnhTwilightForestFeatureType.values()[0].getLayerId());
		assertEquals(
				LayerIds.GTNH_TWILIGHT_FOREST_FEATURE_LAST,
				GtnhTwilightForestFeatureType.values()[19].getLayerId());
	}
}
