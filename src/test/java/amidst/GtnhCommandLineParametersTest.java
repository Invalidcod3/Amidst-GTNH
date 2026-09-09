package amidst;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.Test;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.file.MinecraftInstallation;
import amidst.mojangapi.file.directory.DotMinecraftDirectory;

public class GtnhCommandLineParametersTest {
	@Test
	public void noArgumentsStartWorkerModeWithoutLauncherMetadata() throws Exception {
		Path instance = Path.of("test-gtnh-instance");
		MinecraftInstallation installation = new MinecraftInstallation(new DotMinecraftDirectory(instance));
		CommandLineParameters parameters = parse();

		Optional<LauncherProfile> result = parameters.getInitialLauncherProfile(installation);

		assertTrue(result.isPresent());
		assertEquals("GT New Horizons (worker)", result.get().getProfileName());
		assertEquals("GTNH/RWG", result.get().getVersionName());
		assertEquals(instance.resolve("saves"), result.get().getSaves());
		assertTrue(parameters.getGtnhWorkerSettings().isPresent());
		assertEquals(47117, parameters.getGtnhWorkerSettings().get().port());
	}

	@Test
	public void vanillaModeRequiresAnExplicitOption() throws Exception {
		CommandLineParameters parameters = parse("-vanilla");
		assertFalse(parameters.useGtnhWorker);
		assertTrue(parameters.getGtnhWorkerSettings().isEmpty());
		MinecraftInstallation installation = new MinecraftInstallation(
				new DotMinecraftDirectory(Path.of("test-vanilla-instance")));
		assertTrue(parameters.getInitialLauncherProfile(installation).isEmpty());
	}

	@Test
	public void workerConnectionOptionsDoNotRequireTheOldModeFlag() throws Exception {
		CommandLineParameters parameters = parse(
				"-gtnh-worker-host", "localhost", "-gtnh-worker-port", "48118",
				"-gtnh-worker-token", "test-token", "-gtnh-colors", "colors.json");
		var settings = parameters.getGtnhWorkerSettings().orElseThrow();
		assertEquals("localhost", settings.host());
		assertEquals(48118, settings.port());
		assertEquals("test-token", settings.token());
		assertEquals(Path.of("colors.json"), settings.biomeColorsFile());
	}

	@Test
	public void oldWorkerLaunchScriptsStillWork() throws Exception {
		assertTrue(parse("-gtnh-worker", "-seed", "123").getGtnhWorkerSettings().isPresent());
	}

	@Test
	public void contradictoryModeOptionsAreRejectedInEitherOrder() {
		assertThrows(CmdLineException.class, () -> parse("-vanilla", "-gtnh-worker"));
		assertThrows(CmdLineException.class, () -> parse("-gtnh-worker", "-vanilla"));
		assertThrows(CmdLineException.class, () -> parse("-vanilla", "-gtnh-worker-port", "48118"));
		assertThrows(CmdLineException.class, () -> parse("-gtnh-worker-port", "48118", "-vanilla"));
	}

	@Test
	public void minecraftProfileSelectionRequiresVanillaMode() throws Exception {
		assertThrows(CmdLineException.class, () -> parse("-profile", "test-profile"));
		assertThrows(CmdLineException.class, () -> parse("-mcjar", "test.jar", "-mcjson", "test.json"));
		CommandLineParameters parameters = parse("-vanilla", "-profile", "test-profile");
		assertEquals("test-profile", parameters.profileName);
		assertTrue(parameters.getGtnhWorkerSettings().isEmpty());
		assertTrue(parse("-vanilla", "-mcjar", "test.jar", "-mcjson", "test.json")
				.getGtnhWorkerSettings().isEmpty());
	}

	private static CommandLineParameters parse(String... args) throws CmdLineException {
		CommandLineParameters parameters = new CommandLineParameters();
		new CmdLineParser(parameters).parseArgument(args);
		return parameters;
	}
}
