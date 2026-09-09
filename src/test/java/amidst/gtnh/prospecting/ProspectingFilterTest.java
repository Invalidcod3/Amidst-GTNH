package amidst.gtnh.prospecting;

import static org.junit.Assert.*;
import java.util.List;
import java.text.ParseException;
import javax.swing.*;
import org.junit.Test;
import amidst.AmidstSettings;
import amidst.gtnh.prospecting.ProspectingData.*;

public class ProspectingFilterTest {
    private static FilterOption option(String id, String name) {
        FilterOption o = new FilterOption(); o.id = id; o.name = name; return o;
    }
    @Test public void dropdownUsesDimensionRegistryAndDisambiguatesNamesWithoutEditing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AmidstSettings settings = new AmidstSettings(new ProspectingOverlayTest.MemoryPreferences());
            DimensionInfo info = new DimensionInfo();
            info.oreOptions = List.of(option("mix.b", "Copper"), option("mix.a", "Copper"), option("mix.a", "Duplicate"));
            info.fluidOptions = List.of(option("natural.gas", "Natural Gas"));
            settings.prospectingFilter.set("mix.b");
            var ores = new ProspectingFilterPanel(info, MarkerMode.ORES, settings);
            assertFalse(ores.selection.isEditable());
            assertEquals(3, ores.selection.getItemCount());
            assertEquals("Copper [mix.b]", ores.selection.getSelectedItem().toString());
            ores.selection.setSelectedIndex(1);
            assertEquals("Constructing/editing a cancelled panel must not change the map", "mix.b", settings.prospectingFilter.get());
            try { ores.apply(settings); } catch (ParseException e) { throw new AssertionError(e); }
            assertEquals("mix.a", settings.prospectingFilter.get());
            assertNull(ores.minimum);
            var fluid = new ProspectingFilterPanel(info, MarkerMode.FLUID, settings);
            assertEquals(2, fluid.selection.getItemCount());
            assertEquals(0, fluid.selection.getSelectedIndex());
            fluid.selection.setSelectedIndex(1); fluid.minimum.setValue(150);
            try { fluid.apply(settings); } catch (ParseException e) { throw new AssertionError(e); }
            assertEquals("natural.gas", settings.prospectingFilter.get());
            assertEquals(Integer.valueOf(150), settings.prospectingMinimumFluid.get());
            ((JSpinner.DefaultEditor) fluid.minimum.getEditor()).getTextField().setText("-1");
            fluid.selection.setSelectedIndex(0);
            assertThrows(ParseException.class, () -> fluid.apply(settings));
            assertEquals("Invalid input must not partly apply the selection", "natural.gas", settings.prospectingFilter.get());
            assertEquals(Integer.valueOf(150), settings.prospectingMinimumFluid.get());
        });
    }
    @Test public void exactIdsAndInclusiveThresholdsHideOnlyLowChunksIncludingNegativeCoordinates() {
        Deposit d = new Deposit(); d.id = "oil"; d.name = "Heavy Oil"; d.materials = "Oil";
        d.x = -128; d.z = -256; d.amounts = new int[64];
        d.amounts[0] = 99; d.amounts[1] = 100; d.amounts[8] = 101;
        assertTrue(ProspectingOverlay.matches(d, "oil", 100));
        assertFalse(ProspectingOverlay.matches(d, "Heavy Oil", 0));
        assertFalse(ProspectingOverlay.matches(d, "oi", 0));
        assertFalse(ProspectingOverlay.matches(d, "oil", 102));
        assertFalse(ProspectingOverlay.visibleAt(d, -120, -248, 100));
        assertTrue(ProspectingOverlay.visibleAt(d, -120, -232, 100));
        assertTrue(ProspectingOverlay.visibleAt(d, -104, -248, 100));
        assertFalse(ProspectingOverlay.visibleAt(d, -129, -256, 0));
        assertFalse(ProspectingOverlay.visibleAt(d, 0, -256, 0));
        assertTrue(ProspectingOverlay.matches(d, "", 0));
        d.amounts = null;
        assertTrue("Fluid threshold must not hide ore veins", ProspectingOverlay.matches(d, "oil", 999));
    }
}
