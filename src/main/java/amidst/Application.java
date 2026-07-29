package amidst;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.fragment.FragmentManager;
import amidst.fragment.layer.LayerBuilder;
import amidst.gui.license.LicenseWindow;
import amidst.gui.main.MainWindow;
import amidst.gui.main.MainWindowDialogs;
import amidst.gui.main.UpdatePrompt;
import amidst.gui.main.viewer.BiomeSelection;
import amidst.gui.main.viewer.Zoom;
import amidst.gui.profileselect.ProfileSelectWindow;
import amidst.logging.AmidstLogger;
import amidst.logging.AmidstMessageBox;
import amidst.mojangapi.LauncherProfileRunner;
import amidst.mojangapi.RunningLauncherProfile;
import amidst.mojangapi.file.*;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.mojangapi.world.SeedHistoryLogger;
import amidst.mojangapi.world.WorldBuilder;
import amidst.parsing.FormatException;
import amidst.settings.biomeprofile.BiomeProfileDirectory;
import amidst.threading.ThreadMaster;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * The actual Amidst application, holding its state.
 */
@NotThreadSafe
public class Application {
	private final LauncherProfileRunner launcherProfileRunner;

	/**
	 * The main window object.
	 */
	private volatile MainWindow mainWindow;

	private volatile ProfileSelectWindow profileSelectWindow;
	private volatile Optional<LauncherProfile> selectedLauncherProfile;

	private final ThreadMaster threadMaster = new ThreadMaster();
	private final LayerBuilder layerBuilder = new LayerBuilder();
	private final BiomeSelection biomeSelection = new BiomeSelection();
	private final AmidstSettings settings;
	private final MinecraftInstallation minecraftInstallation;
	private final BiomeProfileDirectory biomeProfileDirectory;
	private final VersionListProvider versionListProvider;
	private final Zoom zoom;
	private final FragmentManager fragmentManager;

	/**
	 * The versions of minecraft available to amidst.
	 */
	private final List<Version> versions;

	/**
	 * Creates a new Amidst application instance.
	 *
	 * @param parameters the command line parameters passed into the executable
	 * @param settings   user settings
	 * @throws FormatException if the JSON parser fails
	 * @throws IOException     if the JSON parser fails
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public Application(CommandLineParameters parameters, AmidstSettings settings) throws FormatException, IOException {
		this.settings = settings;

		minecraftInstallation = MinecraftInstallation.newLocalMinecraftInstallation(parameters.dotMinecraftDirectory);

		selectedLauncherProfile = parameters.getInitialLauncherProfile(minecraftInstallation);

		WorldBuilder worldBuilder = new WorldBuilder(new PlayerInformationCache(), SeedHistoryLogger.from(parameters.seedHistoryFile));
		launcherProfileRunner = new LauncherProfileRunner(
				worldBuilder,
				parameters.getInitialWorldOptions(),
				parameters.getGtnhWorkerSettings());
		biomeProfileDirectory = BiomeProfileDirectory.create(parameters.biomeProfilesDirectory);
		versionListProvider = new VersionListProvider(threadMaster.getWorkerExecutor());
		versions = Version.newLocalVersionList();
		zoom = new Zoom(settings.maxZoom);
		fragmentManager = new FragmentManager(layerBuilder.getConstructors(), layerBuilder.getNumberOfLayers(), settings.threads);
	}

	/**
	 * Opens the profile select window, or a pre-selected launcher profile,
	 * if it is set.
	 * <p>
	 * This also checks for updates in the background.
	 *
	 * @throws MinecraftInterfaceCreationException
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void run() throws MinecraftInterfaceCreationException {
		if (!launcherProfileRunner.isGtnhWorkerEnabled()) {
			UpdatePrompt.from(Amidst.VERSION, threadMaster.getWorkerExecutor(), null, true).check();
		}

		if (selectedLauncherProfile.isPresent()) {
			if (launcherProfileRunner.isGtnhWorkerEnabled()) {
				startGtnhMainWindow(selectedLauncherProfile.get());
			} else {
				displayMainWindow(launcherProfileRunner.run(selectedLauncherProfile.get()));
			}
		} else {
			displayProfileSelectWindow();
		}
	}

	@CalledOnlyBy(AmidstThread.EDT)
	private void startGtnhMainWindow(LauncherProfile launcherProfile) {
		threadMaster.getWorkerExecutor().run(
				() -> launcherProfileRunner.run(launcherProfile),
				this::displayMainWindow,
				e -> {
					AmidstLogger.error(e);
					AmidstMessageBox.displayError("Unable to start world backend", e.getMessage());
				});
	}

	/**
	 * Checks for updates in the foreground.
	 *
	 * @param dialogs
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void checkForUpdates(MainWindowDialogs dialogs) {
		UpdatePrompt.from(Amidst.VERSION, threadMaster.getWorkerExecutor(), dialogs, false).check();
	}

	/**
	 * Shows the {@link MainWindow} to the user.
	 * <p>
	 * This disposes of the profile select window if it is visible,
	 * and any previous main window.
	 *
	 * @param runningLauncherProfile
	 * @return the window object that was shown
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public MainWindow displayMainWindow(RunningLauncherProfile runningLauncherProfile) {
		selectedLauncherProfile = Optional.of(runningLauncherProfile.getLauncherProfile());
		settings.biomeProfileSelection.setRuntimeBiomeColors(runningLauncherProfile.getRuntimeBiomeColors());
		MainWindow m = new MainWindow(
				this,
				settings,
				minecraftInstallation,
				runningLauncherProfile,
				biomeProfileDirectory,
				zoom,
				layerBuilder,
				fragmentManager,
				biomeSelection,
				threadMaster);

		if (mainWindow != null) {
			mainWindow.dispose();
		}
		mainWindow = m;

		if (profileSelectWindow != null) {
			profileSelectWindow.dispose();
			profileSelectWindow = null;
		}

		return mainWindow;
	}

	/**
	 * Creates and shows a profile selection window.
	 * <p>
	 * This disposes the main window if it is visible, and any previous
	 * profile select window.
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void displayProfileSelectWindow() {
		ProfileSelectWindow window = new ProfileSelectWindow(
				this,
				threadMaster.getWorkerExecutor(),
				versions,
				versionListProvider,
				minecraftInstallation,
				launcherProfileRunner,
				settings);

		if (profileSelectWindow != null) {
			profileSelectWindow.dispose();
		}
		profileSelectWindow = window;

		if (mainWindow != null) {
			mainWindow.dispose();
			mainWindow = null;
		}
	}

	/**
	 * Creates and shows a new {@link LicenseWindow}.
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void displayLicenseWindow() {
		new LicenseWindow();
	}

	/**
	 * Disposes of the application windows and exits the application.
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void exitGracefully() {
		dispose();
		System.exit(0);
	}

	/**
	 * Disposes of the profile select window and the main window,
	 * setting them to {@code null}.
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void dispose() {
		if (profileSelectWindow != null) {
			profileSelectWindow.dispose();
			profileSelectWindow = null;
		}
		if (mainWindow != null) {
			mainWindow.dispose();
			mainWindow = null;
		}
	}

	/**
	 * Disposes of the profile select and main windows, and runs {@link #run()}.
	 */
	@CalledOnlyBy(AmidstThread.EDT)
	public void restart() {
		dispose();
		SwingUtilities.invokeLater(() -> {
			try {
				run();
			} catch (MinecraftInterfaceCreationException e) {
				throw new RuntimeException("Unexpected exception while restarting Amidst", e);
			}
		});
	}
}
