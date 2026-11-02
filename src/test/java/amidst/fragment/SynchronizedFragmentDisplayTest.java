package amidst.fragment;

import static org.junit.Assert.*;

import amidst.fragment.layer.*;
import amidst.mojangapi.minecraftinterface.*;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.oracle.BiomeDataOracle;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.function.Function;

public class SynchronizedFragmentDisplayTest {
    @Test
    public void refreshDoesNotRestartFadeButNewCoordinatesAndDimensionsDo() {
        Fragment fragment = create();
        var enabled = amidst.settings.Setting.createImmutable(true);
        var declaration = new LayerDeclaration(LayerIds.ALPHA, null, false, true, enabled);
        var alpha = new amidst.fragment.loader.AlphaInitializer(declaration, enabled);
        alpha.load(Dimension.OVERWORLD, fragment);
        assertEquals(0, fragment.getAlpha(), 0);
        stage(fragment, 300, 1, icon("old"));
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        fragment.setState(Fragment.State.LOADING);
        fragment.setAlpha(1);
        alpha.load(Dimension.OVERWORLD, fragment);
        assertEquals("Retained complete picture stays fully visible", 1, fragment.getAlpha(), 0);
        fragment.prepareForLoad(Dimension.NETHER);
        alpha.load(Dimension.NETHER, fragment);
        assertEquals(0, fragment.getAlpha(), 0);
        fragment.setAlpha(1);
        fragment.setCorner(CoordinatesInWorld.from(512, 0));
        fragment.prepareForLoad(Dimension.OVERWORLD);
        alpha.load(Dimension.OVERWORLD, fragment);
        assertEquals(0, fragment.getAlpha(), 0);
    }

    private static final int DATA = LayerIds.BIOME_DATA,
            IMAGE = LayerIds.BACKGROUND,
            ICON = LayerIds.SLIME;

    @Test
    public void wholeTilePublishesAtOnceAndPaintPinsOneGeneration() {
        Fragment fragment = create();
        WorldIcon oldIcon = icon("old");
        BufferedImage oldImage = stage(fragment, 300, 0xff335577, oldIcon);
        assertFalse(fragment.hasDisplayData(Dimension.OVERWORLD));
        fragment.beginDisplayFrame();
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        assertFalse(
                "An empty frame stays empty until the next paint",
                fragment.hasDisplayData(Dimension.OVERWORLD));
        fragment.endDisplayFrame();
        fragment.setState(Fragment.State.LOADED);
        assertSame(oldImage, fragment.getImage(IMAGE));
        assertEquals(300, fragment.getDisplayBiomeDataAt(0, 0));

        fragment.setState(Fragment.State.LOADING);
        fragment.invalidateLayerData(IMAGE);
        fragment.invalidateLayerData(ICON);
        populate(fragment, 329);
        BufferedImage nextImage = image(0xff778899);
        fragment.putImage(IMAGE, nextImage);
        fragment.markLayerComplete(IMAGE, 1);
        assertSame(oldImage, fragment.getImage(IMAGE));
        assertEquals(329, fragment.getBiomeDataAt(0, 0));
        assertEquals(
                "Cursor map sample must match the visible generation",
                300,
                fragment.getDisplayBiomeDataAt(0, 0));
        assertEquals(List.of(oldIcon), fragment.getWorldIcons(ICON));
        assertThrows(
                IllegalStateException.class,
                () -> fragment.setLoadedDimension(Dimension.OVERWORLD));

        WorldIcon nextIcon = icon("next");
        fragment.putWorldIcons(ICON, List.of(nextIcon));
        fragment.markLayerComplete(ICON, 1);
        fragment.beginDisplayFrame();
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        fragment.setState(Fragment.State.LOADED);
        assertSame(
                "Publication between background and icon draws cannot split a frame",
                oldImage,
                fragment.getImage(IMAGE));
        assertEquals(List.of(oldIcon), fragment.getWorldIcons(ICON));
        fragment.endDisplayFrame();
        assertSame(nextImage, fragment.getImage(IMAGE));
        assertEquals(List.of(nextIcon), fragment.getWorldIcons(ICON));
        assertEquals(329, fragment.getDisplayBiomeDataAt(0, 0));
    }

