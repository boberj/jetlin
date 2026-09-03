plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

dependencies {
    implementation(project(":jetlin-server-ktor"))
    implementation(libs.ktor.server.netty)
    implementation(libs.slf4j.simple)

    testImplementation(libs.kotlin.test)
}

application {
    mainClass.set("jetlin.samples.keyed.MainKt")
}

/**
 * The js-framework-benchmark keyed operations, measured server-side.
 *
 * A separate task rather than a test: it takes minutes, it reports numbers rather than passing or
 * failing, and a build that has to wait for it is a build people stop running. What *is* a test is
 * `KeyedBenchmarkTest`, which pins the op counts these measurements depend on.
 */
tasks.register<JavaExec>("keyedBenchmark") {
    group = "verification"
    description = "Runs the keyed js-framework-benchmark operations and reports server-side cost."
    mainClass.set("jetlin.samples.keyed.RunnerKt")
    classpath = sourceSets["main"].runtimeClasspath
    // The report has em dashes in it, and a container with a POSIX locale otherwise writes them
    // out as question marks.
    jvmArgs("-Xmx6g", "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8")
}
