# Amidst GTNH Worker

Standalone Forge 1.7.10 mod, release **0.3.1**, protocol **25**.
Install it with the matching Viewer and restart Minecraft. Current behavior and
compatibility are documented in the [release notes](../docs/release-v0.3.1.md).

## Responsibilities

| Module | Responsibility |
| --- | --- |
| `BiomeWorkerServer` | Loopback protocol, game-thread queue and request dispatch |
| `SurfaceBiomeSampler` / `SurfaceBiomeSamplers` | Query interface / runtime and independent RWG implementations |
| `RwgChunkBiomeReplay` / `RwgSpawnCaves` | Disposable terrain arrays, surface replacement and spawn cave stage |
| `SavedWorldSpawn` / `SpawnSearch` / `SpawnSearchCache` | Recorded spawn, provider rules, resumable seed searches |
| `MapCachePreparation` / `MapCacheIdentity` | Background configuration fingerprint and cache identity |
| `AccuracyValidation` | Independent predictions compared with existing evidence |
| `AsteroidProspecting` / `ProspectingService` | Runtime ore models and dimension-aware resource queries |
| `WorkerTickHooks` / `core/` | Game-thread scheduling while the integrated world is paused |

Worker implementation classes are package-private; the wire protocol is the Viewer
integration boundary. Public core hooks exist only for injected Minecraft calls.

## Runtime boundaries

- World access and native generation callbacks run through the game-thread queue.
  The tick budget is a soft limit: a single callback cannot be interrupted safely.
- Configuration fingerprint preparation runs on a daemon thread. Pending or failed
  preparation returns no cache context; it must not block biome queries.
- Independent prediction uses save-free worlds and scratch arrays. Validation does
  not load or generate chunks. Missing evidence remains `UNVERIFIED`.
- Saved spawn data bypasses estimation caches. Configuration changes require a game
  restart; same-seed world changes must still refresh live data.

See the [maintenance guide](../CONTRIBUTING.md) for source navigation, cache lifecycle,
tests and release checks, and [spawn prediction](../docs/reference/spawn-prediction.md)
for the generation stages and known accuracy limit.

## Build and diagnostics

Use the root `assembleRelease` task for paired JARs, or root `buildWorker` for the mod.
For this directory's wrapper, set `JAVA_HOME` to JDK 25 first:

```powershell
.\gradlew.bat stageReleaseJar
```

Install `build/release/amidst-gtnh-worker.jar`. The developer `-dev` archive is not
installable. The staging task runs tests and verifies SRG mappings and the core
plugin manifest. See [BUILDING.md](../BUILDING.md) and the [verifier](tools/README.md).

The Worker is enabled by default on first launch; an explicit disabled setting is
preserved. For connection problems start with the [usage guide](../docs/usage.md).
Original profiling and runtime investigations are retained in the
[historical archive](../docs/archive/README.md).
