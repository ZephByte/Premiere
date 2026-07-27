import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

// Paper server plugin: the same wire protocol and shared core as the Fabric
// mod, over Bukkit API only — no NMS, no paperweight, so one jar should span
// MC versions until Paper breaks an API we actually use.
plugins {
    kotlin("jvm")
    id("com.gradleup.shadow") version "9.6.1"
    id("me.modmuss50.mod-publish-plugin")
}

base {
    archivesName.set("Premiere-Paper")
}

val targetJavaVersion = 25
java {
    toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:${project.property("paper_api_version")}")
    // The server jar bundles netty; plugins see it via classloader delegation,
    // but paper-api doesn't re-export it for compilation.
    compileOnly("io.netty:netty-buffer:4.1.118.Final")
    implementation(project(":common"))
    implementation(kotlin("stdlib"))
}

tasks.processResources {
    inputs.property("version", project.version)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.fromTarget(targetJavaVersion.toString()))
}

tasks.shadowJar {
    archiveClassifier.set("")
    // No relocation: Paper isolates plugin classloaders, and relocating
    // kotlin-stdlib breaks Kotlin metadata. Shading (over plugin.yml
    // `libraries:`) keeps startup deterministic and offline-safe.
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// --- Publishing: same Modrinth project as the Fabric mod, tagged with the
// paper loader; CurseForge's mod section can't host Paper plugins, so the
// plugin ships via Modrinth + the GitHub release only. ---

val modVersion = property("mod_version") as String
val supportedMc = (property("modrinth_versions") as String).split(",").map { it.trim() }

publishMods {
    file.set(tasks.shadowJar.flatMap { it.archiveFile })
    version.set("$modVersion+paper") // distinct from the fabric files within the shared project
    displayName.set("Premiere $modVersion (Paper)")
    changelog.set(providers.environmentVariable("CHANGELOG").orElse(""))
    type.set(
        when {
            modVersion.contains("-alpha") -> ALPHA
            modVersion.contains("-beta")  -> BETA
            else                          -> STABLE
        }
    )
    modLoaders.add("paper")
    dryRun.set(
        providers.environmentVariable("MODRINTH_TOKEN").getOrNull() == null ||
            providers.gradleProperty("modrinth_id").getOrElse("").isEmpty()
    )

    modrinth {
        accessToken.set(providers.environmentVariable("MODRINTH_TOKEN").orElse(""))
        projectId.set(providers.gradleProperty("modrinth_id").orElse(""))
        minecraftVersions.addAll(supportedMc)
        // No dependency pins: the plugin is Bukkit-API-only and self-contained.
    }
}

val runDir = layout.projectDirectory.dir("run")

val installPlugin = tasks.register<Copy>("installPlugin") {
    group = "run"
    description = "Copies the freshly built plugin jar into run/plugins"
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(runDir.dir("plugins"))
}

tasks.register<Exec>("runPaper") {
    group = "run"
    description = "Runs the Paper dev server (run/paper.jar) with the current plugin build installed"
    dependsOn(installPlugin)
    workingDir = runDir.asFile
    commandLine("java", "-jar", "paper.jar", "--nogui")
    standardInput = System.`in`
    doFirst {
        check(runDir.file("paper.jar").asFile.exists()) {
            "paper/run/paper.jar is missing. Fetch a build, e.g.:\n" +
                "  curl -sL -o paper/run/paper.jar " +
                "\"https://fill-data.papermc.io/v1/objects/<hash>/paper-<version>-<build>.jar\"\n" +
                "(look up the latest build's download url at " +
                "https://fill.papermc.io/v3/projects/paper/versions/<version>/builds/latest)"
        }
    }
}
