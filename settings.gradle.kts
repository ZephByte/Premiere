pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/") {
            name = "Fabric"
        }
        maven("https://maven.kikugie.dev/releases") {
            name = "KikuGie"
        }
        gradlePluginPortal()
    }
}

plugins {
    // Auto-provisions the JDK required by the toolchain.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("dev.kikugie.stonecutter") version "0.9.7"
}

rootProject.name = "Premiere"

include("common")
include("paper")

// :fabric is the only MC-version-sensitive module; stonecutter turns it into a
// controller with one subproject per MC version (it includes :fabric itself —
// no include("fabric") here). Adding a version later: append it to versions(),
// create fabric/versions/<v>/gradle.properties, extend modrinth_versions.
stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"
    create(":fabric") {
        versions("26.2")
        vcsVersion = "26.2"
    }
}
