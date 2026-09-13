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
    // The sample talks to its own stub of an external system over real HTTP: one process, one port, but a
    // genuine client and a genuine request, because a fake transport would prove nothing about the wiring.
    implementation(libs.ktor.client.cio)
    implementation(libs.slf4j.simple)

    testImplementation(project(":jetlin-testing"))
    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("jetlin.samples.teams.MainKt")
}

tasks.test {
    // The sample's tests run two principals against one database, which is where a leaked reference would
    // show up. On, for the same reason a framework test has it on.
    systemProperty("jetlin.db.leakDetector", "true")
}

/** Retained heap for the resident graph and for the sessions reading it — the two halves of the ceiling. */
tasks.register<JavaExec>("benchmark") {
    group = "verification"
    description = "Measures retained heap per resident record and per live session."
    mainClass.set("jetlin.samples.teams.BenchmarkKt")
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("-Xmx2g")
}
