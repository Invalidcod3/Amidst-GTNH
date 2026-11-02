# Amidst GTNH Worker

Standalone Forge 1.7.10 mod, release **0.4.0**, protocol **31**.
Use the matching Viewer and restart Minecraft after replacing the Worker.
This formal release consolidates the internal RWG2 development builds;
see the [release notes](../docs/release-v0.4.0.md) for compatibility and installation.

## Responsibilities

| Module | Responsibility |
| --- | --- |
| `BiomeWorkerServer` | Loopback protocol, game-thread queue, request validation and dispatch |
| `OverworldGenerator` / `SurfaceBiomeSamplers` | Generator selection and runtime/independent samplers |
| `Rwg2Runtime` / `Rwg2ParallelSupport` | Native implementation fingerprints and parallel callback checks |
| `Rwg2SurfaceBiomeSampler` / `Rwg2SamplingPool` | Exact biome replay and exclusively leased background contexts |
| `Rwg2StructurePredictor` / `Rwg2PoiPool` | Selective landmarks and bounded background cave calculations |
| `StructureSelection` | Optional structure-type filtering before predictor execution |
| `StoredBiomeAccess` / `DisplayBiomeIds` | Extended biome storage and display identifiers |
| `SavedWorldSpawn` / `SpawnSearchCache` | Recorded spawn and resumable independent spawn searches |
| `MapCacheIdentity` / `MapCachePreparation` | Cache identity and background configuration fingerprints |
| `AccuracyValidation` | Independent predictions compared with existing evidence |
| `ProspectingService` / `AsteroidProspecting` | Runtime resource models and dimension-aware queries |
| `WorkerTickHooks` / `core/` | Game-thread scheduling while the integrated world is paused |

Implementation classes remain package-private; the wire protocol is the Viewer integration boundary.
Public core hooks exist only for injected Minecraft calls.

## Thread ownership and lifetime

- Real world access, mod initialization, general structures and resources use the game-thread queue.
  No preview query loads or generates world chunks. The game-tick budget is a soft limit:
  a running native callback cannot be safely preempted.
- Audited RWG2 2.0.6 final-biome replay runs in exclusively leased background contexts.
  Every context owns its manager, noise objects, RNGs, arrays and mutable caches.
  Registered callbacks are shared only after verification; unknown implementations retain the game-thread path.
- Detached landmarks and supported cave masks use at most four independent POI workers.
  Constructors and loaded-world evidence remain on the game thread. Unknown replacement cave generators use serial replay.
- A cancelled running context stays leased until its actual computation returns. Queued work can be removed immediately.
  Waiting queries park rather than repeatedly consuming game-thread budget; completion wakes them.
- Terrain and POI threads expire after five idle seconds. Context caches remain bounded for reuse.
  Terrain prepares at most two successors, POIs at most one; socket admission is also bounded.
- Configuration fingerprints use a daemon task. Pending or failed preparation yields no cache context and does not block terrain.
  Configuration changes require a game restart; changing saves must still invalidate loaded evidence.

## Generator, structure and cache inputs

Protocol 31 carries `generator`: `RWG2_CONTINENTS` (RWG2 Smooth) or `RWG` (RWG-Alpha).
Omitted values retain the legacy RWG meaning. Ordinary `RWG2` is not treated as continents mode.
Stored-biome evidence requires the same seed and world type; pure caches also distinguish noise mode.

`parallelism` comes from the Viewer setting. Terrain is capped at 64 and defaults to available processors minus two,
minimum one. Override that terrain ceiling with `-Dgtnh.amidst.worker.rwg2Threads=N` in the game JVM.
POIs are capped at the smaller of four, the requested concurrency, and available processors minus two (minimum one).
The socket pool admits 72 active requests; computation has its own smaller bounded pools.

Optional `structureTypes` filters overworld predictors. Omission means the legacy complete query; an empty list means no
standard structures. Ordinary LootGames, lava-cave games and river-chamber games are distinct selections.
Shared locators and RNG replay remain necessary when an enabled structure depends on them. Visibility never changes the
native decoration choice. The Viewer omits disabled vanilla spawner-dungeon queries entirely.

RWG2 final-biome sampling preserves the 4-block fine preview. Pure-ocean shortcuts check native prerequisites;
mixed terrain retains complete replay. Landmarks preserve native center finding, thresholds and owner chunks.
Matching loaded block evidence is refreshed per query. Arbitrary-seed spawn is estimated; matching recorded spawn wins.

See [RWG2 implementation boundaries](../docs/reference/rwg2-adaptation-plan.md),
[spawn prediction](../docs/reference/spawn-prediction.md) and the [maintenance guide](../CONTRIBUTING.md).

## Build and diagnostics

Use root `assembleRelease` for the paired release or root `buildWorker` for this mod.
For this directory's wrapper, set `JAVA_HOME` to JDK 25 and run:

```powershell
.\gradlew.bat stageReleaseJar
```

Install `build/release/amidst-gtnh-worker.jar`, not the developer `-dev` archive.
The staging task runs tests and verifies SRG mappings and the core-plugin manifest.
Reference RWG/RWG2/GregTech tests accept local JAR paths; see [BUILDING.md](../BUILDING.md)
and the [archive verifier](tools/README.md).

The Worker is enabled by default on first launch; an explicit disabled setting is preserved.
For connections and normal use start with the [usage guide](../docs/usage.md).
Detailed development-era benchmarks and earlier designs are retained in the
[RWG2 history](../docs/archive/rwg2-development-history.md), not as current configuration requirements.
