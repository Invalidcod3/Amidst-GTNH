# Amidst GTNH Worker

This directory is a standalone Forge 1.7.10 mod built with the GTNH Gradle
conventions.

## Layout

```text
gtnh-worker/
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
└── src/
    └── main/
        ├── java/amidst/gtnh/worker/
        │   ├── AmidstGtnhBiomeWorkerMod.java
        │   ├── BiomeWorkerServer.java
        │   ├── WorkerConfig.java
        │   └── ...samplers and predictors
        └── resources/
            └── mcmod.info
```

The package deliberately keeps the protocol server, samplers, and predictors
package-private. This limits the public mod API and makes it clear that the
wire protocol is the integration boundary with Amidst.

## Build

```powershell
.\gradlew.bat assemble
```

The versioned raw artifacts are written to `build/libs/`. To avoid accidentally
selecting a `-dev` JAR or a stale artifact from another branch, use the stable
staging task:

```powershell
.\gradlew.bat stageReleaseJar
```

It writes the installable reobfuscated mod to
`build/release/amidst-gtnh-worker.jar`. The mod is compiled for Minecraft
1.7.10 compatibility even though the Gradle build itself requires JDK 25.

For a matched Amidst and worker release, run the root repository's
`assembleRelease` task instead.
