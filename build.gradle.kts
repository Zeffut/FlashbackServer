plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.18"
    id("xyz.jpenilla.run-paper") version "3.0.2"
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
    paperweight.paperDevBundle("1.21.5-R0.1-SNAPSHOT")
    // Gson is provided by the Paper server at runtime — compile against it but don't bundle it.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.geysermc.mcprotocollib:protocol:1.21.5-1")
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}
val integrationTest by tasks.registering(Test::class) {
    useJUnitPlatform { includeTags("integration") }
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    dependsOn(tasks.reobfJar)
    systemProperty("flashback.plugin.jar", tasks.reobfJar.get().outputJar.get().asFile.absolutePath)
    shouldRunAfter(tasks.test)
}

tasks.processResources {
    // Single source of truth for the version: expand ${version} in plugin.yml from gradle.properties.
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.runServer {
    minecraftVersion("1.21.5")
}
