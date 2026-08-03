import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Pure-JVM shared core. Hard rule: zero net.minecraft / net.fabricmc / org.bukkit
// imports — Fabric remaps MC to intermediary at runtime while Paper runs mojmap,
// so any MC reference here would break on one platform or the other.
plugins {
    kotlin("jvm")
}

base {
    // Loom nests this jar in the Fabric mod with a generated fabric.mod.json
    // whose id derives from the archive name — keep it distinct from "premiere".
    archivesName.set("premiere-common")
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
}

repositories {
    mavenCentral()
}

dependencies {
    // All compileOnly: both platforms already ship these at runtime
    // (gson + slf4j + netty are bundled by Minecraft/Fabric and Paper alike).
    compileOnly("com.google.code.gson:gson:2.11.0")
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    compileOnly("io.netty:netty-buffer:4.1.118.Final")

    testImplementation(kotlin("test-junit5"))
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("io.netty:netty-buffer:4.1.118.Final")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.test {
    useJUnitPlatform()
}