    @Test
    public void cancelledOrFailedPartialRefreshKeepsOldBundleAndReuseHidesIt() {
        Fragment fragment = create();
        WorldIcon oldIcon = icon("old");
        BufferedImage oldImage = stage(fragment, 300, 1, oldIcon);
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        fragment.setState(Fragment.State.LOADED);
        fragment.invalidateLayerData(IMAGE);
        fragment.invalidateLayerData(ICON);
        fragment.putImage(IMAGE, image(2));
        fragment.markLayerComplete(IMAGE, 1);
        fragment.putWorldIcons(ICON, List.of(icon("unfinished")));
        // Neither failure nor cancellation calls the all-layers publication operation.
        fragment.setState(Fragment.State.INITIALIZED);
        assertSame(oldImage, fragment.getImage(IMAGE));
        assertEquals(List.of(oldIcon), fragment.getWorldIcons(ICON));
        fragment.prepareForLoad(Dimension.NETHER);
        assertFalse(fragment.hasDisplayData(Dimension.OVERWORLD));
        assertNull(fragment.getImage(IMAGE));
        assertTrue(fragment.getWorldIcons(ICON).isEmpty());
        fragment.setCorner(CoordinatesInWorld.from(512, 0));
        assertFalse(fragment.hasDisplayData(Dimension.NETHER));
    }

    @Test
    public void disabledStructuresDoNotBlockPublicationOrLeakOldMarkers() {
        Fragment fragment = create();
        stage(fragment, 300, 1, icon("old"));
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        BitSet required = new BitSet();
        required.set(DATA);
        required.set(IMAGE);
        fragment.setRequiredLayers(required);
        fragment.invalidateLayerData(ICON);
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, IMAGE));
        assertFalse(fragment.hasDisplayLayer(Dimension.OVERWORLD, ICON));
        assertTrue(fragment.getWorldIcons(ICON).isEmpty());
    }

    private static Fragment create() {
        Fragment fragment = new Fragment(5);
        fragment.setSynchronizedPublication(true);
        fragment.setCorner(CoordinatesInWorld.origin());
        fragment.prepareForLoad(Dimension.OVERWORLD);
        fragment.initBiomeData(1, 1);
        fragment.setState(Fragment.State.LOADING);
        BitSet required = new BitSet();
        for (int id : new int[] {DATA, IMAGE, ICON}) required.set(id);
        fragment.setRequiredLayers(required);
        return fragment;
    }

    private static BufferedImage stage(Fragment fragment, int biome, int color, WorldIcon icon) {
        populate(fragment, biome);
        fragment.markLayerComplete(DATA, 0);
        BufferedImage image = image(color);
        fragment.putImage(IMAGE, image);
        fragment.markLayerComplete(IMAGE, 0);
        fragment.putWorldIcons(ICON, List.of(icon));
        fragment.markLayerComplete(ICON, 0);
        return image;
    }

    private static BufferedImage image(int color) {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        return image;
    }

    private static WorldIcon icon(String name) {
        return new WorldIcon(CoordinatesInWorld.origin(), name, null, Dimension.OVERWORLD, false);
    }

    private static void populate(Fragment fragment, int biome) {
        var access =
                new MinecraftInterface.WorldAccessor() {
                    public Set<Dimension> supportedDimensions() {
                        return Set.of(Dimension.OVERWORLD);
                    }

                    public <T> T getBiomeData(
                            Dimension d,
                            int x,
                            int z,
                            int w,
                            int h,
                            boolean quarter,
                            Function<int[], T> mapper) {
                        return mapper.apply(new int[] {biome});
                    }
                };
        fragment.populateBiomeData(
                new BiomeDataOracle(
                        access, Dimension.OVERWORLD, null, new BiomeDataOracle.Config()));
    }
}
