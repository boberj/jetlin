plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

dependencies {
    // The snapshot system is the whole substrate: cells are snapshot state and a transaction is a
    // mutable snapshot. Deliberately no dependency on :jetlin-html — see the package KDoc.
    api(project(":jetlin-runtime"))

    // One file, in process, no server. The only storage dependency.
    implementation(libs.sqlite.jdbc)

    // `unsafe` logs every time it runs, and a warning nobody can route anywhere is not a warning.
    implementation(libs.slf4j.api)

    // Tests compose a real view and assert on the ops a write produces, which is the only honest way
    // to check that reading a field subscribes the reader. That needs the HTML applier in the test
    // source set only.
    testImplementation(project(":jetlin-html"))

    // Route guards are routing, not storage — but whether an entity-bound route discloses a record it
    // refused to show can only be checked with both halves present.
    testImplementation(project(":jetlin-testing"))
    // The processor runs over this module's own test entities, which is the only place in the repo
    // where an @Entity exists before the samples do — and generated code that compiles against the
    // runtime it was generated for is the thing worth checking.
    kspTest(project(":jetlin-db-ksp"))

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.test {
    // A leaked reference is this design's known failure mode, so the detector that finds one is on for
    // every test in the repo. Production leaves it off: see LeakDetector.
    systemProperty("jetlin.db.leakDetector", "true")
}
