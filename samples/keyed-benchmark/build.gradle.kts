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

/**
 * Repeats one operation under a Flight Recorder recording, for the question the benchmark cannot
 * answer: not what an operation costs but which frames it spends its time in.
 *
 * A task rather than `installDist` and a classpath glob, because that distribution is assembled by
 * file name and two of the dependencies here are both called `runtime-desktop-<version>.jar` — one
 * from JetBrains, one from androidx. The loser is dropped silently and the run dies on a missing
 * class that has nothing to do with the collision.
 *
 * OP=create|swap|remove, ROWS, CHUNK, SECONDS and JFR select what is recorded and where it goes.
 */
tasks.register<JavaExec>("profile") {
    group = "verification"
    description = "Loops one benchmark operation under JFR. See Profile.kt."
    mainClass.set("jetlin.samples.keyed.ProfileKt")
    classpath = sourceSets["main"].runtimeClasspath
    val recording = providers.environmentVariable("JFR").getOrElse("profile.jfr")
    val seconds = providers.environmentVariable("SECONDS").getOrElse("30").toInt()
    jvmArgs(
        "-Xmx6g",
        "-Dstdout.encoding=UTF-8",
        "-XX:StartFlightRecording=duration=${seconds + 5}s,filename=$recording,settings=profile",
    )
}
