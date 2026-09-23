plugins {
    alias(libs.plugins.kotlin.jvm)
}

/**
 * Repository-wide conventions that the compiler can't express, checked as ordinary tests.
 *
 * This module has no production code. It exists so the rules live somewhere that obviously covers
 * the whole repository, instead of inside whichever module needed them first.
 */
dependencies {
    testImplementation(libs.konsist)
    testImplementation(libs.kotlin.test)
}
