plugins {
    // The parent project already has the Kotlin plugin on its classpath, with the catalog's version.
    kotlin("jvm")
}

// `:jetlin-db` asks for `jetlin:jetlin-db-migrate`. The group has to match for Gradle to substitute
// this project, here and in any build that includes the Jetlin repository.
group = "jetlin"

dependencies {
    // The engine runs at build time inside Gradle and at startup inside the application, so it
    // depends on nothing but the driver, the same version that `:jetlin-db` uses.
    implementation(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
}

// An included build doesn't inherit the root build's conventions. See ../build.gradle.kts.
kotlin {
    jvmToolchain(24)
    explicitApi()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
