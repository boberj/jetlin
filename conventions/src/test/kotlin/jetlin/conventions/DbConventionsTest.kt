package jetlin.conventions

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.verify.assertTrue
import kotlin.test.Test
import kotlin.test.assertTrue as assertTrueKotlin

/**
 * Rules that protect `jetlin-db`'s safety properties as the code changes.
 *
 * The compiler can't enforce these rules, reviewers can easily miss a violation, and a change that
 * looks harmless on its own can break any of them.
 */
class DbConventionsTest {

    /**
     * Returning a record grants access to it, so a function that returns one must take the principal.
     *
     * The design checks access when code obtains a record. Once application code holds a record,
     * reading its fields isn't checked. That's safe only if every way of obtaining a record is checked,
     * so no public function may return a record, or a collection of records, without a principal to
     * check against. One unchecked accessor would undermine the whole model.
     */
    @Test
    fun `nothing public in jetlin-db hands out a record without a principal`() {
        val handOuts = Konsist.scopeFromProject(moduleName = "jetlin-db", sourceSetName = "main")
            .functions(includeNested = true, includeLocal = false)
            .filter { it.hasPublicOrDefaultModifier }
            .filter { function ->
                val returned = function.returnType?.name?.removeSuffix("?") ?: return@filter false
                // Match a function's own type parameter bounded by Record, or a View of records. A `T`
                // declared by the enclosing class deliberately doesn't match: the gate created that
                // instance, and it already carries the principal. That's how `View.add` is checked
                // without taking a principal.
                val recordParameters = function.typeParameters
                    .filter { "Record" in it.name }
                    .map { it.name.substringBefore(':').trim() }
                returned in recordParameters || returned.startsWith("View<")
            }

        // Make sure the rule can't pass because it matched nothing.
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
     * Policies run during recomposition, so they must not suspend or block.
     *
     * Reading a policy-filtered collection runs the policy for each record on every read, during
     * composition. Results aren't cached, because a cached decision could outlive the state it was
     * based on and break reactive revocation. A policy that did I/O would put a network round trip
     * inside recomposition, on the session's only thread.
     */
    @Test
    fun `policies do not suspend or block`() {
        val policies = Konsist.scopeFromProject()
            .classesAndObjects(includeNested = true)
            // A parent's name includes its type arguments, as in `Policy<Todo, User>`.
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
     * Every entity must have a policy, because an entity without access rules is almost always a
     * mistake.
     *
     * The KSP processor already fails the build in this case, and that's the more important check,
     * because it covers applications too. This test covers this repository, and documents the rule
     * alongside the other two.
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
 * Member functions that may return records without a principal, because no principal exists yet
 * where they're used.
 *
 * Each one explains why at its definition. Treat adding an entry as a significant change in review:
 * every entry is a way around the policy checks, and needs an equally strong justification.
 */
private val PRIVILEGED_MEMBERS = setOf(
    // Loading at startup. Principals are later looked up in the graph being loaded.
    "Row.reference",
    "Row.referenceOrNull",
)

/**
 * Top-level functions that may return records without a principal. See [PRIVILEGED_MEMBERS].
 *
 * They have no containing type, so they're listed by name only.
 */
private val PRIVILEGED_TOP_LEVEL = setOf(
    // Finding the principal. That can't require a principal, so §4.4 of the plan allows exactly one
    // unchecked lookup for it.
    "authenticate",
    // Seeding, fixtures, and backfills. It works only inside `unsafe { }`, which logs a warning with
    // the reason on every call, so nobody can use it unnoticed.
    "insertUnchecked",
)

/** Text that suggests a function blocks. A policy whose source contains any of it fails the rule. */
private val BLOCKING = listOf("runBlocking", "Thread.sleep", ".get()", "readText")

/** The failure message for a public function that returns records without a principal. */
private val PRINCIPAL_REQUIRED = """
    A public function that returns a record, or a collection of records, must take the principal it is
    handing them to.

    Authorization happens where a record is obtained — a collection, a lookup, a relation — because
    checking it again on every field read would sit in the middle of recomposition. That is only sound
    while there is no way to obtain one without being checked. If this function really is the exception,
    it belongs next to `unsafe`, named so that it can be found.
""".trimIndent()

/** The failure message for a policy that suspends or blocks. */
private val POLICIES_ARE_PURE = """
    A policy is evaluated per record, per read, inside a composition, and its result is deliberately not
    cached — so it must be a cheap, pure expression over live state.

    Nothing that waits: no suspending calls, no IO, no blocking. Whatever a policy needs has to be
    resident already, which is the whole reason the graph is kept in memory.
""".trimIndent()

/** The failure message for an entity without a policy. */
private val POLICY_REQUIRED = """
    An @Entity needs a companion object implementing Policy, because nothing else decides who may read
    or write it:

        companion object : Policy<Todo, User> by owned(Todo::owner)
""".trimIndent()
