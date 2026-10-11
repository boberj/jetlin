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
    }
    // Use the repository's version catalog, so the migration tool and the runtime use the same SQLite
    // driver version.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "jetlin-db-gradle"

// The migration engine. Both the plugin (`dbMigrate`) and `:jetlin-db` (`Db.open`) run it, so it lives
// here, where the plugin can depend on it directly. The root build's `:jetlin-db` depends on it by
// coordinates, and Gradle substitutes this project for them.
include("jetlin-db-migrate")
