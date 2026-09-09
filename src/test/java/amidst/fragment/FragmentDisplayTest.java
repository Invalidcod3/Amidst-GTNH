package amidst.fragment;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import amidst.fragment.layer.LayerDeclaration;
import amidst.fragment.loader.ImageLoader;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.coordinates.Resolution;
import amidst.settings.Setting;

public class FragmentDisplayTest {
    @Test(timeout = 5000)
    public void firstAccurateImageIsVisibleWhileLaterIconsAreStillLoading() throws Exception {
        int background = amidst.fragment.layer.LayerIds.BACKGROUND, icons = amidst.fragment.layer.LayerIds.VILLAGE;
        Fragment fragment = new Fragment(amidst.fragment.layer.LayerIds.NUMBER_OF_LAYERS);
        fragment.setCorner(CoordinatesInWorld.from(0, 0));
        fragment.prepareForLoad(Dimension.OVERWORLD);
        fragment.setState(Fragment.State.LOADING);
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1);
        ImageLoader imageLoader = new ImageLoader(
                visibleDeclaration(background),
                Resolution.QUARTER, (d, f, cx, cz, x, y) -> 0xff336699);
        amidst.fragment.loader.FragmentLoader slowIcons = new amidst.fragment.loader.FragmentLoader(
                visibleDeclaration(icons)) {
            public void load(Dimension d, Fragment f) {
                waiting.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            public void reload(Dimension d, Fragment f) { load(d, f); }
        };
        var layers = new amidst.fragment.layer.LayerLoader(java.util.List.of(imageLoader, slowIcons),
                amidst.fragment.layer.LayerIds.NUMBER_OF_LAYERS);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> layers.loadAll(Dimension.OVERWORLD, fragment));
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, background));
            assertFalse(fragment.hasDisplayLayer(Dimension.OVERWORLD, icons));
            assertEquals(0xff336699, fragment.getImage(background).getRGB(127, 127));
            release.countDown(); future.get(3, TimeUnit.SECONDS);
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, icons));
            fragment.setCorner(CoordinatesInWorld.from(512, 0));
            fragment.prepareForLoad(Dimension.OVERWORLD);
            assertFalse(fragment.hasDisplayLayer(Dimension.OVERWORLD, background));
            imageLoader.load(Dimension.OVERWORLD, fragment);
            fragment.markLayerComplete(background, 0);
            assertTrue(fragment.hasDisplayLayer(Dimension.OVERWORLD, background));
            assertFalse("Old coordinate's icons must remain hidden", fragment.hasDisplayLayer(Dimension.OVERWORLD, icons));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    public void completedPictureSurvivesRefreshButNotCoordinateOrDimensionReuse() {
        Fragment fragment = ready();
        fragment.setState(Fragment.State.LOADING);
        assertTrue(fragment.hasDisplayData(Dimension.OVERWORLD));
        assertFalse(fragment.hasDisplayData(Dimension.NETHER));
        fragment.setState(Fragment.State.INITIALIZED); // Failed reload retains old picture.
        assertTrue(fragment.hasDisplayData(Dimension.OVERWORLD));
        fragment.prepareForLoad(Dimension.NETHER);
        assertFalse(fragment.hasDisplayData(Dimension.OVERWORLD));
        assertFalse(fragment.hasDisplayData(Dimension.NETHER));
        fragment.setCorner(CoordinatesInWorld.from(512, 0));
        assertFalse(fragment.hasDisplayData(Dimension.OVERWORLD));
    }

    @Test(timeout = 5000)
    public void refreshPublishesOnlyACompleteImageAndNeverMutatesTheVisibleImage() throws Exception {
        Fragment fragment = ready();
        BufferedImage old = fragment.getImage(0);
        CountDownLatch painting = new CountDownLatch(1), release = new CountDownLatch(1);
        ImageLoader loader = new ImageLoader(declaration(), Resolution.QUARTER, (d, f, cx, cz, x, y) -> {
            if (x == 0 && y == 1) {
                painting.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timed out"); }
                catch (InterruptedException e) { throw new AssertionError(e); }
            }
            return 0xff336699;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            fragment.setState(Fragment.State.LOADING);
            var future = executor.submit(() -> loader.reload(Dimension.OVERWORLD, fragment));
            assertTrue(painting.await(3, TimeUnit.SECONDS));
            assertSame(old, fragment.getImage(0));
            assertEquals(0xffff0000, old.getRGB(0, 0));
            assertTrue(fragment.hasDisplayData(Dimension.OVERWORLD));
            release.countDown();
            future.get(3, TimeUnit.SECONDS);
            assertNotSame(old, fragment.getImage(0));
            assertEquals(0xff336699, fragment.getImage(0).getRGB(127, 127));
            assertEquals(0xffff0000, old.getRGB(0, 0));
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test
    public void failedPaintingPreservesTheLastPublishedPicture() {
        Fragment fragment = ready();
        BufferedImage old = fragment.getImage(0);
        ImageLoader loader = new ImageLoader(declaration(), Resolution.QUARTER, (d, f, cx, cz, x, y) -> {
            if (y == 1) throw new IllegalStateException("deliberate renderer failure");
            return 0xff336699;
        });
        assertThrows(IllegalStateException.class, () -> loader.reload(Dimension.OVERWORLD, fragment));
        assertSame(old, fragment.getImage(0));
        assertEquals(0xffff0000, old.getRGB(0, 0));
    }

    private static LayerDeclaration visibleDeclaration(int id) {
        LayerDeclaration declaration = new LayerDeclaration(id, Dimension.OVERWORLD, false, true, Setting.createImmutable(true));
        declaration.update(Dimension.OVERWORLD);
        return declaration;
    }

    private static LayerDeclaration declaration() {
        return new LayerDeclaration(0, Dimension.OVERWORLD, false, true, Setting.createImmutable(true));
    }

    private static Fragment ready() {
        Fragment fragment = new Fragment(1);
        fragment.setCorner(CoordinatesInWorld.from(0, 0));
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xffff0000);
        fragment.putImage(0, image);
        fragment.setLoadedDimension(Dimension.OVERWORLD);
        fragment.setState(Fragment.State.LOADED);
        return fragment;
    }
}
