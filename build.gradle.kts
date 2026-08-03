// Root build: declares shared plugin versions only. Real work happens in the
// modules — :fabric (mod, client+server), and later :common / :paper.
plugins {
    kotlin("jvm") version "2.4.0" apply false
    id("fabric-loom") version "1.17-SNAPSHOT" apply false
    id("me.modmuss50.mod-publish-plugin") version "0.8.4" apply false
}

subprojects {
    group = property("maven_group") as String
    version = property("mod_version") as String
}

// One command, both server artifacts + the client-carrying Fabric jar, all
// stamped with the same version — the wire format is only guaranteed between
// artifacts of the same release.
tasks.register("assembleAll") {
    group = "build"
    description = "Tests and builds every Fabric version jar and the Paper plugin jar in lockstep"
    dependsOn(":common:check", ":fabric:chiseledBuild", ":paper:check", ":paper:shadowJar")
}
