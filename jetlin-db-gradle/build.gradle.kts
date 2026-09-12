plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-gradle-plugin`
}

dependencies {
    // The migration runner applies SQL to the database file, so it needs the same driver the runtime has.
    implementation(libs.sqlite.jdbc)
    implementation(libs.serialization.json)

    testImplementation(libs.kotlin.test)
}

// An included build gets none of the root build's conventions, so the ones that matter are repeated here:
// the same toolchain, the same explicit API, and the same test framework and logging.
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
