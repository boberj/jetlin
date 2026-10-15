// A standalone build, deliberately not included from the repository's root settings, so that this
// throwaway spike can never break `./gradlew build`. Run it with `../../gradlew` from this directory.
pluginManagement {
    repositories {
        // Maven Central rate-limits busy CI egress; Google's mirror of it serves the same artifacts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        // Maven Central rate-limits busy CI egress; Google's mirror of it serves the same artifacts.
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
    // Reuse the main build's version catalog, so the spike compiles with the same Kotlin and
    // kotlinx.serialization versions as the server it would talk to.
    versionCatalogs {
        create("libs") { from(files("../../gradle/libs.versions.toml")) }
    }
}

rootProject.name = "kmp-client-spike"

include(":typed", ":dynamic")
