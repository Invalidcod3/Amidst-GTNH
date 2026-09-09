package amidst;

import amidst.documentation.AmidstThread;
import amidst.documentation.CalledByAny;
import amidst.documentation.CalledOnlyBy;
import amidst.documentation.NotThreadSafe;
import amidst.gui.crash.CrashWindow;
import amidst.logging.AmidstLogger;
import amidst.logging.AmidstMessageBox;
import amidst.logging.FileLogger;
import amidst.logging.WindowsConsoleLauncher;
import amidst.mojangapi.file.DotMinecraftDirectoryNotFoundException;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.ParserProperties;

import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.prefs.Preferences;

/**
 * The entry point class to the Amidst application.
 */
@NotThreadSafe
public class Amidst {

	/**
	 * A list of icon images for windows.
	 */
	public static final List<BufferedImage> BUFFERED_IMAGES = List.of(
			ResourceLoader.getImage("/amidst/icon/amidst-16x16.png"),
			ResourceLoader.getImage("/amidst/icon/amidst-32x32.png"),
			ResourceLoader.getImage("/amidst/icon/amidst-48x48.png"),
			ResourceLoader.getImage("/amidst/icon/amidst-64x64.png"),
			ResourceLoader.getImage("/amidst/icon/amidst-128x128.png"),
			ResourceLoader.getImage("/amidst/icon/amidst-256x256.png")
	);

	/**
	 * The version of Amidst running.
	 */
	public static final AmidstVersion VERSION = AmidstVersion.from(ResourceLoader.getProperties("/amidst/metadata.properties"));

	/**
	 * The entry point to the Amidst application.
	 *
	 * @param args command line arguments (see the wiki)
	 */
	@CalledOnlyBy(AmidstThread.STARTUP)
	public static void main(String[] args) {
		Thread.setDefaultUncaughtExceptionHandler((thread, e) -> handleCrash(e, thread));

		// Parse CLI arguments
		CommandLineParameters parameters = new CommandLineParameters();
		CmdLineParser parser = new CmdLineParser(
				parameters,
				ParserProperties.defaults().withShowDefaults(false).withUsageWidth(120).withOptionSorter(null));

		try {
			parser.parseArgument(args);
		} catch (CmdLineException e) {
			System.out.println(VERSION.createLongVersionString());
			System.err.println(e.getMessage());
			parser.printUsage(System.out);
			System.exit(2);
		}

		String versionString = VERSION.createLongVersionString();

		// Printing the help guide prints and exits
		if (parameters.printHelp) {
			System.out.println(versionString);
			parser.printUsage(System.out);
			return;
		}

		// Printing the version prints and exits
		if (parameters.printVersion) {
			System.out.println(versionString);
			return;
		}

		// Relaunch javaw in a native Windows console before starting any backend.
		// Explicit java.exe launches retain their existing console or redirection.
		try {
			if (WindowsConsoleLauncher.relaunchIfNeeded(parameters.useGtnhWorker, args)) {
				return;
			}
		} catch (IOException e) {
			AmidstLogger.error(e);
			AmidstMessageBox.displayError("Unable to open the Windows console",
					e.getMessage() + "\nPlease start the Viewer using run-viewer.bat or java -jar instead.");
			return;
		}

		Path logFile = parameters.logFile;
		if (logFile == null && parameters.useGtnhWorker) {
			logFile = defaultLogFile();
		}
		if (logFile != null) {
			FileLogger fileLogger = new FileLogger(logFile);
			AmidstLogger.addListener("file", fileLogger);
			Runtime.getRuntime().addShutdownHook(new Thread(fileLogger::close, "viewer-log-flush"));
			AmidstLogger.info("Using log file: {}", logFile.toAbsolutePath());
		}

		// Log system information
		AmidstLogger.info(versionString);
		AmidstLogger.info("Current system time: " + new Timestamp(new Date().getTime()));
		AmidstLogger.info(createPropertyString("os.name"));
		AmidstLogger.info(createPropertyString("os.version"));
		AmidstLogger.info(createPropertyString("os.arch"));
		AmidstLogger.info(createPropertyString("java.version"));
		AmidstLogger.info(createPropertyString("java.vendor"));
		AmidstLogger.info(createPropertyString("sun.arch.data.model"));

		// Start application
		EventQueue.invokeLater(() -> {
			AmidstSettings settings = new AmidstSettings(Preferences.userNodeForPackage(Amidst.class));
			try {
				settings.lookAndFeel.get().tryApply();
				new Application(parameters, settings).run();
			} catch (DotMinecraftDirectoryNotFoundException e) {
				AmidstLogger.warn(e);
				AmidstMessageBox.displayError(
						"Please install Minecraft",
						"Amidst is not able to find your '.minecraft' directory, but it requires a working Minecraft installation.");
			} catch (MinecraftInterfaceCreationException e) {
				AmidstLogger.error(e);
				AmidstMessageBox.displayError("Unable to start world backend", e.getMessage());
			} catch (Exception e) {
				handleCrash(e, Thread.currentThread());
			}
		});
	}

	private static Path defaultLogFile() {
		try {
			Path location = Path.of(Amidst.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			Path directory = Files.isDirectory(location) ? Path.of("").toAbsolutePath() : location.getParent();
			Path candidate = directory.resolve("viewer.log");
			if (Files.exists(candidate) ? Files.isRegularFile(candidate) && Files.isWritable(candidate)
					: Files.isWritable(directory)) {
				return candidate;
			}
		} catch (Exception e) {
			AmidstLogger.warn(e, "Unable to locate the Viewer directory for logging");
		}
		return Path.of(System.getProperty("user.home"), "amidst-viewer.log");
	}

	private static String createPropertyString(String key) {
		StringBuilder b = new StringBuilder();
		b.append("System.getProperty(\"");
		b.append(key);
		b.append("\") == '");
		b.append(System.getProperty(key));
		b.append("'");
		return b.toString();
	}

	/**
	 * On an uncaught exception, this logs it and shows a new window.
	 *
	 * @param e      the uncaught exception
	 * @param thread the thread Amidst crashed on
	 */
	@CalledByAny
	private static void handleCrash(Throwable e, Thread thread) {
		String message = "Amidst has encounted an uncaught exception on the thread " + thread;
		try {
			AmidstLogger.crash(e, message);
			CrashWindow.showAfterCrash();
		} catch (Throwable t) {
			System.err.println("Amidst crashed!");
			System.err.println(message);
			e.printStackTrace();
		}
	}
}
