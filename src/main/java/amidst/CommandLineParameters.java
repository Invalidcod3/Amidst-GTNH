package amidst;

import java.nio.file.Path;
import java.util.Optional;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.OptionDef;
import org.kohsuke.args4j.spi.OptionHandler;
import org.kohsuke.args4j.spi.Parameters;
import org.kohsuke.args4j.spi.Setter;

import amidst.documentation.ThreadSafe;
import amidst.gtnh.worker.GtnhWorkerSettings;
import amidst.mojangapi.file.LauncherProfile;
import amidst.mojangapi.file.MinecraftInstallation;
import amidst.mojangapi.world.WorldOptions;
import amidst.mojangapi.world.WorldSeed;
import amidst.mojangapi.world.WorldType;

/**
 * An instance of this class will be created to hold the command line
 * parameters. Afterwards, the assigned values should not be modified.
 */
@ThreadSafe
public class CommandLineParameters {
	// @formatter:off
	@Option(
	    name = "-mcpath",
	    usage = "location of the '.minecraft' directory.",
	    metaVar = "<directory>"
	)
	public volatile Path dotMinecraftDirectory;

	@Option(
	    name = "-mcjar",
	    usage = "location of the minecraft jar file",
	    metaVar = "<file>",
	    depends = { "-mcjson" },
	    forbids = { "-profile" }
	)
	public volatile Path minecraftJarFile;

	@Option(
	    name = "-mcjson",
	    usage = "location of the minecraft json file",
	    metaVar = "<file>",
	    depends = { "-mcjar" },
	    forbids = { "-profile" }
	)
	public volatile Path minecraftJsonFile;

	@Option(
	    name = "-profile",
	    usage = "name of profile to select",
	    metaVar = "<name>",
	    forbids = { "-mcjar", "-mcjson" }
	)
    public volatile String profileName;

	@Option(
	    name = "-biome-profiles",
	    usage = "location of the biome profile directory",
	    metaVar = "<directory>"
	)
	public volatile Path biomeProfilesDirectory;

	@Option(
	    name = "-history",
	    usage = "location of the seed history file",
	    metaVar = "<file>"
	)
	public volatile Path seedHistoryFile;

	@Option(
	    name = "-log",
	    usage = "location of the log file",
	    metaVar = "<file>"
	)
	public volatile Path logFile;

	@Option(
	    name = "-seed",
	    handler = SeedHandler.class,
	    usage = "initial seed to use",
        metaVar = "<string>"
	)
	public volatile WorldSeed initialSeed;

	@Option(
	    name = "-world-type",
	    handler = WorldTypeHandler.class,
	    usage = "world type for the initial seed",
	    metaVar = "<string>",
	    depends = { "-seed" }
	)
	public volatile WorldType initialWorldType;

	@Option(
	    name = "-help",
	    usage = "print usage information"
	)
	public volatile boolean printHelp;

	@Option(
	    name = "-version",
	    usage = "print version"
	)
	public volatile boolean printVersion;

	@Option(
	    name = "-gtnh-worker",
	    usage = "use a running GTNH biome worker instead of loading the selected Minecraft jar"
	)
	public volatile boolean useGtnhWorker;

	@Option(
	    name = "-gtnh-worker-host",
	    usage = "GTNH biome worker host (loopback by default)",
	    metaVar = "<host>",
	    depends = { "-gtnh-worker" }
	)
	public volatile String gtnhWorkerHost = "127.0.0.1";

	@Option(
	    name = "-gtnh-worker-port",
	    usage = "GTNH biome worker TCP port",
	    metaVar = "<port>",
	    depends = { "-gtnh-worker" }
	)
	public volatile int gtnhWorkerPort = 47117;

	@Option(
	    name = "-gtnh-worker-token",
	    usage = "shared token configured in the GTNH biome worker",
	    metaVar = "<token>",
	    depends = { "-gtnh-worker" }
	)
	public volatile String gtnhWorkerToken = "";

	@Option(
	    name = "-gtnh-colors",
	    usage = "optional GTNH biome color override JSON file",
	    metaVar = "<file>",
	    depends = { "-gtnh-worker" }
	)
	public volatile Path gtnhBiomeColorsFile;
	// @formatter:on

	public Optional<WorldOptions> getInitialWorldOptions() {
	    if (initialSeed == null) {
	        return Optional.empty();
	    }
	    return Optional.of(new WorldOptions(initialSeed, initialWorldType != null ? initialWorldType : WorldType.DEFAULT));
	}

	public Optional<LauncherProfile> getInitialLauncherProfile(MinecraftInstallation minecraftInstallation) {
	    if (profileName != null) {
	        return minecraftInstallation.tryGetLauncherProfileFromName(profileName);
	    }
	    if (minecraftJarFile != null) {
	        return minecraftInstallation.tryReadLauncherProfile(minecraftJarFile, minecraftJsonFile);
	    }
	    if (useGtnhWorker) {
	        return Optional.of(minecraftInstallation.createGtnhWorkerProfile());
	    }
	    return Optional.empty();
	}

	public Optional<GtnhWorkerSettings> getGtnhWorkerSettings() {
		if (!useGtnhWorker) {
			return Optional.empty();
		}
		return Optional.of(new GtnhWorkerSettings(
				gtnhWorkerHost,
				gtnhWorkerPort,
				gtnhWorkerToken,
				gtnhBiomeColorsFile));
	}

	public static class SeedHandler extends OptionHandler<WorldSeed> {
	    public SeedHandler(CmdLineParser cmdLineParser, OptionDef optionDef, Setter<WorldSeed> setter) {
	        super(cmdLineParser, optionDef, setter);
	    }

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            WorldSeed seed = WorldSeed.fromUserInput(params.getParameter(0));
            setter.addValue(seed);
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "STRING";
        }
	}

   public static class WorldTypeHandler extends OptionHandler<WorldType> {
        public WorldTypeHandler(CmdLineParser cmdLineParser, OptionDef optionDef, Setter<WorldType> setter) {
            super(cmdLineParser, optionDef, setter);
        }

        @Override
        public int parseArguments(Parameters params) throws CmdLineException {
            String param = params.getParameter(0);
            WorldType type = WorldType.findInstance(param);
            if(type == null) {
                throw new CmdLineException(owner, "Invalid WorldType: '" + param + "'", null);
            }
            setter.addValue(type);
            return 1;
        }

        @Override
        public String getDefaultMetaVariable() {
            return "STRING";
        }
    }
}
