package jetlin.conventions

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as assertTrueKotlin

/**
 * Rules for the repository's tests that the compiler won't catch and a reviewer won't reliably
 * notice.
 */
class TestConventionsTest {

    /**
     * A JUnit test method must return `void`. Kotlin's expression bodies make it easy to write one
     * that doesn't:
     *
     * ```kotlin
     * @Test
     * fun `something`() = runTest {   // returns whatever the last expression evaluated to
     *     assertIs<Patch>(message)    // ...which here is a Patch, not Unit
     * }
     * ```
     *
     * JUnit doesn't reject that method, and it doesn't warn about it either. This was verified
     * against this project's setup, including with `--info`. JUnit never discovers the method, so
     * the test neither passes nor fails. It's absent, and the suite still passes. That already
     * happened once here, to a test that was guarding real behavior.
     *
     * Declaring `: Unit` makes the return type part of the signature, instead of something inferred
     * from whatever the body ends with, so a later edit to the last line can't unregister the test
     * without anyone noticing. Tests with a block body always return `Unit` and need no declaration.
     */
    @Test
    fun `every test function returns Unit`() {
        // Match by the name as written, not by KClass. On the JVM, `kotlin.test.Test` is a typealias
        // for `org.junit.jupiter.api.Test`, so a class-based matcher compares the typealias target
        // with the source text, and matches nothing without any error.
        val tests = Konsist.scopeFromProject(sourceSetName = "test")
            .functions()
            .filter { function -> function.annotations.any { it.name == "Test" } }

        // Make sure this check can't pass by accident. If the scope resolved to nothing, the rule would
        // approve a repository with no tests at all.
        assertTrueKotlin(
            tests.size >= 20,
            "expected to find the project's test functions, found ${tests.size} — " +
                "the Konsist scope is probably resolving to the wrong directory",
        )

        tests.assertTrue(additionalMessage = EXPLANATION) { function ->
            function.hasBlockBody || function.returnType?.name == "Unit"
        }
    }
}

/** The failure message for a test with an inferred return type. */
private val EXPLANATION = """
    A test with an expression body must declare `: Unit` explicitly.

        @Test
        fun `name`(): Unit = runTest { ... }
                    ^^^^^^

    Without it the method's return type is inferred from the last expression in the body. If that
    is anything but Unit, JUnit silently does not discover the method and the test never runs.
""".trimIndent()
