package jetlin.db.testing

import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.Schema
import jetlin.db.Table

/**
 * Tests every policy in [schema] against many small, random worlds, and throws if it finds a
 * problem.
 *
 * ```kotlin
 * @Test
 * fun `the policies have no unexpected loopholes`(): Unit = checkPolicies(JetlinSchema) {
 *     allow(Check.PushOntoOthers, Todo::team, because = "sharing a todo with a team is the point")
 * }
 * ```
 *
 * Each world is a real database with a few records of every entity, filled from small pools of
 * values, so that collisions such as two users on the same team are common. In each world, the check
 * tries every change, addition, and deletion a principal could make to a record they can reach, and
 * looks for the patterns in [Check]: principals who can give themselves more access, push records
 * onto others, or reach states they couldn't create directly. It also tests any properties you
 * state with [PolicyCheck.never] and [PolicyCheck.always].
 *
 * A pattern isn't always a mistake. Sharing a todo *is* pushing it onto others. When a finding is
 * intended, [allow][PolicyCheck.allow] it, and say why. The reasons document the policy.
 *
 * When it finds a problem, the check shrinks the world to the fewest records that still show it,
 * and throws an [AssertionError] that describes that world and the action, step by step.
 *
 * The same seed always produces the same worlds, so a failure always reproduces.
 *
 * @param schema The application's generated schema, usually `JetlinSchema`.
 * @param configure Sets up the worlds, and declares what's allowed and what must hold.
 * @return The report, which lists allowed findings too.
 * @throws AssertionError If there's a finding that isn't allowed, or a property doesn't hold.
 */
public fun checkPolicies(schema: Schema, configure: PolicyCheck.() -> Unit = {}): PolicyReport {
    val report = findPolicyProblems(schema, configure)
    if (report.problems.isNotEmpty()) throw AssertionError(report.describe())
    return report
}

/**
 * Tests every policy in [schema] like [checkPolicies], but returns what it found instead of
 * throwing.
 *
 * Use it to explore a policy, or to assert on particular findings.
 *
 * @return The report.
 */
public fun findPolicyProblems(schema: Schema, configure: PolicyCheck.() -> Unit = {}): PolicyReport {
    val settings = PolicyCheck().apply(configure)
    return Engine(Universe(schema, settings), settings).run()
}

/**
 * Compares two policies for the same entity, and returns every decision where they disagree.
 *
 * ```kotlin
 * @Test
 * fun `the rewritten policy decides like the old one`(): Unit =
 *     comparePolicies(JetlinSchema, Todos.table, old = LegacyTodoPolicy, new = Todos.policy).assertSame()
 * ```
 *
 * Both policies answer the same questions in the same worlds: who can see, change, and delete each
 * record, who can change each column, and which changes and additions each principal could make.
 * Use it to check a refactor, such as rewriting a hand-written policy with `policy { }`, or to see
 * exactly what tightening a policy changes.
 *
 * @param table The entity's table, such as `Todos.table`.
 * @return The comparison. Call [PolicyComparison.assertSame] to fail on any difference.
 */
public fun <T : Record, P : Principal> comparePolicies(
    schema: Schema,
    table: Table<T>,
    old: Policy<T, P>,
    new: Policy<T, P>,
    configure: PolicyCheck.() -> Unit = {},
): PolicyComparison {
    val settings = PolicyCheck().apply(configure)
    val oldSettings = PolicyCheck().apply(configure).also { it.policyOverrides[table] = old }
    val newSettings = PolicyCheck().apply(configure).also { it.policyOverrides[table] = new }
    return Comparison(schema, table, oldSettings, newSettings, settings).run()
}

/**
 * The settings for [checkPolicies]: how to build worlds, which findings are intended, and which
 * properties must hold.
 */
public class PolicyCheck internal constructor() {
    /** How many random worlds to test. More worlds find rarer problems, and take longer. Default: 30. */
    public var worlds: Int = 30

    /** The seed for the random worlds. The same seed always produces the same worlds. Default: 1. */
    public var seed: Long = 1

    /**
     * The most actions to try in each world, chosen at random when there are more. Default: 150.
     */
    public var maxActionsPerWorld: Int = 150

    internal val entities = HashMap<KClass<*>, EntitySetup<*>>()
    internal val allowances = mutableListOf<Allowance>()
    internal val nevers = mutableListOf<Never>()
    internal val alwayses = mutableListOf<Always<*, *>>()
    internal val policyOverrides = HashMap<Table<*>, Policy<*, *>>()

    /**
     * Sets up how worlds are built for one entity.
     *
     * ```kotlin
     * entity(User::class) {
     *     count = 4
     *     values(User::team, "acme", "globex", null)
     * }
     * ```
     */
    public fun <T : Record> entity(type: KClass<T>, configure: EntitySetup<T>.() -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val setup = entities.getOrPut(type) { EntitySetup<T>() } as EntitySetup<T>
        setup.configure()
    }

