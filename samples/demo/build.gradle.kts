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
    mainClass.set("jetlin.samples.demo.MainKt")
}

// Measures the retained heap of each live session, which limits how many users a node can hold.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures retained heap per live session."
    mainClass.set("jetlin.samples.demo.BenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
