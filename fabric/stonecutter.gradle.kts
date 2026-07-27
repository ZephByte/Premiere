plugins {
    id("dev.kikugie.stonecutter")
}
stonecutter active "26.2" /* [SC] DO NOT EDIT */

// Aggregates the task across every versioned subproject (fabric/versions/*).
// Since stonecutter 0.7 each version processes its own sources at build time,
// so plain per-version tasks are safe regardless of which version is active.
tasks.register("chiseledBuild") {
    group = "project"
    description = "Builds every Fabric MC version"
    dependsOn(stonecutter.tasks.named("build"))
}

tasks.register("chiseledPublishMods") {
    group = "publishing"
    description = "Publishes every Fabric MC version to Modrinth/CurseForge"
    dependsOn(stonecutter.tasks.named("publishMods"))
}
