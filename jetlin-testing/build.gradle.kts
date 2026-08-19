plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    // Callers write @Composable content against these, so they are part of this module's surface.
    api(project(":jetlin-html"))

    // Deliberately no test-framework dependency: assertions here throw AssertionError directly, so
    // this works under JUnit 4, JUnit 5 or anything else the consuming project already runs.
    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