    /**
     * Marks every finding of [check] as intended, and says why.
     *
     * ```kotlin
     * allow(Check.CannotUndo, because = "handing a task on is meant to be final")
     * ```
     */
    public fun allow(check: Check, because: String) {
        allowances += Allowance(check, entity = null, column = null, because = because)
    }

    /**
     * Marks the findings of [check] about records of [entity] as intended, and says why.
     *
     * ```kotlin
     * allow(Check.PushOntoOthers, Announcement::class, because = "announcements are for everyone")
     * ```
     */
    public fun allow(check: Check, entity: KClass<out Record>, because: String) {
        allowances += Allowance(check, entity, column = null, because = because)
    }

    /**
     * Marks the findings of [check] that involve [column] as intended, and says why.
     *
     * ```kotlin
     * allow(Check.PushOntoOthers, Todo::team, because = "sharing a todo with a team is the point")
     * ```
     */
    public fun allow(check: Check, column: KProperty1<out Record, *>, because: String) {
        allowances += Allowance(check, column.ownerClass(), column.name, because)
    }

    /**
     * States that no allowed action may ever match [forbidden].
     *
     * ```kotlin
     * never("a non-admin changes archived") { event ->
     *     event.changes(Todo::archived) && !(event.principal as User).admin
     * }
     * ```
     *
     * The check asks it about every action a principal is allowed to take, in every world.
     */
    public fun never(description: String, forbidden: (Event) -> Boolean) {
        nevers += Never(description, forbidden)
    }

    /**
     * States that [holds] is true for every record of [entity] and every principal of [principal], in
     * every world.
     *
     * ```kotlin
     * always(Todo::class, User::class, "an owner can see their own todos") { todo, user, access ->
     *     todo.owner != user || access.read
     * }
     * ```
     */
    public fun <T : Record, P : Principal> always(
        entity: KClass<T>,
        principal: KClass<P>,
        description: String,
        holds: (record: T, principal: P, access: Access) -> Boolean,
    ) {
        alwayses += Always(entity, principal, description, holds)
    }
}

/** How worlds are built for one entity. See [PolicyCheck.entity]. */
public class EntitySetup<T : Record> internal constructor() {
    /** How many records of this entity each world has. Default: 3 for principals, 2 otherwise. */
    public var count: Int? = null

    internal val pools = HashMap<String, List<Any?>>()

    /**
     * Sets the values [property] can take in worlds.
     *
     * By default, a text column takes "a" or "b", a number takes 0 or 1, and a nullable column can
     * also be `null`. Choose values when the policy compares a column with particular values, such
     * as a role. The values you give are used exactly: include `null` if the column should sometimes
     * be empty. A reference always points at a record of the world, so it can't be set here.
     */
    public fun <V> values(property: KProperty1<T, V>, vararg values: V) {
        pools[property.name] = values.toList()
    }
}

/**
 * The patterns [checkPolicies] looks for. Each is a common kind of policy mistake, but none is
 * always one: [allow][PolicyCheck.allow] the findings that are intended.
 *
 * @property title A short name, for reports.
 * @property explanation What the pattern is, for reports.
 */
public enum class Check(public val title: String, public val explanation: String) {
    /**
     * A principal gains access to other records by changing, adding, or deleting a record: for
     * example, by setting their own `admin` field, or joining a team they weren't invited to.
     */
    SelfEscalation(
        "Self-escalation",
        "A principal gains access to other records by changing, adding, or deleting a record.",
    ),

    /**
     * A principal gains access to a record by changing it: for example, by making themselves its
     * owner. Accepting an offer looks like this, and is usually intended.
     */
    TakeOver("Takeover", "A principal gains access to a record by changing it."),

    /**
     * One principal's action gives another principal access they didn't have: for example, by making
     * them the owner of a record, or sharing it with their team. Sharing is often intended.
     *
     * A principal who can already do the same to every other record of that entity doesn't count:
     * an admin who can edit every todo gains nothing when someone adds one.
     */
    PushOntoOthers(
        "Pushing onto others",
        "One principal's action gives another principal access they didn't have, without their involvement.",
    ),

    /**
     * A change produces a record that the principal couldn't have added directly: something they can
     * reach in two steps that the policy refuses in one.
     */
    NotCreatable("Reachable but not creatable", "A change produces a record that the principal couldn't have added directly."),

    /**
     * Access depends on two missing values counting as the same: for example, a user on no team
     * seeing a todo shared with no team.
     */
    MissingValuesMatch("Missing values match", "Access depends on two missing values counting as the same."),

    /** A grant's condition never held in any world, so the grant has no effect. */
    DeadGrant("Dead grant", "A grant's condition never held in any world, so the grant has no effect."),

    /** A change leaves a record that nobody can see any more. */
    Orphan("Orphan", "A change leaves a record that nobody can see any more."),

    /** A principal can make a change they can't reverse. */
    CannotUndo("Cannot undo", "A principal can make a change they can't reverse."),
}

/** What a principal did, in an [Event]. */
public enum class Action {
    /** Added a record. */
    Create,

