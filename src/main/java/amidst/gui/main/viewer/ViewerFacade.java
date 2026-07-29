package amidst.gui.main.viewer;

import amidst.AmidstSettings;
import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.FragmentGraph;
import amidst.fragment.FragmentManager;
import amidst.fragment.FragmentQueueProcessor;
import amidst.fragment.layer.LayerBuilder;
import amidst.fragment.layer.LayerManager;
import amidst.fragment.layer.LayerReloader;
import amidst.gtnh.worker.GtnhWorldState;
import amidst.gui.export.BiomeExporterDialog;
import amidst.gui.main.Actions;
import amidst.gui.main.viewer.widget.*;
import amidst.gui.main.viewer.widget.ProgressWidget.ProgressEntryType;
import amidst.mojangapi.world.Dimension;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.coordinates.CoordinatesInWorld;
import amidst.mojangapi.world.icon.WorldIcon;
import amidst.mojangapi.world.player.MovablePlayerList;
import amidst.logging.AmidstLogger;
import amidst.settings.Setting;
import amidst.threading.WorkerExecutor;

import java.awt.Component;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class works as wrapper around a world instance. It holds everything that
 * is needed to display the world on the screen. This allows us to easily
 * exchange the currently displayed world.
 */
@NotThreadSafe
public class ViewerFacade {
	private static final long GTNH_WORLD_STATE_POLL_NANOS = 1_000_000_000L;
	private static final long GTNH_WORLD_STATE_RETRY_NANOS = 5_000_000_000L;

	private final World world;
	private final FragmentManager fragmentManager;
	private final FragmentGraph graph;
	private final FragmentGraphToScreenTranslator translator;
	private final Zoom zoom;
	private final Viewer viewer;
	private final LayerReloader layerReloader;
	private final WorldIconSelection worldIconSelection;
	private final LayerManager layerManager;
	private final WorkerExecutor workerExecutor;
	private final BiomeExporterDialog biomeExporterDialog;
	private final FragmentQueueProcessor fragmentQueueProcessor;
	private final Setting<Dimension> dimensionSetting;
	private final AtomicReference<Entry<ProgressEntryType, Integer>> progressEntryHolder;
	private long gtnhWorldRevision = -1L;
	private long nextGtnhWorldStatePollNanos;

