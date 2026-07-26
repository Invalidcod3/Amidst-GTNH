package amidst.mojangapi;

import java.util.Optional;

import amidst.documentation.Immutable;
import amidst.gtnh.worker.GtnhWorkerSettings;
import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.mojangapi.world.WorldBuilder;
import amidst.mojangapi.world.WorldOptions;

@Immutable
public class LauncherProfileRunner {
	private final WorldBuilder worldBuilder;
	private Optional<WorldOptions> initialWorldOptions;
	private final Optional<GtnhWorkerSettings> gtnhWorkerSettings;

	public LauncherProfileRunner(WorldBuilder worldBuilder, Optional<WorldOptions> initialWorldOptions) {
		this(worldBuilder, initialWorldOptions, Optional.empty());
	}

	public LauncherProfileRunner(
			WorldBuilder worldBuilder,
			Optional<WorldOptions> initialWorldOptions,
			Optional<GtnhWorkerSettings> gtnhWorkerSettings) {
		this.worldBuilder = worldBuilder;
		this.initialWorldOptions = initialWorldOptions;
		this.gtnhWorkerSettings = gtnhWorkerSettings;
	}

	public RunningLauncherProfile run(LauncherProfile launcherProfile) throws MinecraftInterfaceCreationException {
		if (gtnhWorkerSettings.isPresent()) {
			return RunningLauncherProfile.fromGtnhWorker(
					worldBuilder,
					launcherProfile,
					initialWorldOptions,
					gtnhWorkerSettings.get());
		}
		return RunningLauncherProfile.from(worldBuilder, launcherProfile, initialWorldOptions);
	}

	public boolean isGtnhWorkerEnabled() {
		return gtnhWorkerSettings.isPresent();
	}
}
