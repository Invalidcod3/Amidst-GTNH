plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val stageReleaseJar by tasks.registering(Sync::class) {
    group = "build"
    description = "Stages the installable reobfuscated mod under a stable filename."
    dependsOn(tasks.named("reobfJar"))
    from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("release"))
    rename { "amidst-gtnh-worker.jar" }
}
