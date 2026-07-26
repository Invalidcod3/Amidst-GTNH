package amidst.mojangapi;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.worker.GtnhBiomeCatalogProvider;
import amidst.gtnh.worker.GtnhMinecraftInterface;
import amidst.gtnh.worker.GtnhWorkerSettings;
import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.file.SaveGame;
import amidst.mojangapi.minecraftinterface.LoggingMinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterface;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceCreationException;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaceException;
import amidst.mojangapi.minecraftinterface.MinecraftInterfaces;
import amidst.mojangapi.minecraftinterface.RecognisedVersion;
import amidst.mojangapi.world.World;
import amidst.mojangapi.world.WorldBuilder;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.WorldSeed;
import amidst.mojangapi.world.WorldType;
import amidst.mojangapi.world.biome.BiomeColor;

@ThreadSafe
public class RunningLauncherProfile {
	public static RunningLauncherProfile from(WorldBuilder worldBuilder, LauncherProfile launcherProfile, Optional<WorldOptions> initialWorldOptions)
			throws MinecraftInterfaceCreationException {
		return new RunningLauncherProfile(
				worldBuilder,
				launcherProfile,
				new LoggingMinecraftInterface(MinecraftInterfaces.fromLocalProfile(launcherProfile)), initialWorldOptions);
	}

	public static RunningLauncherProfile fromGtnhWorker(
			WorldBuilder worldBuilder,
			LauncherProfile launcherProfile,
			Optional<WorldOptions> initialWorldOptions,
			GtnhWorkerSettings settings) throws MinecraftInterfaceCreationException {
		try {
			GtnhMinecraftInterface minecraftInterface = GtnhMinecraftInterface.connect(settings);
			Optional<WorldOptions> effectiveWorldOptions = initialWorldOptions.isPresent()
					? initialWorldOptions
					: Optional.of(new WorldOptions(
							WorldSeed.random(),
							WorldType.DEFAULT));
			return new RunningLauncherProfile(
					worldBuilder,
					launcherProfile,
					minecraftInterface,
					effectiveWorldOptions);
		} catch (MinecraftInterfaceException e) {
			throw new MinecraftInterfaceCreationException(
					"Unable to start the GTNH biome backend using worker "
							+ settings.host()
							+ ":"
							+ settings.port()
							+ ". Start GTNH with the worker enabled (no save needs to be loaded) and verify the port, token, "
							+ "and optional biome color file. "
							+ e.getMessage(),
					e);
		}
	}

	private final WorldBuilder worldBuilder;
	private final LauncherProfile launcherProfile;
	private final MinecraftInterface minecraftInterface;
	private final Optional<WorldOptions> initialWorldOptions;

	public RunningLauncherProfile(
			WorldBuilder worldBuilder,
			LauncherProfile launcherProfile,
			MinecraftInterface minecraftInterface,
			Optional<WorldOptions> initialWorldOptions) {
		this.worldBuilder = worldBuilder;
		this.launcherProfile = launcherProfile;
		this.minecraftInterface = minecraftInterface;
		this.initialWorldOptions = initialWorldOptions;
	}

	public LauncherProfile getLauncherProfile() {
		return launcherProfile;
	}

	public Optional<WorldOptions> getInitialWorldOptions() {
		return initialWorldOptions;
	}

	public RecognisedVersion getRecognisedVersion() {
		return minecraftInterface.getRecognisedVersion();
	}

	public RunningLauncherProfile createSilentPlayerlessCopy() {
		if (minecraftInterface instanceof GtnhBiomeCatalogProvider) {
			return new RunningLauncherProfile(
					WorldBuilder.createSilentPlayerless(),
					launcherProfile,
					minecraftInterface,
					initialWorldOptions);
		}
		try {
			return RunningLauncherProfile.from(WorldBuilder.createSilentPlayerless(), launcherProfile, null);
		} catch (MinecraftInterfaceCreationException e) {
			// This will not happen normally, because we already successfully
			// created the same LocalMinecraftInterface once before.
			throw new RuntimeException("exception while duplicating the RunningLauncherProfile", e);
		}
	}

	public Map<Integer, BiomeColor> getRuntimeBiomeColors() {
		if (!(minecraftInterface instanceof GtnhMinecraftInterface gtnh)) {
			return Collections.emptyMap();
		}
		return gtnh.getRuntimeBiomeColors();
	}

	public synchronized World createWorld(WorldOptions worldOptions)
			throws MinecraftInterfaceException {
		return worldBuilder.from(minecraftInterface, worldOptions);
	}

	public synchronized World createWorldFromSaveGame(SaveGame saveGame)
			throws IOException, MinecraftInterfaceException {
		return worldBuilder.fromSaveGame(minecraftInterface, saveGame);
	}
}
