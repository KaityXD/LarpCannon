plugins {
    kotlin("jvm") version "1.9.22"
    id("com.gradleup.shadow") version "9.0.0-beta12"
}

group = "com.orbital.larpcannon"
version = "1.6.1"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    // Paper API for modern minecraft features
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
    
    // Shading Kotlin stdlib since Spigot/Paper might not include it
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.shadowJar {
    archiveBaseName.set("OrbitalLarpCannon")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
