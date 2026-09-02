plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

dependencies {
    implementation(project(":jetlin-server-ktor"))
    implementation(libs.ktor.server.netty)
    implementation(libs.slf4j.simple)

    testImplementation(project(":jetlin-testing"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("jetlin.samples.vessels.MainKt")
}
