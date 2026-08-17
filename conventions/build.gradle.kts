plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Repo-wide conventions that the compiler cannot express, checked as ordinary tests.
 *
 * This module has no production code. It exists so the rules live somewhere obviously
 * repo-scoped rather than inside whichever module happened to need them first.
 */
dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlin.test)
}
