pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// The migration tooling is a Gradle plugin. A project can only apply a plugin that is already on the
// build's classpath, which a sibling subproject can't provide, so the plugin is an included build. This
// lets `:samples:teams` apply the real plugin instead of a copy of its logic.
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
