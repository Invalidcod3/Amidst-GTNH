package amidst.gtnh.vegetation;

import static org.junit.Assert.*;

import amidst.gtnh.worker.GtnhBiomeDescriptor;
import amidst.gui.main.viewer.BiomeSelection;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.WorldType;

import org.junit.Test;

import java.util.List;
import java.util.Set;

public class VegetationCatalogTest {
    private final VegetationCatalog catalog = VegetationCatalog.get();

    private GtnhBiomeDescriptor biome(String name, String... tags) {
        return new GtnhBiomeDescriptor(500, name, 0, 0.8f, 0.6f, 0, 0, List.of(tags));
    }

    private boolean matches(
            String id, GtnhBiomeDescriptor biome, WorldType type, Dimension dimension) {
        return catalog.matches(
                catalog.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(),
                biome,
                type,
                dimension);
    }

    private boolean matches(String id, GtnhBiomeDescriptor biome) {
        return matches(id, biome, WorldType.RWG2_SMOOTH, Dimension.OVERWORLD);
    }

    @Test
    public void pamUsesTagsAndPreservesBooleanPrecedence() {
        assertFalse(matches("harvestcraft:watergarden", biome("Temperate River", "RIVER", "COLD")));
        assertTrue(matches("harvestcraft:watergarden", biome("Wet River", "RIVER", "HOT", "WET")));
        assertFalse(matches("harvestcraft:grassgarden", biome("Cold Plains", "PLAINS", "COLD")));
        assertTrue(matches("harvestcraft:herbgarden", biome("Wetland", "WET", "COLD")));
        assertTrue(
                matches(
                        "harvestcraft:mushroomgarden",
                        biome("Wetland", "WET", "MOUNTAIN", "COLD")));
        assertFalse(matches("harvestcraft:mushroomgarden", biome("Wetland", "WET", "DEAD")));
    }

    @Test
    public void fruitExceptionsFollowTheShippedGenerator() {
        assertFalse(matches("harvestcraft:apple", biome("Cold Forest", "FOREST", "COLD")));
        assertTrue(matches("harvestcraft:apple", biome("Meadow", "FOREST", "PLAINS")));
        assertFalse(matches("harvestcraft:banana", biome("Hot Plains", "HOT", "PLAINS")));
        assertTrue(matches("harvestcraft:coconut", biome("Cold Ocean", "OCEAN", "COLD")));
        assertFalse(
                matches(
                        "harvestcraft:maple",
                        biome("Cold Forest", "FOREST", "CONIFEROUS", "COLD")));
        assertTrue(matches("harvestcraft:maple", biome("Wetland", "WET", "CONIFEROUS")));
        assertFalse(matches("harvestcraft:olive", biome("Meadow", "FOREST")));
    }

    @Test
    public void etFuturumUsesItsOwnTagExclusions() {
        assertTrue(matches("etfuturum:cornflower", biome("Cold Plains", "PLAINS", "COLD")));
        assertFalse(matches("etfuturum:cornflower", biome("Cold Plains", "PLAINS", "SNOWY")));
        assertFalse(matches("etfuturum:cornflower", biome("Hot Plains", "PLAINS", "SAVANNA")));
        assertTrue(matches("etfuturum:lily_of_the_valley", biome("Meadow", "FOREST")));
        assertFalse(
                matches("etfuturum:lily_of_the_valley", biome("Cold Forest", "FOREST", "COLD")));
        assertTrue(matches("etfuturum:cherry", biome("Meadow", "MOUNTAIN")));
        assertTrue(matches("etfuturum:pink_petals", biome("Meadow", "MOUNTAIN")));
        for (String excluded :
                List.of(
                        "SNOWY",
                        "HOT",
                        "SANDY",
                        "MESA",
                        "SPARSE",
                        "JUNGLE",
                        "DEAD",
                        "SPOOKY",
                        "WASTELAND"))
            assertFalse(
                    excluded, matches("etfuturum:cherry", biome("Meadow", "MOUNTAIN", excluded)));
    }

    @Test
    public void generatorPoolsDoNotLeakBetweenAlphaAndRwg2() {
        var flowers = biome("Flower Field", "PLAINS");
        assertTrue(
                matches("minecraft:flower:4", flowers, WorldType.RWG2_SMOOTH, Dimension.OVERWORLD));
        assertFalse(
                matches("minecraft:flower:4", flowers, WorldType.RWG_ALPHA, Dimension.OVERWORLD));
        var mystic = biome("Mystic Grove", "MAGICAL", "FOREST");
        assertTrue(matches("bop:flowers:3", mystic, WorldType.RWG_ALPHA, Dimension.OVERWORLD));
        assertFalse(matches("bop:flowers:3", mystic, WorldType.RWG2_SMOOTH, Dimension.OVERWORLD));
        // Both integrations call the meadow decorator; arbitrary biome IDs remain valid.
        for (var type : List.of(WorldType.RWG_ALPHA, WorldType.RWG2_SMOOTH))
            assertTrue(matches("bop:flowers:4", biome("Meadow"), type, Dimension.OVERWORLD));
    }

