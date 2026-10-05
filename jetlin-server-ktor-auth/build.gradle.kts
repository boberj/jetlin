plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    // Only the tests need it: their session type is @Serializable, which is how Ktor stores sessions.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":jetlin-server-ktor"))
    api(libs.ktor.server.auth)
    api(libs.ktor.server.sessions)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
}
