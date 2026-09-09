package amidst.gtnh.worker;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class OverworldTileCacheTest {
    @Test public void yieldsWithoutRepeatingCompletedPixelsAndRetainsCancelledWork() {
        OverworldTileCache cache = new OverworldTileCache(2);
        Object context = new Object();
        AtomicInteger calls = new AtomicInteger();
        OverworldTileCache.Sampling job = cache.begin(context, context, -16, -32, 8, 8, 4,
                (x,z) -> -1, (x,z) -> {
                    calls.incrementAndGet();
                    java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);
                    return x * 100 + z + 10000;
                });
        assertFalse(job.advance(System.nanoTime() - 1));
        assertEquals(0, calls.get());
        assertFalse(job.advance(System.nanoTime() + 2_000_000));
        assertTrue(calls.get() > 0 && calls.get() < 64);
        assertTrue(job.advance(Long.MAX_VALUE));
        assertEquals(64, calls.get());
        int[] again = cache.sample(context, context, -16, -32, 8, 8, 4, (x,z)->-1, (x,z)->{
            fail("Completed pure predictions must survive yielding"); return 0;
        });
        assertArrayEquals(job.result, again);
    }
    @Test
    public void loadedTruthAlwaysOverridesPredictionsAndUnloadRestoresPurePrediction() {
        OverworldTileCache cache = new OverworldTileCache(2);
        Object world = new Object(), manager = new Object();
        AtomicInteger calls = new AtomicInteger(), actual = new AtomicInteger(-1);
        java.util.function.IntBinaryOperator predict = (x, z) -> { calls.incrementAndGet(); return 121; };
        assertArrayEquals(new int[] {121}, cache.sample(world, manager, 0, 0, 1, 1, 4, (x,z)->actual.get(), predict));
        actual.set(62);
        assertArrayEquals(new int[] {62}, cache.sample(world, manager, 0, 0, 1, 1, 4, (x,z)->actual.get(), predict));
        actual.set(65000); // Extended IDs and edits to already-loaded chunks.
        assertArrayEquals(new int[] {65000}, cache.sample(world, manager, 0, 0, 1, 1, 4, (x,z)->actual.get(), predict));
        actual.set(-1);
        assertArrayEquals(new int[] {121}, cache.sample(world, manager, 0, 0, 1, 1, 4, (x,z)->actual.get(), predict));
        assertEquals(1, calls.get());
    }

    @Test
    public void cacheIsBoundedIsolatedByContextAndReturnsOwnedArrays() {
        OverworldTileCache cache = new OverworldTileCache(1);
        Object world = new Object(), manager = new Object();
        AtomicInteger calls = new AtomicInteger();
        java.util.function.IntBinaryOperator predict = (x,z)->calls.incrementAndGet();
        int[] first=cache.sample(world,manager,0,0,1,1,1,(x,z)->-1,predict);
        first[0]=900;
        assertEquals(1,cache.sample(world,manager,0,0,1,1,1,(x,z)->-1,predict)[0]);
        cache.sample(world,manager,16,0,1,1,1,(x,z)->-1,predict);
        assertEquals(3,cache.sample(world,manager,0,0,1,1,1,(x,z)->-1,predict)[0]);
        assertEquals(4,cache.sample(new Object(),manager,0,0,1,1,1,(x,z)->-1,predict)[0]);
        assertEquals(5,cache.sample(world,new Object(),0,0,1,1,1,(x,z)->-1,predict)[0]);
    }

    @Test
    public void negativeCoordinatesKeepMapCellCentersAndLoadedCellsAreNotPredictions() {
        OverworldTileCache cache = new OverworldTileCache(2);
        Object context = new Object();
        int[] initial=cache.sample(context,context,-16,-32,2,2,4,(x,z)->x==-14?62:-1,(x,z)->x*100+z+5000);
        assertArrayEquals(new int[] {62,3970,62,3974},initial);
        int[] unloaded=cache.sample(context,context,-16,-32,2,2,4,(x,z)->-1,(x,z)->x*100+z+5000);
        assertArrayEquals(new int[] {3570,3970,3574,3974},unloaded);
    }
}
