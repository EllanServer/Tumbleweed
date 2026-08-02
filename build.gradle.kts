plugins {
    java
}

group = "net.tumbleweed"
version = "1.0.0"
description = "Tumbleweed - Paper plugin rewrite of the Tumbleweed mod, powered by MythicMobs & ModelEngine"

repositories {
    mavenCentral()
    // Paper API
    maven("https://repo.papermc.io/repository/maven-public/")
    // MythicMobs & ModelEngine (MythicCraft / Lumine official maven)
    maven("https://mvn.lumine.io/repository/maven-public/")
}

dependencies {
    // Paper 26.2 API (provides org.joml transitively)
    compileOnly("io.papermc.paper:paper-api:26.2.build.87-stable")

    // MythicMobs 5.9.2 (runtime dist jar used as compile-only API)
    compileOnly("io.lumine:Mythic-Dist:5.9.2-SNAPSHOT")

    // ModelEngine 4 (R4.1.1) - official artifact on Lumine maven
    compileOnly("com.ticxo.modelengine:ModelEngine:R4.1.1")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.jar {
    archiveBaseName.set("Tumbleweed")
    archiveVersion.set(project.version.toString())
    from("LICENSE") { into("META-INF") }
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
