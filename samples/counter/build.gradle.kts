plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

dependencies {
    implementation(project(":jetlin-server-ktor"))
    implementation(libs.ktor.server.netty)
    implementation(libs.slf4j.simple)
}

application {
    mainClass.set("jetlin.samples.counter.MainKt")
}

/** Retained heap per live session — the number that decides whether stateful sessions are viable. */
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures retained heap per live session."
    mainClass.set("jetlin.samples.counter.BenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
