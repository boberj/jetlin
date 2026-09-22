plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

dependencies {
    // Everything is built on the snapshot system: cells are snapshot state and a transaction is a
    // mutable snapshot. There is intentionally no dependency on :jetlin-html; route guards live there.
    api(project(":jetlin-runtime"))

    // SQLite runs in process against a single file. This is the only storage dependency.
    implementation(libs.sqlite.jdbc)

    // `unsafe` logs a warning on every call, through SLF4J so applications can route it.
    implementation(libs.slf4j.api)

    // Tests compose a real view and assert on the ops a write produces. That is the most direct way to
    // check that reading a field subscribes the reader. The HTML applier is only needed for tests.
    testImplementation(project(":jetlin-html"))

    // Route guards belong to routing, not storage, but testing that an entity-bound route doesn't reveal
    // a record it refused to show needs both modules.
    testImplementation(project(":jetlin-testing"))
    // Run the processor over this module's test entities. Apart from the samples, they are the only
    // entities in the repository, and they let the tests check that generated code compiles against
    // this runtime.
    kspTest(project(":jetlin-db-ksp"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    // Leaked references are the design's known weak point, so the leak detector is enabled for all
    // tests. It is off by default in production; see LeakDetector.
    systemProperty("jetlin.db.leakDetector", "true")
}
