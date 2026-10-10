# Worker JAR verification

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

The test package has a separate version suffix. The current scripts use protocol `19`; update both Viewer and Worker for v39:
v37 adds the optional overworld `structures` request field `structureGroup`,
with `standard` and `thaumcraft` values. Omission retains the combined response.
Older workers ignore the field, so both sides should be updated for the speed benefit.
