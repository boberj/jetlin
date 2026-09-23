plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    // Callers write @Composable content against these, so they're part of this module's API.
    api(project(":jetlin-html"))

    // There's deliberately no test framework dependency. Assertions here throw AssertionError
    // directly, so this module works with JUnit 4, JUnit 5, or whatever the project already uses.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
