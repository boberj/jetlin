rootProject.name = "jetlin"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose's runtime depends on androidx.annotation and androidx.collection, and those are
        // published here and nowhere else. Scoped to the androidx groups so that every other
        // dependency in the build is still answered by Maven Central alone.
        google {
            content { includeGroupByRegex("androidx\\..*") }
        }
    }
}

include(
    ":jetlin-protocol",
    ":jetlin-runtime",
    ":jetlin-html",
    ":jetlin-server-ktor",
    ":jetlin-testing",
    ":samples:demo",
    ":conventions",
)
