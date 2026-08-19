plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    // SessionSnapshot is @Serializable. Without this the annotation compiles but generates nothing,
    // so any store that has to write the envelope somewhere would fail to find a serializer.
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
}
