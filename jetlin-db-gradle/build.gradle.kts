plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-gradle-plugin`
}

dependencies {
    // The migration runner runs SQL against the database file, so it uses the same driver as the
    // runtime.
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test)
}

// An included build doesn't inherit the root build's conventions, so the relevant ones are repeated
// here: the toolchain, explicit API mode, and the test framework and logging settings.
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

gradlePlugin {
    plugins {
        create("jetlinDb") {
            id = "jetlin.db"
            implementationClass = "jetlin.db.gradle.JetlinDbPlugin"
        }
    }
}
