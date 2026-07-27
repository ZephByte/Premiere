import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.0"
    id("fabric-loom") version "1.17-SNAPSHOT"
    id("maven-publish")
}

version = project.property("mod_version") as String
group = project.property("maven_group") as String

base {
    archivesName.set(project.property("archives_base_name") as String)
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
}

loom {
    splitEnvironmentSourceSets()

    runs {
        named("client") {
            // Convenience for testing the full pipeline against a local runServer.
            programArgs("--quickPlayMultiplayer", "127.0.0.1:25565")
        }
    }

    mods {
        register("premiere") {
            sourceSet("main")
            sourceSet("client")
        }
    }
}

repositories {
    mavenCentral()
    maven("https://maven.maxhenkel.de/repository/public") {
        name = "henkelmax"
    }
}

// ffmpeg (via JavaCPP) is bundled so neither the rented server host nor players
// need a system-wide VLC/ffmpeg install. Natives are per-platform; add targets here
// if the player base needs more (e.g. linux-arm64).
val javacppVersion = "1.5.13"
val javacvVersion = "1.5.13"
val ffmpegVersion = "8.0.1-$javacppVersion"
val nativeTargets = listOf("windows-x86_64", "linux-x86_64", "macosx-x86_64", "macosx-arm64")

dependencies {
    // To change the versions see the gradle.properties file
    minecraft("com.mojang:minecraft:${project.property("minecraft_version")}")

    mappings("net.fabricmc:intermediary:0.0.0:v2")
    implementation("net.fabricmc:fabric-loader:${project.property("loader_version")}")
    // Workaround: this Loom snapshot leaves the loaderLibraries configuration
    // empty, so dev runs crash with "ASM not detected". Dev-only; real servers
    // get these from the Fabric installer. Keep in sync with the loader's
    // fabric-installer.json when bumping loader_version.
    localRuntime("org.ow2.asm:asm:9.10.1")
    localRuntime("org.ow2.asm:asm-analysis:9.10.1")
    localRuntime("org.ow2.asm:asm-commons:9.10.1")
    localRuntime("org.ow2.asm:asm-tree:9.10.1")
    localRuntime("org.ow2.asm:asm-util:9.10.1")
    localRuntime("net.fabricmc:sponge-mixin:0.17.3+mixin.0.8.7")
    localRuntime("io.github.llamalad7:mixinextras-fabric:0.5.4")
    implementation("net.fabricmc:fabric-language-kotlin:${project.property("kotlin_loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${project.property("fabric_version")}")

    // Provided by the Simple Voice Chat mod at runtime; only the "voicechat"
    // entrypoint ever touches these classes, so SVC stays optional.
    compileOnly("de.maxhenkel.voicechat:voicechat-api:2.6.20")
    // Provided by LuckPerms at runtime; guarded behind isModLoaded("luckperms").
    compileOnly("net.luckperms:api:5.5")

    // javacv is non-transitive on purpose: its pom drags in OpenCV and a dozen
    // capture libraries we never touch. We only need FFmpegFrameGrabber + Frame.
    implementation("org.bytedeco:javacv:$javacvVersion") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    include("org.bytedeco:javacv:$javacvVersion")
    include("org.bytedeco:javacpp:$javacppVersion")
    include("org.bytedeco:ffmpeg:$ffmpegVersion")
    nativeTargets.forEach { target ->
        runtimeOnly("org.bytedeco:javacpp:$javacppVersion:$target")
        runtimeOnly("org.bytedeco:ffmpeg:$ffmpegVersion:$target")
        include("org.bytedeco:javacpp:$javacppVersion:$target")
        include("org.bytedeco:ffmpeg:$ffmpegVersion:$target")
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", project.property("minecraft_version"))
    inputs.property("loader_version", project.property("loader_version"))
    filteringCharset = "UTF-8"

    filesMatching("fabric.mod.json") {
        expand(
            "version" to project.version,
            "minecraft_version" to project.property("minecraft_version") as String,
            "loader_version" to project.property("loader_version") as String,
            "kotlin_loader_version" to project.property("kotlin_loader_version") as String
        )
    }
}

tasks.withType<JavaCompile>().configureEach {
    // ensure that the encoding is set to UTF-8, no matter what the system default is
    // this fixes some edge cases with special characters not displaying correctly
    // see http://yodaconditions.net/blog/fix-for-java-file-encoding-problems-with-gradle.html
    // If Javadoc is generated, this must be specified in that task too.
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_${project.base.archivesName.get()}" }
    }
}

// configure the maven publication
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = project.property("archives_base_name") as String
            from(components["java"])
        }
    }

    // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
    repositories {
        // Add repositories to publish to here.
        // Notice: This block does NOT have the same function as the block in the top level.
        // The repositories here will be used for publishing your artifact, not for
        // retrieving dependencies.
    }
}