    @Test
    public void hangingBopFruitAndNetherFlowersHaveSeparateRules() {
        assertTrue(matches("bop:appleLeaves:0", biome("Mountain")));
        assertTrue(matches("bop:persimmonLeaves:0", biome("Seasonal Forest")));
        assertTrue(matches("bop:fruit:peach", biome("Cherry Blossom Grove")));
        assertTrue(matches("bop:fruit:pear", biome("Boreal Forest")));
        assertFalse(matches("bop:fruit:pear", biome("Cold Forest")));
        assertTrue(
                matches(
                        "bop:flowers2:2",
                        biome("Hell", "NETHER"),
                        WorldType.RWG_ALPHA,
                        Dimension.NETHER));
        assertFalse(matches("bop:flowers2:2", biome("Hot Desert", "HOT")));
    }

    @Test
    public void dimensionsAndScopedIdsAreKeptSeparate() {
        var tf = biome("Twilight Forest", "TWILIGHT_FOREST", "FOREST");
        assertTrue(
                matches("harvestcraft:apple", tf, WorldType.RWG_ALPHA, Dimension.TWILIGHT_FOREST));
        assertFalse(matches("harvestcraft:apple", tf));
        var ross =
                new GtnhBiomeDescriptor(
                        11 * 65536 + 192, "Magical Forest", 0, 0, 0, 0, 0, List.of("MAGICAL"));
        assertTrue(
                matches(
                        "botania:mystical_flowers",
                        ross,
                        WorldType.RWG2_SMOOTH,
                        Dimension.ROSS_128B));
        assertFalse(matches("botania:mystical_flowers", biome("Magical Forest", "MAGICAL")));
        assertFalse(
                matches(
                        "botania:mystical_flowers",
                        new GtnhBiomeDescriptor(8 * 65536 + 192, "Magical Forest", 0, 0, 0, 0, 0),
                        WorldType.RWG2_SMOOTH,
                        Dimension.ROSS_128B));
        assertFalse(
                matches(
                        "harvestcraft:berrygarden",
                        biome("Hell", "NETHER", "FOREST"),
                        WorldType.RWG_ALPHA,
                        Dimension.NETHER));
    }

    @Test
    public void multiSelectionAndEmptyResultsDoNotSelectUnknownBiomes() {
        var meadow = biome("Meadow", "FOREST", "PLAINS");
        var desert =
                new GtnhBiomeDescriptor(
                        501, "Hot Desert", 0, 2, 0, 0, 0, List.of("SANDY", "HOT", "DRY"));
        var selected = Set.of("harvestcraft:apple", "harvestcraft:desertgarden");
        assertEquals(
                2,
                catalog.matching(
                                List.of(meadow, desert),
                                selected,
                                false,
                                WorldType.RWG_ALPHA,
                                Dimension.OVERWORLD)
                        .size());
        assertTrue(
                catalog.matching(
                                List.of(meadow, desert),
                                selected,
                                true,
                                WorldType.RWG_ALPHA,
                                Dimension.OVERWORLD)
                        .isEmpty());
        var selection = new BiomeSelection();
        selection.selectAll();
        selection.highlightOnly(List.of(501));
        assertTrue(selection.isHighlightMode());
        assertTrue(selection.isSelected(501));
        assertTrue(selection.isVisible(501));
        assertFalse(selection.isVisible(999));
        selection.highlightOnly(List.of(501)); // Reapplying selects, rather than toggles off.
        assertTrue(selection.isSelected(501));
        selection.toggle(999); // Vegetation does not restrict later manual selection.
        assertTrue(selection.isVisible(999));
        selection.selectAll();
        assertTrue(selection.isVisible(1000)); // All retains its original unrestricted behavior.
        selection.highlightOnly(List.of());
        assertFalse(selection.isVisible(501));
        assertFalse(selection.isVisible(1000));
        selection.clearHighlight();
        assertTrue(selection.isVisible(999));
    }

    @Test
    public void unrelatedVanillaOrSpaceBiomesAreNotGuessedFromNames() {
        assertFalse(matches("harvestcraft:apple", biome("Forest", "FOREST")));
        assertFalse(
                matches(
                        "harvestcraft:apple",
                        biome("Meadow", "FOREST"),
                        WorldType.DEFAULT,
                        Dimension.OVERWORLD));
        assertFalse(
                matches(
                        "natura:maple",
                        biome("Woodland", "FOREST"))); // Natura spells it Woodlands.
        assertTrue(matches("natura:redwood", biome("Meadow", "FOREST")));
    }
}
