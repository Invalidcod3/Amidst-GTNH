package amidst.gtnh.prospecting;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;
import amidst.gtnh.export.GtnhCoordinateType;
import amidst.gtnh.prospecting.ProspectingData.DimensionInfo;
import amidst.mojangapi.world.Dimension;

public class ProspectingCatalogTest {
    @Test public void keepsOnlyWorldsWithResourcesOrPoiAndOnlySupportedExportTypes() {
        DimensionInfo info = new DimensionInfo(); info.key = Dimension.TITAN.prospectingKey();
        assertFalse(GtnhCoordinateType.hasContent(Dimension.TITAN, List.of()));
        assertFalse(GtnhCoordinateType.hasContent(Dimension.TITAN, List.of(info)));
        info.fluids = true;
        assertTrue(GtnhCoordinateType.hasContent(Dimension.TITAN, List.of(info)));
        assertEquals(1, GtnhCoordinateType.forDimension(Dimension.TITAN).stream().filter(t -> t.isAvailable(List.of(info))).count());
        info.ores = true;
        assertEquals(2, GtnhCoordinateType.forDimension(Dimension.TITAN).stream().filter(t -> t.isAvailable(List.of(info))).count());
        assertTrue("POI worlds stay available without prospecting", GtnhCoordinateType.hasContent(Dimension.OVERWORLD, List.of()));
    }
}
