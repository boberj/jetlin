plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    api(project(":jetlin-runtime"))
    api(project(":jetlin-protocol"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
}
