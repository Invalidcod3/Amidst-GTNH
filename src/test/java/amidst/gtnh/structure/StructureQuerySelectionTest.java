package amidst.gtnh.structure;

import static org.junit.Assert.*;

import amidst.fragment.layer.LayerDeclaration;
import amidst.fragment.layer.LayerIds;
import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class StructureQuerySelectionTest {
    @Test
    public void visibleLayersAndNestedCancellationRestoreTheCallingScope() {
        var enabled = Setting.createDummy(false);
        var portal =
                new LayerDeclaration(
                        LayerIds.GTNH_RWG2_TWILIGHT_PORTAL,
                        Dimension.OVERWORLD,
                        false,
                        true,
                        enabled);
        var game =
                new LayerDeclaration(
                        LayerIds.GTNH_RWG2_LAVA_CAVE_GAME,
                        Dimension.OVERWORLD,
                        false,
                        true,
                        Setting.createImmutable(true));
        portal.update(Dimension.OVERWORLD);
        game.update(Dimension.OVERWORLD);
        assertEquals(
                Set.of("RWG2_LAVA_CAVE_GAME"),
                StructureQuerySelection.visibleTypes(List.of(portal, game)));
        enabled.set(true);
        portal.update(Dimension.OVERWORLD);
        try (var scope =
                StructureQuerySelection.open(
                        StructureQuerySelection.visibleTypes(List.of(portal, game)))) {
            assertEquals(
                    Set.of("RWG2_LAVA_CAVE_GAME", "RWG2_TWILIGHT_PORTAL"),
                    StructureQuerySelection.current());
            assertThrows(
                    java.util.concurrent.CancellationException.class,
                    () -> {
                        try (var nested = StructureQuerySelection.open(Set.of())) {
                            throw new java.util.concurrent.CancellationException();
                        }
                    });
            assertTrue(StructureQuerySelection.current().contains("RWG2_TWILIGHT_PORTAL"));
        }
        assertNull(StructureQuerySelection.current());
    }
}
