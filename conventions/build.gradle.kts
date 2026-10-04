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

tasks.test {
    // Konsist reads the other modules' sources from disk while the tests run, so Gradle can't see
    // them as inputs. Without this, a change elsewhere in the repository left the task up to date, and
    // a violation could pass unnoticed until something in this module changed too.
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("**/src/**/*.kt")
            exclude("**/build/**", "conventions/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE)
}
