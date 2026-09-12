pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
        google {
            content { includeGroupByRegex("androidx\\..*") }
        }
    }
}

rootProject.name = "jetlin"

include(
    ":jetlin-protocol",
    ":jetlin-runtime",
    ":jetlin-html",
    ":jetlin-server-ktor",
    ":jetlin-testing",
    ":samples:demo",
    ":conventions",
)
