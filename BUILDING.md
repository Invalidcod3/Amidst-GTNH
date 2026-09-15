# Building Amidst-GTNH

The repository contains two deliverables:

- the Java 17 Amidst desktop application;
- the standalone Forge 1.7.10 worker mod in `gtnh-worker/`.

The root Gradle build is the canonical build. It compiles and tests Amidst,
invokes the worker's independent GTNH Gradle build, and stages matching JARs
under `build/release/`.

## Requirements

- JDK 21 or newer for the root build, plus an installed JDK 25 for the GTNH
  Gradle plugin. Gradle discovers JDK 25 as a toolchain and uses it only for
  the nested worker build.
- Network access on the first build so Gradle can download dependencies.

Minecraft 1.7.10 compatibility is handled by the GTNH build plugin. Do not run
the build itself on Java 8. The root build also uses Java's
`file.encoding=COMPAT` mode so Gradle can launch test workers correctly from
non-ASCII Windows paths.

## Commands

Windows:

```powershell
.\build.bat
```

Linux/macOS:

```sh
./build.sh
```

Standard Gradle commands are also available:

```powershell
.\gradlew.bat test
.\gradlew.bat jar
.\gradlew.bat buildWorker
.\gradlew.bat assembleRelease
```

`build.ps1` provides named `-Offline`, `-SkipTests`, and `-LocalNbtJar`
parameters on systems that permit PowerShell script execution. `build.bat`
accepts normal Gradle arguments and is not affected by PowerShell execution
policy.

Use `--offline` after all dependencies have been cached. Use `-x test` only for
local iteration; release builds should run the test suite.

For an air-gapped build, Querz NBT can be supplied as a local JAR without
changing the build file:

```powershell
.\build.bat --offline "-PlocalNbtJar=C:\path\to\NBT-6.1.jar"
```

Normal connected builds should omit this property and resolve the dependency
from JitPack.

## Versioning

The Amidst release filename is controlled by
`src/main/resources/amidst/metadata.properties`. A filename ending in `-vNN`
or `-v0.3` produces the matching worker filename, such as
`amidst-gtnh-worker-v0.3.jar`. `amidst.release.version` controls both Gradle
project versions. For v0.3.1 use `amidst-gtnh-biomes-v0.3.1`, release version
`0.3.1`, and display suffix `gtnh-biomes-0.3.1`. Keep the worker's `@Mod`
version in sync with it.
The Viewer retains the upstream Amidst version and appends the GTNH release
version in `amidst.version.preReleaseSuffix`.

After `assembleRelease`, package the release from the repository root using
PowerShell 7:

```powershell
./tools/Package-Release.ps1 -PackageSuffix rebuild-20260915
```

This creates `Amidst-GTNH-v0.3.1-rebuild-20260915.zip`, its matching source ZIP,
installation instructions, source manifest and SHA-256
checksums under `build/`. It checks test reports, JAR versions and ZIP contents.
Release notes are maintained in `docs/release-v0.3.1.md`. The source ZIP contains
the current working tree, including uncommitted, non-ignored files. Review
`git status` before packaging. Existing package outputs are never overwritten.
For later rebuilds choose a new descriptive suffix and update the current release
notes. The JAR release version stays 0.3.1; the suffix identifies the archive only.
The staging directory retains older versioned JARs and logs; packaging selects
only the current version. No Git tag or remote
release is created by this script.

The worker protocol number is intentionally declared on both sides:

- `src/main/java/amidst/gtnh/worker/GtnhBiomeSource.java`
- `gtnh-worker/src/main/java/amidst/gtnh/worker/BiomeWorkerServer.java`

Changing the wire format requires incrementing both constants and
`amidst.worker.protocol` in metadata, then releasing both JARs together.
`verifyReleaseContract` checks these values, release versions and mirrored
wire DTOs in both builds. It is required by `check` and `jar`; both JAR manifests
record the protocol and the packaging script checks them.

For reference-algorithm tests, supply RWG alpha 1.5.2 and GregTech 5.09.54.133:

```powershell
.\gradlew.bat assembleRelease "-PrwgReferenceJar=C:/reference/RWG-alpha-1.5.2.jar" "-PgregtechReferenceJar=C:/reference/gregtech-5.09.54.133.jar"
```

Without these optional inputs their comparison tests are skipped. The files
are not bundled with the source release. See [CONTRIBUTING.md](CONTRIBUTING.md)
for architecture, scoped formatting, regression tests and in-game acceptance.

## Worker-only development

The worker remains a normal standalone GTNH mod project:

Set `JAVA_HOME` to JDK 25 before running its wrapper directly. Using the root
`buildWorker` task selects that toolchain automatically.

After moving a top-level class between source files, Gradle's incremental Java
compiler may retain its old source association. If it reports that the moved
class is missing, run `cleanCompileJava stageReleaseJar` from the worker directory
with JDK 25, then run the root `assembleRelease` again. This cleans compiled
Worker classes only; it does not remove dependency caches or game data.

```powershell
cd gtnh-worker
.\gradlew.bat stageReleaseJar
```

Install `gtnh-worker/build/release/amidst-gtnh-worker.jar`. The stable staging
name avoids confusing the installable reobfuscated JAR with `-dev`, sources,
javadoc, or stale branch artifacts in `build/libs/`.

`stageReleaseJar` now runs the worker's network tests and verifies the actual
staged archive. It must match `reobfJar` and contain Minecraft runtime (SRG)
member references; the developer archive must fail that check. A successful
`reobfJar` task alone does not prove that the correct file was staged.
See [Worker troubleshooting](docs/archive/worker-troubleshooting.md) for the v25
packaging regression, source entry points and in-game verification steps.
