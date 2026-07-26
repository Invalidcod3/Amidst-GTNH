package amidst;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.Test;

import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.file.MinecraftInstallation;
import amidst.mojangapi.file.directory.DotMinecraftDirectory;

public class GtnhCommandLineParametersTest {
	@Test
	public void workerModeCreatesAProfileWithoutLauncherMetadata() {
		Path instance = Path.of("test-gtnh-instance");
		MinecraftInstallation installation = new MinecraftInstallation(new DotMinecraftDirectory(instance));
		CommandLineParameters parameters = new CommandLineParameters();
		parameters.useGtnhWorker = true;

		Optional<LauncherProfile> result = parameters.getInitialLauncherProfile(installation);

		assertTrue(result.isPresent());
		assertEquals("GT New Horizons (worker)", result.get().getProfileName());
		assertEquals("GTNH/RWG", result.get().getVersionName());
		assertEquals(instance.resolve("saves"), result.get().getSaves());
	}
}
