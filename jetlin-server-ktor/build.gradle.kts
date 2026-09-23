plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    // SessionSnapshot is @Serializable. Without this plugin, the annotation compiles but generates
    // nothing, so a store that has to write the snapshot somewhere would fail to find a serializer.
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":jetlin-html"))
    api(libs.ktor.server.core)
    api(libs.ktor.server.websockets)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.server.netty)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    // A real logging backend, so tests can assert that reaching a limit is reported. Without one,
    // SLF4J binds a no-op logger and the warnings go nowhere, which is also what a regression that
    // removed them would look like.
    testImplementation(libs.logback.classic)
}
