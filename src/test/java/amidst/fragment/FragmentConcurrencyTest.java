package amidst.fragment;

import static org.junit.Assert.*;

import amidst.fragment.layer.*;
import amidst.mojangapi.world.Dimension;
import amidst.settings.Setting;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

public class FragmentConcurrencyTest {
    @Test
    public void smoothFollowsConfiguredThreadsWhileAlphaKeepsItsSerialBackendLimit()
            throws Exception {
        String override = System.getProperty("amidst.gtnh.concurrentFragments");
        System.clearProperty("amidst.gtnh.concurrentFragments");
        try {
            for (int threads : new int[] {1, 8, 32, 96}) {
                FragmentManager manager =
                        new FragmentManager(List.of(), 0, Setting.createImmutable(threads));
                try {
                    LayerManager layers =
                            new LayerManager(List.of(), new LayerLoader(List.of(), 0), List.of());
                    java.lang.reflect.Field cap =
                            FragmentQueueProcessor.class.getDeclaredField("maxConcurrentLoads");
                    cap.setAccessible(true);
                    assertEquals(
                            Math.min(64, threads),
                            cap.getInt(
                                    manager.createQueueProcessor(
                                            layers,
                                            Setting.createImmutable(Dimension.OVERWORLD),
                                            true,
                                            true)));
                    assertEquals(
                            Math.min(4, threads),
                            cap.getInt(
                                    manager.createQueueProcessor(
                                            layers,
                                            Setting.createImmutable(Dimension.OVERWORLD),
                                            true,
                                            false)));
                } finally {
                    manager.clear();
                    java.lang.reflect.Field pool =
                            FragmentManager.class.getDeclaredField("fragWorkers");
                    pool.setAccessible(true);
                    ((ThreadPoolExecutor) pool.get(manager)).shutdownNow();
                }
            }
        } finally {
            if (override != null) System.setProperty("amidst.gtnh.concurrentFragments", override);
            amidst.gtnh.worker.GtnhBiomeWorkerClient.setSamplingParallelism(
                    Runtime.getRuntime().availableProcessors() / 2 + 1);
        }
    }
}
