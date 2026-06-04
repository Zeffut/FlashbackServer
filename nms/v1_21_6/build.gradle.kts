plugins {
    java
    id("io.papermc.paperweight.userdev")
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
    // Full NMS dev bundle — only this module sees net.minecraft / craftbukkit.
    paperweight.paperDevBundle("1.21.6-R0.1-SNAPSHOT")
    // The VersionAdapter interface + format.ReplayAction live in :core.
    implementation(project(":core"))
}
