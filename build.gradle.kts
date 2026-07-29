import java.util.Properties

plugins {
    java
    application
}

val metadata = Properties().apply {
    file("src/main/resources/amidst/metadata.properties").inputStream().use(::load)
}
val distributionName = metadata.getProperty("amidst.build.filename")
    ?: error("amidst.build.filename is missing from metadata.properties")
val releaseSuffix = Regex("""-(v\d+)$""")
    .find(distributionName)
    ?.groupValues
    ?.get(1)
    ?: "dev"
val workerReleaseName = "amidst-gtnh-worker-$releaseSuffix.jar"

group = "amidst"
version = listOf(
    metadata.getProperty("amidst.version.major"),
    metadata.getProperty("amidst.version.minor"),
    metadata.getProperty("amidst.version.patch"),
).joinToString(".")

repositories {
    mavenLocal()
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven("https://jitpack.io")
        }
        filter {
            includeGroup("com.github.Querz")
        }
    }
}

val localNbtJar = providers.gradleProperty("localNbtJar")

dependencies {
    implementation("com.google.code.gson:gson:2.13.1")
    implementation("com.miglayout:miglayout-swing:4.2")
    implementation("args4j:args4j:2.37")
    if (localNbtJar.isPresent) {
        val nbtJar = file(localNbtJar.get())
        require(nbtJar.isFile) {
            "The localNbtJar property does not point to a file: $nbtJar"
        }
        implementation(files(nbtJar))
    } else {
        implementation("com.github.Querz:NBT:6.1")
    }

    testImplementation("junit:junit:4.13.1")
}

application {
    mainClass = "amidst.Amidst"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    archiveFileName = "$distributionName.jar"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes(
            "Main-Class" to application.mainClass.get(),
            "Amidst-Version" to distributionName,
        )
    }
    from({
        configurations.runtimeClasspath.get()
            .filter(File::isFile)
            .map(::zipTree)
    })
    exclude(
        "META-INF/*.SF",
        "META-INF/*.RSA",
        "META-INF/*.DSA",
    )
}

val buildWorker by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds and stages the standalone, reobfuscated Forge 1.7.10 worker mod."
    workingDir = file("gtnh-worker")

    val workerJavaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
    val workerArguments = mutableListOf(
        "stageReleaseJar",
        "-Pgtnh.modules.codeStyle=false",
    )
    if (gradle.startParameter.isOffline) {
        workerArguments += "--offline"
    }
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        commandLine(listOf("cmd", "/c", "gradlew.bat") + workerArguments)
    } else {
        commandLine(listOf("./gradlew") + workerArguments)
    }
    doFirst {
        environment(
            "JAVA_HOME",
            workerJavaLauncher.get().metadata.installationPath.asFile.absolutePath,
        )
    }
}

val workerJar = layout.projectDirectory.file(
    "gtnh-worker/build/release/amidst-gtnh-worker.jar",
)

val assembleRelease by tasks.registering(Sync::class) {
    group = "build"
    description = "Tests and packages matching Amidst and GTNH worker JARs."
    dependsOn(tasks.test, tasks.jar, buildWorker)
    into(layout.buildDirectory.dir("release"))
    from(tasks.jar.flatMap { it.archiveFile })
    from(workerJar) {
        rename { workerReleaseName }
    }
    doLast {
        logger.lifecycle("Release artifacts:")
        logger.lifecycle("  ${destinationDir.resolve("$distributionName.jar")}")
        logger.lifecycle("  ${destinationDir.resolve(workerReleaseName)}")
    }
}

tasks.register("release") {
    group = "build"
    description = "Alias for assembleRelease."
    dependsOn(assembleRelease)
}
