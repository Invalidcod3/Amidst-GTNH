# Amidst GTNH Worker

This directory is a standalone Forge 1.7.10 mod built with the GTNH Gradle
conventions.

The v0.3 release uses protocol 21 and adds ore/fluid prospecting, dimension
discovery and conditional coordinate export. See the
[v0.3 release notes](../docs/release-v0.3.md). Runtime mod version: 0.3.0.

The worker is enabled by default. On the first launch it writes
`worker.enabled=true` to `config/amidstgtnhworker.cfg` and starts during that
same launch; creating the configuration does not require another restart.
An existing explicit `enabled=false` or JVM override is still respected.

v38 batches final biome pages for Ross 128b / Deep Dark and applies Ross's actual
post-manager substitutions. See [dimension sampling and accuracy](../docs/gtnh-v38-dimensions.md).
v37 added paged RWG grid reuse, per-tick slack budgeting, and resumable, separately
requested Thaumcraft structures. See [the profiling-driven fix](../docs/gtnh-v37-performance.md).
It preserves v36's biome-only evaluation when the actual runtime surface callbacks do
not access the final biome array, and retains native chunk replay otherwise.
Overworld tiles resume across a shared 8 ms / 50 ms game-thread budget. See
[adaptive prediction and diagnostics](../docs/gtnh-v36-adaptive.md), including
fallback reasons, the full-replay switch and `tools/Profile-WorkerTiles.ps1`.

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
        │   ├── WorkerTickHooks.java
        │   ├── core/WorkerLoadingPlugin.java
        │   ├── core/IntegratedServerTickTransformer.java
        │   └── ...samplers and predictors
        └── resources/
            └── mcmod.info
```

The package deliberately keeps the protocol server, samplers, and predictors
package-private. This limits the public mod API and makes it clear that the
wire protocol is the integration boundary with Amidst.

`WorkerTickHooks` is public only because the coremod injects a call from
`IntegratedServer.tick()`. Forge 1.7.10 stops emitting server tick events while
an integrated world is paused; this return hook keeps queries on the server
thread without unpausing the world. Dedicated servers keep the Forge END event;
main-menu/remote-client queries keep the existing client tick path. The hook's
bootstrap and transformer are isolated under `core/`, with no new Mixin dependency.
See the v32 section of [runtime troubleshooting](../docs/worker-troubleshooting.md)
for method mappings, manifest requirements, failure logs, and regression tests.
Installing this update requires replacing the Worker JAR and restarting Minecraft.

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

The staging task also runs the worker's network tests and validates the final
JAR's Minecraft member mappings and paused-world core plugin manifest/classes.
`jar` produces a developer archive; `reobfJar` produces the installable one.
See [the verifier](tools/README.md)
and [runtime troubleshooting](../docs/worker-troubleshooting.md).

RWG surface sampling has a reproducible `benchmarkSurface` task and exact-output
regression tests against the pre-optimization implementation. See
[surface performance](../docs/gtnh-performance.md) for measurements, cache limits,
and opt-in per-request queue/compute timings that require no game restart.

v34 replays the registered RWG/BOP map-generation and surface callbacks for whole
chunks on disposable arrays, with bounded final-biome caching and no biome-name rules. The optional `-PrwgReferenceJar=...` test input runs method bytecode from
the actual RWG alpha 1.5.2 JAR. `tools/Compare-WorkerBiomes.ps1` compares predictions
against loaded chunks through the read-only `compare_biomes` command. See
[the GTNH call-chain analysis](../docs/gtnh-biome-accuracy.md) for evidence and limits.

v35 keeps the same native biome pipeline, adds immutable terrain-column templates, primitive bound terrain calls and a pure-prediction tile cache with live chunk overrides. See [v35 performance and refresh fixes](../docs/gtnh-v35-performance.md).
