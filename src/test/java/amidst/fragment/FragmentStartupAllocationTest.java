package amidst.fragment;

import static org.junit.Assert.*;

import amidst.fragment.constructor.*;
import amidst.fragment.layer.LayerIds;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.*;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FragmentStartupAllocationTest {
    @Test
    public void startupAllocatesOnlyASmallBatchAndStillGrowsForWideViews() {
        var cache =
                new FragmentCache(
                        new ConcurrentLinkedQueue<>(),
                        new ConcurrentLinkedQueue<>(),
                        List.of(
                                new BiomeDataConstructor(Resolution.QUARTER),
                                new ImageConstructor(Resolution.QUARTER, LayerIds.BACKGROUND),
                                new ImageConstructor(Resolution.CHUNK, LayerIds.SLIME)),
                        LayerIds.NUMBER_OF_LAYERS);
        Set<Fragment> unique = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 130; i++) {
            var tile =
                    cache.requestFragment(CoordinatesInWorld.from(i * 512, 0), Dimension.OVERWORLD);
            assertTrue(unique.add(tile));
            assertTrue(
                    "No large unused batch allocated on the UI thread",
                    cache.size() - unique.size() < 64);
            assertEquals(0, tile.getBiomeDataAt(127, 127));
            assertEquals(128, tile.getImage(LayerIds.BACKGROUND).getWidth());
        }
        assertTrue(cache.size() >= 130);
    }
}
