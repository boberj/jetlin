plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>("kotlin") {
            jvmToolchain(24)
            // Other people's code uses the library modules, so visibility and return types have to
            // be stated instead of inferred. Samples are applications, so they're exempt.
            if (project.name.startsWith("jetlin-")) {
                explicitApi()
            }
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            testLogging {
                events("passed", "failed", "skipped")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

/**
 * The root build's lifecycle tasks don't run tasks in included builds, so without these, the
 * migration tooling's tests would never run. With them, `./gradlew build` and `./gradlew check` cover
 * the whole repository.
 */
for (lifecycle in listOf("build", "check")) {
    tasks.register(lifecycle) {
        group = "build"
        description = "Runs $lifecycle in the included builds as well."
        dependsOn(gradle.includedBuild("jetlin-db-gradle").task(":$lifecycle"))
    }
}
