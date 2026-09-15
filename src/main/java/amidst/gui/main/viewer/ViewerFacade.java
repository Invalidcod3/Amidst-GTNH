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
import amidst.gtnh.export.GtnhMapWaypoint;
import amidst.gtnh.export.JourneyMapAutoImport;
import amidst.i18n.I18n;
import amidst.logging.AmidstLogger;
import amidst.gtnh.worker.GtnhWorldState;
import amidst.gtnh.worker.GtnhWorldStatePoller;
import amidst.gtnh.worker.GtnhCursorBiomeLookup;
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
    private final AmidstSettings settings;
    public amidst.AmidstSettings getSettings() { return settings; }
    private final amidst.gtnh.prospecting.ProspectingOverlay prospecting;
    public java.util.List<amidst.gtnh.prospecting.ProspectingData.DimensionInfo> prospectingCatalog() {
        return world.prospectingCatalog();
    }
    public boolean hasProspecting(Dimension dimension) { return prospecting.dimensionInfo(dimension) != null; }
    public void refreshProspecting() { prospecting.refresh(); }
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
	private final GtnhWorldStatePoller gtnhWorldStatePoller;
	private final GtnhCursorBiomeLookup cursorBiomeLookup;
	private final JourneyMapAutoImport journeyMapAutoImport;
	private String journeyMapStatus;
    private String viewIdentity;
    private long viewGeneration;
    private volatile boolean disposed;
    private javax.swing.Timer viewSaveTimer;
    private final java.nio.file.Path viewsPath = amidst.gtnh.cache.MapStorage.root().resolve("views");
	private long journeyMapStatusUntil;

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
        this.settings = settings;
        amidst.gtnh.cache.MapStorage.enabled=settings.cacheMap.get();
		this.fragmentManager = fragmentManager;
		this.zoom = zoom;
		this.workerExecutor = workerExecutor;
		this.biomeExporterDialog = biomeExporterDialog;
		this.dimensionSetting = settings.dimension;
		this.journeyMapAutoImport = new JourneyMapAutoImport(settings.autoImportJourneyMap, workerExecutor,
				world::importJourneyMapWaypoints, target -> {
					journeyMapStatus = I18n.format("Added to JourneyMap: {0} ({1})", target.waypoint().name(), target.dimension());
					journeyMapStatusUntil = System.currentTimeMillis() + 6000;
					AmidstLogger.info(journeyMapStatus);
				}, failure -> {
					journeyMapStatus = I18n.format("JourneyMap import failed: {0}", failure.getMessage());
					journeyMapStatusUntil = System.currentTimeMillis() + 15000;
					AmidstLogger.error(failure);
				});
		this.gtnhWorldStatePoller = new GtnhWorldStatePoller(
				world::getGtnhWorldState, task -> workerExecutor.run(task::run));
		this.cursorBiomeLookup = world.supportsGtnhWorldStateUpdates()
				? new GtnhCursorBiomeLookup((dimension, x, z) -> world.getBiomeDataOracle(dimension)
						.orElseThrow(() -> new IllegalStateException("No biome oracle for " + dimension))
						.getBiomeAt(x, z, false).getName(), task -> workerExecutor.run(task::run))
				: null;

		Graphics2DAccelerationCounter accelerationCounter = new Graphics2DAccelerationCounter();
		Movement movement = new Movement(settings.smoothScrolling);

		this.worldIconSelection = new WorldIconSelection(world::getSpawnWorldIcon);
		this.layerManager = layerBuilder.create(settings, world, biomeSelection, worldIconSelection, zoom, accelerationCounter);
		this.graph = new FragmentGraph(layerManager.getDeclarations(), fragmentManager);
		this.translator = new FragmentGraphToScreenTranslator(graph, zoom);
        this.prospecting = new amidst.gtnh.prospecting.ProspectingOverlay(world, settings, translator, zoom);
        this.prospecting.setOnDoubleClick(journeyMapAutoImport::add);
		this.fragmentQueueProcessor = fragmentManager.createQueueProcessor(
				layerManager, settings.dimension, world.supportsGtnhWorldStateUpdates());
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
				new ChangeableTextWidget(Widget.CornerAnchorPoint.BOTTOM_CENTER,
						() -> System.currentTimeMillis() < journeyMapStatusUntil ? journeyMapStatus : null) {{ increaseYMargin(40); }},
				debugWidget,
				new CursorInformationWidget(Widget.CornerAnchorPoint.TOP_RIGHT, graph, translator, settings.dimension, world.getBiomeList(), cursorBiomeLookup),
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
        drawer.setProspecting(prospecting);
        viewerMouseListener.setProspecting(prospecting);
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
        saveView(); disposed=true; viewGeneration++;
        if(viewSaveTimer!=null)viewSaveTimer.stop();
        journeyMapAutoImport.close();
        prospecting.close();
		gtnhWorldStatePoller.close();
		if (cursorBiomeLookup != null) cursorBiomeLookup.close();
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
		if (!world.supportsGtnhWorldStateUpdates()) {
			return;
		}
		GtnhWorldState state = gtnhWorldStatePoller.poll();
		if (state == null) {
			return;
		}
		if (state.fullRefresh()) {
            world.clearCachedPredictions();
            for (int layer=0;layer<amidst.fragment.layer.LayerIds.NUMBER_OF_LAYERS;layer++) layerManager.invalidateLayer(layer);
            javax.swing.SwingUtilities.invokeLater(() -> { if(!disposed){prospecting.refresh(); refreshViewIdentity();} });
			if (cursorBiomeLookup != null) cursorBiomeLookup.invalidate();
			fragmentQueueProcessor.requestBiomeReloadAllUsed();
		} else if (state.chunkXs().length != 0 && dimensionSetting.get()==Dimension.OVERWORLD) {
            world.clearCachedPredictions();
            for (int layer=amidst.fragment.layer.LayerIds.GTNH_ROGUELIKE_DESERT;
                    layer<amidst.fragment.layer.LayerIds.GTNH_WORLD_SPAWN;layer++) layerManager.invalidateLayer(layer);
			if (cursorBiomeLookup != null) cursorBiomeLookup.invalidate();
			fragmentQueueProcessor.requestBiomeReloadForChunks(state.chunkXs(), state.chunkZs());
		}
	}

    private amidst.gtnh.cache.MapViewState viewSnapshot() {
        var center=translator.screenToWorld(new Point((int)translator.getWidth()/2,(int)translator.getHeight()/2));
        return new amidst.gtnh.cache.MapViewState(1,center.getX(),center.getY(),zoom.getLevel(),dimensionSetting.get().name(),
                settings.markerMode.get().name(),settings.prospectingFilter.get(),settings.prospectingMinimumFluid.get());
    }
    private void saveView() {
        if(settings.rememberMap.get() && viewIdentity!=null && translator.getWidth()>0) viewSnapshot().save(viewsPath,viewIdentity);
    }
    public void startViewRecovery() {
        viewSaveTimer=new javax.swing.Timer(5000,event->{if(viewIdentity==null)refreshViewIdentity();else saveView();});
        viewSaveTimer.setInitialDelay(150);viewSaveTimer.start();
    }
    private void refreshViewIdentity() {
        if(!world.supportsGtnhWorldStateUpdates() || disposed || translator.getWidth()<=0)return;
        long generation=++viewGeneration;
        var initial=viewSnapshot();
        workerExecutor.run(() -> {
            String identity=world.getMapCacheIdentity();
            return new java.util.AbstractMap.SimpleEntry<>(identity,amidst.gtnh.cache.MapViewState.read(viewsPath,identity));
        }, loaded -> {
            if(disposed || generation!=viewGeneration || java.util.Objects.equals(viewIdentity,loaded.getKey()))return;
            saveView();viewIdentity=loaded.getKey();
            var saved=loaded.getValue();
            if(saved!=null && settings.rememberMap.get() && initial.equals(viewSnapshot())) {
                Dimension dimension=Dimension.valueOf(saved.dimension());
                if(world.getBiomeDataOracle(dimension).isPresent() || hasProspecting(dimension)) {
                    dimensionSetting.set(dimension);zoom.restoreLevel(saved.zoom());
                    translator.centerOn(CoordinatesInWorld.from(saved.x(),saved.z()));
                    settings.markerMode.set(amidst.gtnh.prospecting.MarkerMode.valueOf(saved.mode()));
                    settings.prospectingFilter.set(saved.filter());settings.prospectingMinimumFluid.set(saved.minimumFluid());
                }
            }
        }, failure->AmidstLogger.warn(failure,"Map view recovery unavailable"));
    }
    public void refreshMap() {
        world.invalidateSpawn();
        world.clearCachedPredictions();prospecting.refresh();
        if(cursorBiomeLookup!=null)cursorBiomeLookup.invalidate();
        for(int layer=0;layer<amidst.fragment.layer.LayerIds.NUMBER_OF_LAYERS;layer++)layerManager.invalidateLayer(layer);
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
	public void importWorldIconToJourneyMap(WorldIcon icon) {
		if (icon != null && settings.autoImportJourneyMap.get()) {
			Dimension dimension = dimensionSetting.get();
			var fragment = graph.getFragmentAt(icon.getCoordinates());
			// Do not import stale icons while the map is switching dimensions.
			if (fragment != null && fragment.hasDisplayData(dimension) && fragment.getDataDimension() == dimension) {
				journeyMapAutoImport.add(GtnhMapWaypoint.structure(dimension, icon));
			}
		}
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