    /** Changed a record. */
    Update,

    /** Deleted a record. */
    Delete,
}

/**
 * An action a principal was allowed to take, for [PolicyCheck.never].
 *
 * @property action What the principal did.
 * @property principal Who did it.
 * @property record The record, as it was before the action. For [Action.Create], the new record.
 */
public class Event internal constructor(
    public val action: Action,
    public val principal: Principal,
    public val record: Record,
    private val before: Map<String, Any?>,
    private val after: Map<String, Any?>,
) {
    /**
     * Checks whether the action gave [property] a new value.
     *
     * @return True if [property] belongs to the record's entity, and its value changed; false
     *   otherwise.
     */
    public fun changes(property: KProperty1<out Record, *>): Boolean =
        belongs(property) && property.name in after && before[property.name] != after[property.name]

    /**
     * Gets the value [property] had before the action. For [Action.Create], it's the new value.
     *
     * @return The value.
     */
    public fun <V> before(property: KProperty1<out Record, V>): V = valueIn(before, property)

    /**
     * Gets the value [property] has after the action. For [Action.Delete], it's the value before.
     *
     * @return The value.
     */
    public fun <V> after(property: KProperty1<out Record, V>): V = valueIn(after, property)

    private fun <V> valueIn(values: Map<String, Any?>, property: KProperty1<out Record, V>): V {
        require(belongs(property) && property.name in values) {
            "${property.name} isn't a column of ${record::class.simpleName}"
        }
        // The value was read from the column this property declares.
        @Suppress("UNCHECKED_CAST")
        return values[property.name] as V
    }

    private fun belongs(property: KProperty1<out Record, *>): Boolean =
        property.ownerClass()?.let { it == record::class } ?: true

    override fun toString(): String = "$action by $principal on $record"
}

/**
 * What one principal can do with one record, for [PolicyCheck.always].
 *
 * @property read Whether they can see it.
 * @property update Whether they can change anything about it.
 * @property delete Whether they can delete it.
 */
public class Access internal constructor(
    public val read: Boolean,
    public val update: Boolean,
    public val delete: Boolean,
    private val columns: Map<String, Boolean>,
) {
    /**
     * Checks whether they can change [property].
     *
     * @return True if they can change it; false otherwise.
     */
    public fun change(property: KProperty1<out Record, *>): Boolean = columns[property.name] == true
}

/**
 * What [checkPolicies] found.
 *
 * @property problems The findings that aren't allowed, and the properties that don't hold.
 * @property allowed The findings that [PolicyCheck.allow] covers, with the reason given.
 * @property worlds How many worlds were tested.
 */
public class PolicyReport internal constructor(
    public val problems: List<Problem>,
    public val allowed: List<Problem>,
    public val worlds: Int,
) {
    /** Describes every problem, for an assertion message. */
    public fun describe(): String = buildString {
        appendLine("Found ${problems.size} policy problem(s) in $worlds worlds.")
        for (problem in problems) {
            appendLine()
            append(problem.details)
        }
    }

    override fun toString(): String = describe()
}

/**
 * One finding, or one property that didn't hold.
 *
 * @property check The pattern, or `null` for a property stated with [PolicyCheck.never] or
 *   [PolicyCheck.always].
 * @property summary One line that says what was found.
 * @property details The whole explanation: the smallest world that shows it, what happened, and
 *   how to allow it if it's intended.
 * @property allowedBecause The reason given to [PolicyCheck.allow], or `null` if it isn't allowed.
 */
public class Problem internal constructor(
    public val check: Check?,
    public val summary: String,
    public val details: String,
    public val allowedBecause: String?,
) {
    override fun toString(): String = summary
}

/**
 * What [comparePolicies] found.
 *
 * @property differences Every decision where the two policies disagree, each described with the
 *   smallest world that shows it.
 */
public class PolicyComparison internal constructor(public val differences: List<String>) {
    /**
     * Throws if the policies disagreed about anything.
     *
     * @throws AssertionError If there's any difference.
     */
    public fun assertSame() {
        if (differences.isNotEmpty()) {
            throw AssertionError(
                "The policies disagree in ${differences.size} way(s).\n\n" + differences.joinToString("\n\n"),
            )
        }
    }
}

internal class Allowance(val check: Check, val entity: KClass<*>?, val column: String?, val because: String)

internal class Never(val description: String, val forbidden: (Event) -> Boolean)

internal class Always<T : Record, P : Principal>(
    val entity: KClass<T>,
    val principal: KClass<P>,
    val description: String,
    val holds: (T, P, Access) -> Boolean,
)

/**
 * Returns the class that declares this property, or `null` if Kotlin doesn't say.
 *
 * A property reference such as `Todo::team` records its class without full reflection. Allowances
 * and events use it to tell `Todo::team` from `User::team`.
 */
internal fun KProperty1<*, *>.ownerClass(): KClass<*>? =
    (this as? kotlin.jvm.internal.CallableReference)?.owner as? KClass<*>
