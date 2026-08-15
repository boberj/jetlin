plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.serialization.json)

    testImplementation(libs.kotlin.test)
}
