package jetlin.conventions

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as assertTrueKotlin

/**
 * Conventions that the compiler will not catch and a reviewer will not reliably notice.
 */
class TestConventionsTest {

    /**
     * A JUnit test method must return `void`. Kotlin's expression bodies make it very easy to write
     * one that does not:
     *
     * ```kotlin
     * @Test
     * fun `something`() = runTest {   // returns whatever the last expression evaluated to
     *     assertIs<Patch>(message)    // ...which here is a Patch, not Unit
     * }
     * ```
     *
     * JUnit does not reject that method. It does not warn about it either — verified against this
     * project's setup, including at `--info`. It simply never discovers it, so the test reports as
     * neither passing nor failing: it is absent, and the suite still goes green. That already
     * happened once here, to a test that was guarding real behaviour.
     *
     * Declaring `: Unit` makes the return type part of the signature rather than something inferred
     * from whatever the body happens to end with, so a later edit to the last line cannot quietly
     * un-register the test. Block-bodied tests are `Unit` by construction and need no annotation.
     */
    @Test
    fun `every test function returns Unit`() {
        // Matched by the name as written rather than by KClass: on the JVM `kotlin.test.Test` is a
        // typealias for `org.junit.jupiter.api.Test`, so a class-based matcher compares the
        // typealias target against the source text and silently matches nothing.
        val tests = Konsist.scopeFromProject(sourceSetName = "test")
            .functions()
            .filter { function -> function.annotations.any { it.name == "Test" } }

        // Guard against the guard silently passing: if the scope resolved to nothing, this rule
        // would approve of a repository with no tests in it at all.
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

private val EXPLANATION = """
    A test with an expression body must declare `: Unit` explicitly.

        @Test
        fun `name`(): Unit = runTest { ... }
                    ^^^^^^

    Without it the method's return type is inferred from the last expression in the body. If that
    is anything but Unit, JUnit silently does not discover the method and the test never runs.
""".trimIndent()
