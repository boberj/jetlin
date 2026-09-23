plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.ksp.api)

    testImplementation(libs.kotlin.test)
}
