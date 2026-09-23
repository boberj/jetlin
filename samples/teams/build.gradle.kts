plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("jetlin.db")
    application
}

dependencies {
    implementation(project(":jetlin-server-ktor"))
    implementation(project(":jetlin-db"))
    ksp(project(":jetlin-db-ksp"))

    implementation(libs.ktor.server.netty)
    // The sample calls its stub of an external system over real HTTP. The stub runs in the same
    // process and on the same port, but the client and the requests are real, so the wiring is tested.
    implementation(libs.ktor.client.cio)
    implementation(libs.slf4j.simple)

    testImplementation(project(":jetlin-testing"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("jetlin.samples.teams.MainKt")
}

tasks.test {
    // The sample's tests run two principals against one database, which is where a leaked reference
    // would show up, so turn on the leak detector, as the framework's tests do.
    systemProperty("jetlin.db.leakDetector", "true")
}

// Measures the retained heap of each record in the in-memory graph. `:samples:demo:benchmark`
// measures the cost of each session.
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures retained heap per resident record."
    mainClass.set("jetlin.samples.teams.BenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