	@CalledOnlyBy(AmidstThread.EDT)
	public ViewerFacade(
			AmidstSettings settings,
			World world,
			FragmentManager fragmentManager,
			Zoom zoom,
			WorkerExecutor workerExecutor,
			BiomeExporterDialog biomeExporterDialog,
			LayerBuilder layerBuilder,
			BiomeSelection biomeSelection,
			Actions actions) {
		this.world = world;
		this.fragmentManager = fragmentManager;
		this.zoom = zoom;
		this.workerExecutor = workerExecutor;
		this.biomeExporterDialog = biomeExporterDialog;
		this.dimensionSetting = settings.dimension;

		Graphics2DAccelerationCounter accelerationCounter = new Graphics2DAccelerationCounter();
		Movement movement = new Movement(settings.smoothScrolling);

		this.worldIconSelection = new WorldIconSelection();
		this.layerManager = layerBuilder.create(settings, world, biomeSelection, worldIconSelection, zoom, accelerationCounter);
		this.graph = new FragmentGraph(layerManager.getDeclarations(), fragmentManager);
		this.translator = new FragmentGraphToScreenTranslator(graph, zoom);
		this.fragmentQueueProcessor = fragmentManager.createQueueProcessor(layerManager, settings.dimension);
		this.layerReloader = layerManager.createLayerReloader(world);
		this.progressEntryHolder = new AtomicReference<Entry<ProgressEntryType, Integer>>();

		DebugWidget debugWidget = new DebugWidget(Widget.CornerAnchorPoint.BOTTOM_RIGHT, graph, fragmentManager, settings.showDebug, accelerationCounter, zoom);
		BiomeWidget biomeWidget = new BiomeWidget(Widget.CornerAnchorPoint.NONE, biomeSelection, layerReloader, settings.biomeProfileSelection, world.getBiomeList());
		BiomeToggleWidget biomeToggleWidget = new BiomeToggleWidget(Widget.CornerAnchorPoint.BOTTOM_RIGHT, biomeWidget, biomeSelection);
		WorldOptions worldOptions = world.getWorldOptions();
		List<Widget> widgets = Arrays.asList(
				new FpsWidget(Widget.CornerAnchorPoint.BOTTOM_LEFT, new FramerateTimer(2), settings.showFPS),
				new ScaleWidget(Widget.CornerAnchorPoint.BOTTOM_CENTER, zoom, settings.showScale),
				new SeedAndWorldTypeWidget(Widget.CornerAnchorPoint.TOP_LEFT, worldOptions.getWorldSeed(), worldOptions.getWorldType()),
				new SelectedIconWidget(Widget.CornerAnchorPoint.TOP_LEFT, worldIconSelection),
				debugWidget,
				new CursorInformationWidget(Widget.CornerAnchorPoint.TOP_RIGHT, graph, translator, settings.dimension, world.getBiomeList()),
				biomeToggleWidget,
				new BiomeExporterProgressWidget(Widget.CornerAnchorPoint.BOTTOM_RIGHT, progressEntryHolder::get, -20, settings.showDebug, debugWidget, biomeToggleWidget.getWidth()),
				biomeWidget
		);

		Drawer drawer = new Drawer(
				graph,
				translator,
				zoom,
				movement,
				widgets,
				layerManager.getDrawers(),
				settings.dimension,
				accelerationCounter);

		ViewerMouseListener viewerMouseListener = new ViewerMouseListener(new WidgetManager(widgets), graph, translator, zoom, movement, actions);
		this.viewer = new Viewer(viewerMouseListener, drawer);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public Component getComponent() {
		return viewer.getComponent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void reloadBackgroundLayer() {
		layerReloader.reloadBackgroundLayer();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void reloadPlayerLayer() {
		layerReloader.reloadPlayerLayer();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void dispose() {
		graph.dispose();
		zoom.skipFading();
		zoom.reset();
		fragmentManager.clear();
		fragmentManager.restartThreadPool();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public Runnable getOnRepainterTick() {
		return viewer::repaintComponent;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public Runnable getOnFragmentLoaderTick() {
		return this::onFragmentLoaderTick;
	}

	private void onFragmentLoaderTick() {
		pollGtnhWorldState();
		fragmentQueueProcessor.processQueues();
	}

	private void pollGtnhWorldState() {
		if (!world.supportsGtnhWorldStateUpdates()
				|| dimensionSetting.get() != Dimension.OVERWORLD) {
			return;
		}
		long now = System.nanoTime();
		if (now < nextGtnhWorldStatePollNanos) {
			return;
		}
		nextGtnhWorldStatePollNanos = now + GTNH_WORLD_STATE_POLL_NANOS;
		try {
			GtnhWorldState state = world.getGtnhWorldState(gtnhWorldRevision);
			boolean isBaseline = gtnhWorldRevision < 0L;
			gtnhWorldRevision = state.revision();
			if (isBaseline) {
				return;
			}
			if (state.fullRefresh()) {
				fragmentQueueProcessor.requestBiomeReloadAllUsed();
			} else {
				int[] chunkXs = state.chunkXs();
				int[] chunkZs = state.chunkZs();
				if (chunkXs.length == 0) {
					return;
				}
				fragmentQueueProcessor.requestBiomeReloadForChunks(
						chunkXs,
						chunkZs);
			}
		} catch (amidst.mojangapi.minecraftinterface.MinecraftInterfaceException e) {
			nextGtnhWorldStatePollNanos =
					System.nanoTime() + GTNH_WORLD_STATE_RETRY_NANOS;
			AmidstLogger.warn(e, "Unable to read GTNH overworld chunk state");
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void centerOn(CoordinatesInWorld coordinates) {
		translator.centerOn(coordinates);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void centerOn(WorldIcon worldIcon) {
		translator.centerOn(worldIcon.getCoordinates());
		worldIconSelection.select(worldIcon);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public BufferedImage createScreenshot() {
		return viewer.createScreenshot();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void adjustZoom(int notches) {
		zoom.adjustZoom(viewer.getMousePositionOrCenter(), notches);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void adjustZoom(Point mousePosition, int notches) {
		zoom.adjustZoom(mousePosition, notches);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void selectWorldIcon(WorldIcon worldIcon) {
		worldIconSelection.select(worldIcon);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public WorldOptions getWorldOptions() {
		return world.getWorldOptions();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public WorldIcon getSpawnWorldIcon() {
		return world.getSpawnWorldIcon();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public List<WorldIcon> getStrongholdWorldIcons() {
		return world.getStrongholdWorldIcons();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public List<WorldIcon> getPlayerWorldIcons() {
		return world.getPlayerWorldIcons();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public MovablePlayerList getMovablePlayerList() {
		return world.getMovablePlayerList();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean canLoadPlayerLocations() {
		return world.getMovablePlayerList().canLoad();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void loadPlayers() {
		worldIconSelection.clear();
		world.getMovablePlayerList().load(workerExecutor, layerReloader::reloadPlayerLayer);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean canSavePlayerLocations() {
		return world.getMovablePlayerList().canSave();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void savePlayerLocations() {
		world.getMovablePlayerList().save();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean calculateIsLayerEnabled(int layerId, Dimension dimension) {
		return layerManager.calculateIsEnabled(layerId, dimension);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean hasLayer(int layerId) {
		return world.getEnabledLayers().contains(layerId);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean hasNetherBiomeLayer() {
		return world.getNetherBiomeDataOracle().isPresent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean hasEndBiomeLayer() {
		return world.getEndBiomeDataOracle().isPresent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean hasMoonBiomeLayer() {
		return world.getMoonBiomeDataOracle().isPresent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean hasTwilightForestBiomeLayer() {
		return world.getTwilightForestBiomeDataOracle().isPresent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public boolean hasBiomeLayer(Dimension dimension) {
		return world.getBiomeDataOracle(dimension).isPresent();
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public void openExportDialog() {
		biomeExporterDialog.createAndShow(world, translator, progressEntryHolder::set);
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public World getWorld() {
		return world;
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public CoordinatesInWorld getVisibleTopLeft() {
		return translator.screenToWorld(new Point(0, 0));
	}

	@CalledOnlyBy(AmidstThread.EDT)
	public CoordinatesInWorld getVisibleBottomRight() {
		return translator.screenToWorld(
				new Point((int) translator.getWidth(), (int) translator.getHeight()));
	}

	public boolean isFullyLoaded() {
		return fragmentManager.getLoadingQueueSize() == 0;
	}
}
