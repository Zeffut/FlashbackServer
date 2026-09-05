plugins {
    java
    // Declared here so subprojects can apply them; not applied to the root itself.
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.22" apply false
    id("xyz.jpenilla.run-paper") version "3.0.2"
    // Shadow assembles the single bundled plugin jar.
    id("com.gradleup.shadow") version "8.3.6"
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.opencollab.dev/maven-releases/")
}

dependencies {
    // Bundle :core compiled classes + resources (plugin.yml/config.yml) into the shaded jar.
    implementation(project(":core"))
}

// ── Bundled plugin jar ─────────────────────────────────────────────────────
// The final deployable jar = :core classes + resources + the REOBF'd :nms:v1_21_5 adapter classes.
// Paper's PluginRemapper remaps the (mojang→spigot) nms references in the reobf classes at load.
// Ensure the nms subprojects are configured first so their paperweight `reobfJar` tasks exist.
val nmsVersions = listOf("5", "6", "7", "8", "9", "10", "11")
nmsVersions.forEach { v -> evaluationDependsOn(":nms:v1_21_$v") }
val nmsReobfJars = nmsVersions.associateWith { v ->
    project(":nms:v1_21_$v").tasks.named("reobfJar")
}

tasks.shadowJar {
    archiveClassifier.set("")
    // Pull in each version adapter's remapped NMS classes (not the mojang-mapped ones).
    nmsReobfJars.forEach { (v, reobfJar) ->
        dependsOn(reobfJar)
        from(zipTree(reobfJar.map { it.outputs.files.singleFile })) {
            include("dev/zeffut/flashbackserver/version/v1_21_$v/**")
        }
    }
    // Paper 26.1+ runs Mojang-mapped plugins, so its adapter must not be reobfuscated.
    val v26_2Jar = project(":nms:v26_2").tasks.named<Jar>("jar")
    dependsOn(v26_2Jar)
    from(zipTree(v26_2Jar.map { it.outputs.files.singleFile })) {
        include("dev/zeffut/flashbackserver/version/v26_2/**")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

// ── Integration tests ──────────────────────────────────────────────────────
// The harness (in :core test) deploys the BUNDLED root jar via the flashback.plugin.jar property.
val integrationTest by tasks.registering(Test::class) {
    val coreTest = project(":core").extensions
        .getByType<SourceSetContainer>()["test"]
    useJUnitPlatform { includeTags("integration") }
    testClassesDirs = coreTest.output.classesDirs
    classpath = coreTest.runtimeClasspath
    dependsOn(tasks.shadowJar)
    systemProperty(
        "flashback.plugin.jar",
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath
    )
    shouldRunAfter(project(":core").tasks.named("test"))
}

// Focused real-server smoke for the Java-25 Paper 26.2 adapter. This avoids
// conflating it with the Java-21 regression suite while running the exact
// deployed shadow jar, booting the server, asserting plugin enablement, and
// performing its controlled shutdown.
val paper26_2Smoke by tasks.registering(Test::class) {
    val coreTest = project(":core").extensions
        .getByType<SourceSetContainer>()["test"]
    useJUnitPlatform { includeTags("integration") }
    filter { includeTestsMatching("dev.zeffut.flashbackserver.harness.Paper26_2SmokeIT") }
    testClassesDirs = coreTest.output.classesDirs
    classpath = coreTest.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    dependsOn(tasks.shadowJar)
    systemProperty(
        "flashback.plugin.jar",
        tasks.shadowJar.get().archiveFile.get().asFile.absolutePath
    )
    shouldRunAfter(project(":core").tasks.named("test"))
}

tasks.runServer {
    minecraftVersion("1.21.5")
    // Run the bundled jar so manual testing matches the deployed artifact.
    pluginJars(tasks.shadowJar.flatMap { it.archiveFile })
}
