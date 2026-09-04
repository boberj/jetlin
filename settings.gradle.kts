// pluginManagement has to come before anything else Gradle evaluates, including the toolchain
// resolver plugin declared below it.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Lets Gradle fetch the JDK the build asks for instead of requiring one to be installed.
//
// `jvmToolchain(21)` is a requirement, not a preference: without this a machine carrying only a JRE,
// or only some other major version, fails at configuration time with a message about toolchain
// download repositories rather than anything to do with this project. With it, the first build on a
// new machine downloads a JDK 21 and caches it under ~/.gradle/jdks.
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

rootProject.name = "jetlin"

include(
    ":jetlin-protocol",
    ":jetlin-runtime",
    ":jetlin-html",
    ":jetlin-server-ktor",
    ":jetlin-testing",
    ":samples:demo",
    ":samples:keyed-benchmark",
    ":conventions",
)
