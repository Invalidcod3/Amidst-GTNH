package amidst.fragment;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.constructor.FragmentConstructor;
import amidst.fragment.layer.LayerManager;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.settings.Setting;

@NotThreadSafe
public class FragmentManager {
	private static final int GTNH_MAX_CONCURRENT_FRAGMENTS = 64;
	private final ConcurrentLinkedQueue<Fragment> availableQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Fragment> loadingQueue = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<Fragment> recycleQueue = new ConcurrentLinkedQueue<>();
	private final FragmentCache cache;
	
	private final Setting<Integer> threadsSetting;
	private ThreadPoolExecutor fragWorkers;
	private volatile FragmentViewport viewport;
	private FragmentQueueProcessor queueProcessor;
	private Setting<Dimension> dimensionSetting = Setting.createImmutable(Dimension.OVERWORLD);

	@CalledOnlyBy(AmidstThread.EDT)
	public FragmentManager(Iterable<FragmentConstructor> constructors, int numberOfLayers, Setting<Integer> threadsSetting) {
		this.cache = new FragmentCache(availableQueue, loadingQueue, constructors, numberOfLayers);
		this.threadsSetting = threadsSetting;
		this.fragWorkers = createThreadPool();
	}
	
	public ThreadPoolExecutor createThreadPool() {
		ThreadPoolExecutor pool = (ThreadPoolExecutor) Executors.newFixedThreadPool(threadsSetting.get(), new ThreadFactory() {
			private int num;
			
			@Override
			public Thread newThread(Runnable r) {
				return new Thread(r, "Fragment-Worker-" + num++);
			}
		});
		pool.setKeepAliveTime(5L, java.util.concurrent.TimeUnit.SECONDS);
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public Fragment requestFragment(CoordinatesInWorld coordinates) {
		return cache.requestFragment(coordinates, dimensionSetting.get());
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void recycleFragment(Fragment fragment) {
		cache.requestRecycling(fragment);
		recycleQueue.offer(fragment);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void setViewport(CoordinatesInWorld topLeft, CoordinatesInWorld bottomRight) {
		viewport = new FragmentViewport(topLeft, bottomRight);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public FragmentQueueProcessor createQueueProcessor(
			LayerManager layerManager, Setting<Dimension> dimensionSetting, boolean usesGtnhWorker,
            boolean parallelSmooth) {
		this.dimensionSetting = dimensionSetting;
        cache.setSynchronizedPublication(usesGtnhWorker && parallelSmooth);
		int concurrency = usesGtnhWorker
				? Math.max(1, Math.min(parallelSmooth ? GTNH_MAX_CONCURRENT_FRAGMENTS : 4,
						Integer.getInteger("amidst.gtnh.concurrentFragments", fragWorkers.getMaximumPoolSize())))
				: fragWorkers.getMaximumPoolSize();
		if (usesGtnhWorker) amidst.gtnh.worker.GtnhBiomeWorkerClient.setSamplingParallelism(
				Math.min(GTNH_MAX_CONCURRENT_FRAGMENTS, fragWorkers.getMaximumPoolSize()));
		queueProcessor = new FragmentQueueProcessor(
				loadingQueue,
				recycleQueue,
				cache,
				layerManager,
				fragWorkers,
				dimensionSetting,
				() -> viewport,
				concurrency, usesGtnhWorker && parallelSmooth);
		return queueProcessor;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public int getAvailableQueueSize() {
		return availableQueue.size();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public int getLoadingQueueSize() {
		return loadingQueue.size() + (queueProcessor == null ? 0 : queueProcessor.getActiveLoadCount());
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public int getRecycleQueueSize() {
		return recycleQueue.size();
	}
	
	@CalledOnlyBy(AmidstThread.EDT)
	public void clear() {
		if (queueProcessor != null) {
			queueProcessor.dispose();
		}
		cache.clear();
		availableQueue.clear();
		loadingQueue.clear();
		recycleQueue.clear();
	}
	
	@CalledOnlyBy(AmidstThread.EDT)
	public void restartThreadPool() {
		fragWorkers.shutdownNow();
		this.fragWorkers = createThreadPool();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public int getCacheSize() {
		return cache.size();
	}
}
