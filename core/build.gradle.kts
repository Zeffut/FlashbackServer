plugins {
    java
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
    // Core compiles against the Paper API only — NO NMS dev bundle here.
    compileOnly("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    // Netty is provided by the Paper server at runtime (used for raw packet capture). The plain
    // paper-api POM doesn't expose it transitively, so declare it compileOnly here.
    compileOnly("io.netty:netty-all:4.1.115.Final")
    // Gson is provided by the Paper server at runtime — compile against it but don't bundle it.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("io.papermc.paper:paper-api:1.21.5-R0.1-SNAPSHOT")
    testImplementation("io.netty:netty-all:4.1.115.Final")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.geysermc.mcprotocollib:protocol:1.21.5-1")
}

tasks.test {
    useJUnitPlatform { excludeTags("integration") }
}

tasks.processResources {
    // Single source of truth for the version: expand ${version} in plugin.yml from gradle.properties.
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}
