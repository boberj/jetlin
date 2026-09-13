package jetlin.conventions

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as assertTrueKotlin

/**
 * Rules that keep `jetlin-db`'s safety properties true as it changes.
 *
 * All three are properties the compiler cannot state and a reviewer will not reliably notice, and all
 * three are the sort of thing that gets broken by a change that looks local.
 */
class DbConventionsTest {

    /**
     * Handing out a record is an authorization decision, so the principal has to be in the signature.
     *
     * The design's load-bearing choice is that a reference *is* authority: once application code holds a
     * record, reading its fields is unchecked. That is only survivable while every way of obtaining one
     * is gated, which means no public function may return a record, or a collection of records, without
     * a principal to check against. One ungated accessor, added in good faith, collapses the model.
     */
    @Test
    fun `nothing public in jetlin-db hands out a record without a principal`() {
        val handOuts = Konsist.scopeFromProject(moduleName = "jetlin-db", sourceSetName = "main")
            .functions(includeNested = true, includeLocal = false)
            .filter { it.hasPublicOrDefaultModifier }
            .filter { function ->
                val returned = function.returnType?.name?.removeSuffix("?") ?: return@filter false
                // A type parameter this function declares and bounds by Record is a record; a View is a
                // collection of them. A bare `T` belonging to the *class* is not matched, and that is
                // right: the instance it came from was built by the gate and carries the principal, which is
                // how `View.add` is checked without taking one.
                val recordParameters = function.typeParameters
                    .filter { "Record" in it.name }
                    .map { it.name.substringBefore(':').trim() }
                returned in recordParameters || returned.startsWith("View<")
            }

        // A rule that matched nothing would approve of anything.
        assertTrueKotlin(
            handOuts.size >= 4,
            "expected to find jetlin-db's record-returning functions, found ${handOuts.size} — " +
                "the Konsist scope is probably resolving to the wrong module",
        )

        handOuts.assertTrue(additionalMessage = PRINCIPAL_REQUIRED) { function ->
            val topLevel = function.containingDeclaration is KoFileDeclaration
            val owner = (function.containingDeclaration as? KoNameProvider)?.name
            function.name in PRIVILEGED_TOP_LEVEL && topLevel ||
                "$owner.${function.name}" in PRIVILEGED_MEMBERS ||
                function.parameters.any { parameter ->
                    parameter.type.name.removeSuffix("?").let { it == "P" || it == "Principal" }
                }
        }
    }

    /**
     * A policy runs on the recomposition hot path, so it cannot wait for anything.
     *
     * Reading a policy-filtered collection evaluates the policy per row, per read, in the middle of a
     * composition — and deliberately does not cache, because a cached decision outlives the state it was
     * based on, which is how reactive revocation gets quietly broken. A policy that did IO would put a
     * network round trip inside recomposition, on a thread a session cannot afford to block.
     */
    @Test
    fun `policies do not suspend or block`() {
        val policies = Konsist.scopeFromProject()
            .classesAndObjects(includeNested = true)
            // A parent's name carries its type arguments: `Policy<Todo, User>`.
            .filter { declaration -> declaration.parents().any { it.name.substringBefore('<') == "Policy" } }

        assertTrueKotlin(
            policies.isNotEmpty(),
            "expected to find some Policy implementations; the Konsist scope is resolving to nothing",
        )

        policies.flatMap { it.functions() }.assertTrue(additionalMessage = POLICIES_ARE_PURE) { function ->
            !function.hasSuspendModifier &&
                BLOCKING.none { blocking -> blocking in function.text }
        }
    }

    /**
     * An entity with no access rules is almost always an oversight.
     *
     * KSP fails the build on one, which is the check that matters because it also covers entities in
     * applications. This one covers this repository and, more usefully, says so next to the other two
     * rules rather than only inside a processor.
     */
    @Test
    fun `every entity declares a policy`() {
        val entities = Konsist.scopeFromProject()
            .classes(includeNested = true)
            .filter { declaration -> declaration.annotations.any { it.name == "Entity" } }

        assertTrueKotlin(
            entities.isNotEmpty(),
            "expected to find some @Entity classes; the Konsist scope is resolving to nothing",
        )

        entities.assertTrue(additionalMessage = POLICY_REQUIRED) { entity ->
            entity.objects(includeNested = true).any { nested ->
                nested.parents().any { it.name.substringBefore('<') == "Policy" }
            }
        }
    }
}

/**
 * The privileged roots: the places that obtain a record with no principal because none exists yet.
 *
 * Each is a place where there is no principal to check against yet, and each says so at its definition.
 * Adding to this list is the visible review event it should be: an entry here is a hole in the gate, and it
 * needs the same kind of argument these have. A top-level function has no containing type, which is why
 * the top-level ones are listed by name alone.
 */
private val PRIVILEGED_MEMBERS = setOf(
    // Reading the file at boot: the graph being built is what a principal would later be resolved against.
    "Row.reference",
    "Row.referenceOrNull",
)

private val PRIVILEGED_TOP_LEVEL = setOf(
    // Working out who the principal is. A system that cannot resolve a principal without a principal cannot
    // start, which is why §4.4 blesses exactly one root for it.
    "authenticate",
    // Seeding, fixtures and backfills. Not a second quiet way in: it refuses unless `unsafe { }` is in
    // effect, and `unsafe` logs a warning naming the reason every time it runs.
    "insertUnchecked",
)

private val BLOCKING = listOf("runBlocking", "Thread.sleep", ".get()", "readText")

private val PRINCIPAL_REQUIRED = """
    A public function that returns a record, or a collection of records, must take the principal it is
    handing them to.

    Authorization happens where a record is obtained — a collection, a lookup, a relation — because
    checking it again on every field read would sit in the middle of recomposition. That is only sound
    while there is no way to obtain one without being checked. If this function really is the exception,
    it belongs next to `unsafe`, named so that it can be found.
""".trimIndent()

private val POLICIES_ARE_PURE = """
    A policy is evaluated per row, per read, inside a composition, and its result is deliberately not
    cached — so it must be a cheap, pure expression over live state.

    Nothing that waits: no suspending calls, no IO, no blocking. Whatever a policy needs has to be
    resident already, which is the whole reason the graph is kept in memory.
""".trimIndent()

private val POLICY_REQUIRED = """
    An @Entity needs a companion object implementing Policy, because nothing else decides who may read
    or write it:

        companion object : Policy<Todo, User> by owned(Todo::owner)
""".trimIndent()
