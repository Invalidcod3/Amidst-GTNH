# Worker JAR verification

## Village selector audit

`AuditVillageSelectors.java` compares the current Worker selectors with method
instructions extracted from reference Village Names and Galacticraft runtime
JARs. It uses controlled biome/configuration inputs and a save-free replacement
for Minecraft's random-seed API. It does not initialize either full mod, load
chunks, place structures, or measure real-world building accuracy. See
[the village investigation](../../docs/reference/village-prediction.md).

Use JDK 17+ and ASM 9 (`asm`, `asm-tree`, `asm-commons`). Set the classpath to
those three library JARs; reference mod JARs are inputs, not runtime dependencies.
Run the Worker `classes` task first and use freshly compiled classes:

```powershell
$villageAsmClasspath = 'C:/reference/asm-9.8.jar;C:/reference/asm-tree-9.8.jar;C:/reference/asm-commons-9.8.jar'
New-Item -ItemType Directory -Force build/village-audit/classes | Out-Null
javac -cp $villageAsmClasspath -d build/village-audit/classes gtnh-worker/tools/AuditVillageSelectors.java
java -cp "build/village-audit/classes;gtnh-worker/build/classes/java/main;$villageAsmClasspath" AuditVillageSelectors gtnh-worker/build/classes/java/main C:/reference/VillageNames-4.5.18-GTNH.jar C:/reference/Galacticraft-3.4.33-GTNH.jar 40 10 0
```

Commands above run from the repository root. The last three arguments are
controlled median, spread and origin-radius values, defaulting to `40 10 0`
when omitted. They are not automatically read from a player's game config.
The diagnostic prints JAR hashes, Moon comparisons, marker-window checks, and
Overworld candidate differences. A mismatch fails the run. The Overworld audit
executes the actual Worker generator adapter, the native VN biome parser and
native selector with controlled fields; extra cases cover different spacing,
radius, empty lists, biome metadata and terrain/biome disagreement. It accepts other
SRG runtime JAR versions with the same selector/configuration interface; interface
changes require reviewing the adapter. This is a manual diagnostic, not a default
release check or proof that an arbitrary game/mod combination is supported.

## Runtime profiling

`Capture-Jfr.ps1` attaches a bounded JFR recording to a selected game or Viewer
PID. `JfrFlameReport.java` (JDK 21+) streams a recording into searchable offline
flame graphs and summary JSON. Native samples and overlapping blocking durations
are deliberately separated from Java execution samples. See
[the v36 live investigation](../../docs/archive/gtnh-v36-jfr-findings.md) for measurements,
interpretation and reproduction commands.

## Release verification

Run with JDK 17 or newer (the Gradle task uses the worker build's JDK 25):

```powershell
java tools/VerifyWorkerJar.java build/release/amidst-gtnh-worker.jar
```

`stageReleaseJar` runs this check automatically, together with the worker's
JUnit tests. The verifier uses only JDK APIs and reads class-file constant
pools. It does not start Minecraft or inspect strings used for reflection.

The build passes three paths: the staged JAR, the `reobfJar` output and the
developer `jar` output. It verifies that:

1. The staged JAR contains the worker entry point, the three paused-world hook
   classes, and both `FMLCorePlugin` / `FMLCorePluginContainsFMLMod` manifest entries.
2. Known Minecraft development member names (including `World.isRemote`)
   do not remain in field/method references.
3. The chunk-event handler references the runtime name `World.field_72995_K`.
4. The staged JAR is byte-for-byte identical to `reobfJar`.
5. The developer JAR fails the same check, preventing a repeat of the v25
   packaging regression even if someone renames that JAR.

This is a targeted packaging regression check, not an exhaustive proof of
compatibility with every mod. If the chunk-event handler is deliberately
rewritten to stop accessing `World.isRemote`, update the required runtime
reference in the verifier and retain a member reference that distinguishes
the developer and installable archives. Keep ordinary Java source names in
the worker; the build handles their conversion to SRG names.

Current versions and the protocol come from `src/main/resources/amidst/metadata.properties`;
`verifyReleaseContract` checks both modules against that metadata. Historical test
package versions and profiling results belong to [the archive](../../docs/archive/README.md).
