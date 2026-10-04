plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

kotlin {
    compilerOptions {
        // This module is the tooling that JetlinDbTooling exists for.
        optIn.add("jetlin.db.JetlinDbTooling")
    }
}

dependencies {
    // Callers pass their schema and policies, so jetlin-db is part of this module's API.
    api(project(":jetlin-db"))

    // There's deliberately no test framework dependency. A failed check throws AssertionError, so this
    // module works with JUnit 4, JUnit 5, or whatever the project already uses.

    // The tests check deliberately flawed policies over their own entities.
    kspTest(project(":jetlin-db-ksp"))
    testImplementation(libs.kotlin.test)
    testRuntimeOnly(libs.slf4j.simple)
}
