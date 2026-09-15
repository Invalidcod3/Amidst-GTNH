import java.util.Properties

// A task class avoids capturing this script in the Worker's configuration cache.
abstract class VerifyReleaseContract : DefaultTask() {
    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val repository = repositoryDirectory.get().asFile
        val metadata = Properties().apply {
            repository.resolve("src/main/resources/amidst/metadata.properties").inputStream().use(::load)
        }
        val version = metadata.getProperty("amidst.release.version")
        require(version != null && Regex("""\d+\.\d+\.\d+""").matches(version)) {
            "amidst.release.version must be a three-part version"
        }
        require(metadata.getProperty("amidst.build.filename") == "amidst-gtnh-biomes-v$version") {
            "Viewer filename must match release version $version"
        }
        require(metadata.getProperty("amidst.version.preReleaseSuffix") == "gtnh-biomes-$version") {
            "Viewer display version must match release version $version"
        }
        val modSource = repository.resolve(
            "gtnh-worker/src/main/java/amidst/gtnh/worker/AmidstGtnhBiomeWorkerMod.java",
        ).readText()
        val modVersion = Regex("""\bversion\s*=\s*"([^"]+)"""")
            .find(modSource)?.groupValues?.get(1)
        require(modVersion == version) { "Worker @Mod version $modVersion differs from $version" }
        val protocol = metadata.getProperty("amidst.worker.protocol")?.toIntOrNull()
        require(protocol != null && protocol > 0) { "amidst.worker.protocol must be a positive integer" }
        for (source in listOf(
            "src/main/java/amidst/gtnh/worker/GtnhBiomeSource.java",
            "gtnh-worker/src/main/java/amidst/gtnh/worker/BiomeWorkerServer.java",
        )) {
            val declared = Regex("""\bPROTOCOL_VERSION\s*=\s*(\d+)\s*;""")
                .find(repository.resolve(source).readText())?.groupValues?.get(1)?.toInt()
            require(declared == protocol) { "$source protocol $declared differs from metadata $protocol" }
        }
        for (source in listOf(
            "amidst/gtnh/prospecting/ProspectingData.java",
            "amidst/gtnh/validation/AccuracyReport.java",
        )) {
            val viewer = repository.resolve("src/main/java/$source").readText().replace("\r\n", "\n")
            val worker = repository.resolve("gtnh-worker/src/main/java/$source").readText().replace("\r\n", "\n")
            require(viewer == worker) { "Wire DTO differs between Viewer and Worker: $source" }
        }
        logger.lifecycle("Release contract verified: v$version, protocol $protocol, matching wire DTOs")
    }
}

// Shared by the independent Viewer and Forge builds. Keep DTOs Java 8 compatible.
val verifyReleaseContract by tasks.registering(VerifyReleaseContract::class) {
    group = "verification"
    description = "Checks matching wire DTOs, protocol constants and release metadata."
    repositoryDirectory.set(if (file("gtnh-worker").isDirectory) projectDir else projectDir.parentFile)
    // No outputs: inexpensive consistency checks intentionally run on every invocation.
}

tasks.named("check") { dependsOn(verifyReleaseContract) }
tasks.named("jar") { dependsOn(verifyReleaseContract) }
