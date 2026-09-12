pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The migration tooling is a Gradle plugin, and a plugin has to be on the build's own classpath before a
// project can apply it — which a sibling subproject cannot be. An included build is the way round that,
// and it means `:samples:teams` applies the real plugin rather than a copy of what it does.
includeBuild("jetlin-db-gradle")

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
    ":jetlin-db",
    ":jetlin-db-ksp",
    ":jetlin-server-ktor",
    ":jetlin-testing",
    ":samples:demo",
    ":samples:teams",
    ":conventions",
)
