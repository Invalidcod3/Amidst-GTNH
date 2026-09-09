import org.gradle.jvm.tasks.Jar
import java.util.Properties

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val releaseMetadata = Properties().apply {
    file("../src/main/resources/amidst/metadata.properties").inputStream().use(::load)
}
version = releaseMetadata.getProperty("amidst.release.version")
    ?: error("amidst.release.version is missing from metadata.properties")

// Both the early tick transformer and the ordinary @Mod must be discovered.
// Apply to reobfJar too; configuring only the developer JAR is insufficient.
// Do not use withType<Jar>: RFG also packages Minecraft/launcher helper JARs,
// which must not carry this mod's bootstrap metadata.
for (archiveTask in listOf("jar", "reobfJar")) {
    tasks.named<Jar>(archiveTask) {
        manifest.attributes(
            "FMLCorePlugin" to "amidst.gtnh.worker.core.WorkerLoadingPlugin",
            "FMLCorePluginContainsFMLMod" to "true",
            "Implementation-Version" to project.version.toString()
        )
    }
}

// RFG's `jar` is the MCP-named developer JAR. `reobfJar` is a separate
// archive with the SRG member names required by an installed Forge instance.
val runtimeJar = tasks.named<Jar>("reobfJar").flatMap { it.archiveFile }
val developerJar = tasks.named<Jar>("jar").flatMap { it.archiveFile }
val stagedJar = layout.buildDirectory.file("release/amidst-gtnh-worker.jar")

val copyReleaseJar by tasks.registering(Sync::class) {
    from(runtimeJar)
    into(stagedJar.map { it.asFile.parentFile })
    rename { "amidst-gtnh-worker.jar" }
}

val verifyReleaseJar by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks the staged JAR's Minecraft mappings and rejects the developer JAR."
    dependsOn(copyReleaseJar)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    // JDK source-file launch keeps this check independent of Minecraft and
    // external libraries. It can also be run manually; see tools/README.md.
    mainClass.set(layout.projectDirectory.file("tools/VerifyWorkerJar.java").asFile.absolutePath)
    args(stagedJar.get().asFile, runtimeJar.get().asFile, developerJar.get().asFile)
    inputs.files("tools/VerifyWorkerJar.java", stagedJar, runtimeJar, developerJar)
}

tasks.test {
    useJUnit()
    providers.gradleProperty("rwgReferenceJar").orNull?.let {
        inputs.file(it)
        systemProperty("rwg.referenceJar", file(it).absolutePath)
    }
    // Exercise the regression on JVMs that prefer ::1 for generic loopback.
    providers.gradleProperty("gregtechReferenceJar").orNull?.let {
        inputs.file(it)
        systemProperty("gregtech.referenceJar", file(it).absolutePath)
    }
    systemProperty("java.net.preferIPv6Addresses", "true")
}

val stageReleaseJar by tasks.registering {
    group = "build"
    description = "Tests, stages and verifies the installable reobfuscated mod."
    dependsOn(tasks.test, verifyReleaseJar)
}

tasks.register<JavaExec>("benchmarkSurface") {
    group = "verification"
    description = "Compares RWG blend/cache performance against the frozen v0.2 implementation."
    dependsOn(tasks.testClasses)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("amidst.gtnh.worker.SurfaceBiomeSamplerBenchmark")
    maxHeapSize = "512m"
    args(layout.buildDirectory.file("reports/performance/surface-biomes.json").get().asFile)
}

tasks.register<JavaExec>("benchmarkManagers") {
    group = "verification"
    description = "Compares native Ross 128b / Deep Dark final-layer point and paged sampling."
    dependsOn(tasks.testClasses)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("amidst.gtnh.worker.ManagerBiomeBenchmark")
    maxHeapSize = "768m"
    args(layout.buildDirectory.file("reports/performance/managers-v38.json").get().asFile)
}

tasks.register<JavaExec>("benchmarkReplay") {
    group = "verification"
    description = "Compares final biome replay against a supplied frozen developer JAR."
    dependsOn(tasks.testClasses)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("amidst.gtnh.worker.RwgReplayBenchmark")
    maxHeapSize = "768m"
    val baseline = providers.gradleProperty("baselineWorkerJar").orNull
    args(baseline?.let { file(it).absolutePath } ?: "",
        layout.buildDirectory.file("reports/performance/replay-v37.json").get().asFile)
}
