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
    mainClass.set("jetlin.samples.demo.MainKt")
}

/** Retained heap per live session — the ceiling on how many users a node can carry. */
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures retained heap per live session."
    mainClass.set("jetlin.samples.demo.BenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
