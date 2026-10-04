package jetlin.db

import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KProperty1

/**
 * Builds a policy from grants: lines that say under which condition a principal can do what.
 *
 * Declare the policy on the entity's companion object, and write one grant per line:
 *
 * ```kotlin
 * @Entity
 * class Todo(@Owner val owner: User, title: String) : Record() {
 *     var title: String by column(title)
 *     var team: Team? by reference()
 *     var archived: Boolean by column(false)
 *
 *     companion object : Policy<Todo, User> by policy({
 *         val admin = (principal() map User::admin) describedAs "the principal is an admin"
 *
 *         principal() equalTo record(Todo::owner) implies canEdit()
 *         admin implies canEdit()
 *         principal() map User::team equalTo record(Todo::team) implies canRead()
 *         admin implies canChange(Todo::archived)
 *     })
 * }
 * ```
 *
 * Each line reads as a sentence: "If the principal is its owner, they can edit it. If the principal
 * is an admin, they can edit it. If the principal's team is its team, they can read it. If the
 * principal is an admin, they can change archived."
 *
 * The same policy can also be written subject first, with shorthand conditions:
 *
 * ```kotlin
 * companion object : Policy<Todo, User> by policy({
 *     val admin = usersWhere(User::admin, "the principal is an admin")
 *
 *     userIn(Todo::owner).canEdit()
 *     admin.canEdit()
 *     membersOf(record(Todo::team), membership = User::team).canRead()
 *     admin.canChange(Todo::archived)
 * })
 * ```
 *
 * The two styles build the same policy: `condition.canEdit()` is `condition implies canEdit()`.
 * [PolicyRules] explains when each reads better. You can mix them in one policy.
 *
 * Two principles hold throughout:
 *
 * - Nothing is allowed unless a grant allows it.
 * - Grants add up. If two lines grant the same thing, either condition is enough. That's how you write
 *   "or": as separate lines.
 *
 * See [PolicyRules] for the conditions and permissions you can write.
 *
 * The framework runs [rules] the first time it uses the policy, not when the companion object is
 * created, so the rules can refer to anything the entity's generated code declares.
 *
 * When a policy refuses a write, the [AccessDenied] message quotes the conditions that would have
 * allowed it:
 *
 * ```
 * User#2 may not change Todo#7: archived can only be changed if the principal is an admin
 * ```
 *
 * @param rules Declares the grants.
 * @return The policy.
 */
public fun <T : Record, P : Principal> policy(rules: PolicyRules<T, P>.() -> Unit): Policy<T, P> =
    RulesPolicy(rules)

/**
 * The grants and rules a policy built with [policy] can declare.
 *
 * A grant is a line of the form `condition implies permission`:
 *
 * ```kotlin
 * principal() equalTo record(Todo::owner) implies canEdit()
 * ```
 *
 * ## Conditions
 *
 * A condition is built from values:
 *
 * | Value                          | Means                                             |
 * |--------------------------------|---------------------------------------------------|
 * | [principal]`()`                | the principal: the user the application acts for  |
 * | [record]`(Todo::owner)`        | the record's `owner` field                        |
 * | `value `[map]` User::team`     | a field of another value, such as its team        |
 *
 * and combined with these:
 *
 * | Condition                      | Holds when                                        |
 * |--------------------------------|---------------------------------------------------|
 * | `a `[equalTo]` b`              | `a` and `b` are the same, and neither is missing  |
 * | `a `[contains]` b`             | the collection `a` includes `b`                   |
 * | `principal() map User::admin`           | a `Boolean` field is true                         |
 * | `a `[and]` b`                  | both hold                                         |
 * | [not]`(a)`                     | `a` doesn't hold                                  |
 * | [anyone]                       | always                                            |
 *
 * There's no `or`: write two lines instead, because grants add up.
 *
 * ## Permissions
 *
 * | Permission                         | Lets the principal…                                          |
 * |------------------------------------|--------------------------------------------------------------|
 * | [canRead]`()`                      | see a record                                                 |
 * | [canCreate]`()`                    | add a record                                                 |
 * | [canUpdate]`()`                    | change a record                                              |
 * | [canDelete]`()`                    | delete a record                                              |
 * | [canEdit]`()`                      | add, change, and delete a record                             |
 * | [canChange]`(column)`              | change one column, and makes it the only way to change it    |
 * | [canReassign]`(column) { … }`      | hand a record to a candidate the block accepts               |
 * | [canOffer]`(column, via)`          | offer a record to someone, who can then accept it            |
 *
 * Two more declarations limit what the grants allow:
 *
 * - `column.`[onlyAllows]`(…) { … }` limits the values a column can take.
 * - [alwaysRequires] keeps everyone else away from every record, whatever the grants say.
 *
 * ## Two ways to write a grant
 *
 * Every permission can also be called on a condition, which puts the subject first:
 *
 * | Condition first                                             | Subject first                                            |
 * |-------------------------------------------------------------|----------------------------------------------------------|
 * | `principal() equalTo record(Todo::owner) implies canEdit()` | `userIn(Todo::owner).canEdit()`                          |
 * | `principal() map User::admin implies canDelete()`           | `usersWhere(User::admin).canDelete()`                    |
 * | `principal() map User::team equalTo record(Todo::team) implies canRead()` | `membersOf(record(Todo::team), membership = User::team).canRead()` |
 * | `anyone implies canRead()`                                  | `anyone.canRead()`                                       |
 *
 * The subject-first form is shorthand: `condition.canEdit()` is `condition implies canEdit()`, and
 * [userIn], [usersWhere], and [membersOf] return ordinary conditions. Both forms add up, refuse with
 * the same messages, and can be mixed in one policy.
 *
 * Subject first reads best for the common shapes: an owner, a flag on the principal, a group. Write
 * the condition out when it compares anything else, or joins conditions with [and] or [not]. To call
 * a permission on a condition you wrote out, put the condition in parentheses:
 * `(principal() equalTo record(Todo::owner)).canEdit()`.
 */
public class PolicyRules<T : Record, P : Principal> internal constructor() {
    internal val readers = mutableListOf<Condition<T, P>>()
    internal val creators = mutableListOf<Condition<T, P>>()
    internal val updaters = mutableListOf<Condition<T, P>>()
    internal val deleters = mutableListOf<Condition<T, P>>()
    internal val columnGrants = LinkedHashMap<String, MutableList<Condition<T, P>>>()
    internal val valueRules = LinkedHashMap<String, ValueRule<T, P, *>>()
    internal val reassignments = mutableListOf<Reassignment<T, P>>()
    internal val offers = mutableListOf<Offer<T, P>>()
    internal val requirements = mutableListOf<Condition<T, P>>()
    internal val granted = mutableListOf<Pair<Permission<T, P>, Condition<T, P>>>()
    private val unclaimed = LinkedHashSet<Permission<T, P>>()

    // ---- Values -----------------------------------------------------------------------------------

    /**
     * Gets the principal: the user the application is acting for.
     *
     * ```kotlin
     * principal() equalTo record(Todo::owner) implies canEdit()
     * principal() map User::team equalTo record(Todo::team) implies canRead()
     * ```
     *
     * @return The principal, described as "the principal" in refusal messages.
     */
    public fun principal(): Value<T, P, P> = Value("the principal", isStatement = false) { env ->
        // A policy is only ever asked about principals of its own type.
        @Suppress("UNCHECKED_CAST")
        env.principal as P
    }

    /**
     * Gets the value of [property] on the record the policy is being asked about.
     *
     * ```kotlin
     * principal() equalTo record(Todo::owner) implies canEdit()    // "the principal is its owner"
     * ```
     *
     * Always name the entity, as in `record(Todo::owner)`. In a policy for `User`, both the record
     * and the principal are users, so writing `record(…)` for one and `principal() map …` for the
     * other is what says which user a field belongs to.
     *
     * @param property The field to read. If it's `null`, the value is missing, and no comparison with
     *   it holds.
     * @return The field's value, described as "its ‹field›" in refusal messages.
     */
    public fun <V> record(property: KProperty1<T, V>): Value<T, P, V & Any> =
        Value("its ${property.name}", isStatement = false) { env ->
            // A policy is only ever asked about records of its own entity.
            @Suppress("UNCHECKED_CAST")
            property.get(env.record as T)
        }

    /**
     * A condition that always holds.
     *
     * ```kotlin
     * // Anyone signed in can see announcements, and only admins can edit them.
     * anyone implies canRead()
     * admin implies canEdit()
     * ```
     */
    public val anyone: Condition<T, P> = Value("anyone", isStatement = true) { true }

    // ---- Permissions ------------------------------------------------------------------------------

    /**
     * Lets principals see records.
     *
     * ```kotlin
     * // Everyone on a todo's team can see it.
     * principal() map User::team equalTo record(Todo::team) implies canRead()
     * ```
     *
     * A record the principal can't see is left out of every collection, and a lookup by ID returns
     * `null`, as if the record didn't exist.
     *
     * Principals who can update or delete a record can always see it, so they don't need this too.
     * Principals who can only add records, or only change certain columns, can't see records unless
     * you grant it.
     */
    public fun canRead(): Permission<T, P> = permission("canRead()") { readers += it }

    /**
     * Lets principals add records.
     *
     * ```kotlin
     * principal() equalTo record(Todo::owner) implies canCreate()
     * ```
     *
     * The framework checks the condition against the new record. With the grant above, Alice can add
     * a todo whose owner is Alice, but not one whose owner is Bob.
     *
     * Adding a record doesn't let the principal see it. A form where users submit feedback that only
     * admins can read needs only this permission for users.
     */
    public fun canCreate(): Permission<T, P> = permission("canCreate()") { creators += it }

    /**
     * Lets principals change records.
     *
     * ```kotlin
     * principal() equalTo record(Todo::owner) implies canUpdate()
     * ```
     *
     * The framework checks the condition twice: against the record before the change, and against
     * the record as it would be afterwards. So with the grant above, Alice can change her todo's
     * title, but not its owner: afterwards, the todo wouldn't be hers to change. To let records
     * change hands, use [canReassign] or [canOffer].
     *
     * The permission covers every column except two kinds:
     *
     * - Columns with their own [canChange] grants.
     * - Columns that say who a record belongs to, according to [canReassign] or [canOffer]. Once you
     *   declare how a record changes hands, that's the only way it does. This permission can still
     *   clear such a column, setting it to `null`, which hands the record to nobody.
     *
     * The second kind matters for grants whose condition doesn't depend on the record, such as
     * "the principal is an admin". That condition holds after any change, so without this, it would
     * let admins hand a record to anyone.
     */
    public fun canUpdate(): Permission<T, P> = permission("canUpdate()") { updaters += it }

    /**
     * Lets principals delete records.
     *
     * ```kotlin
     * admin implies canDelete()
     * ```
     */
    public fun canDelete(): Permission<T, P> = permission("canDelete()") { deleters += it }

    /**
     * Lets principals add, change, and delete records: [canCreate], [canUpdate], and [canDelete]
     * together.
     *
     * ```kotlin
     * principal() equalTo record(Todo::owner) implies canEdit()
     * ```
     */
    public fun canEdit(): Permission<T, P> = permission("canEdit()") {
        creators += it
        updaters += it
        deleters += it
    }

    /**
     * Lets principals change [column] on any record they can see, and makes grants like this one the
     * only way to change it.
     *
     * ```kotlin
     * // Owners edit their todos, but only admins can archive them, anyone's.
     * principal() equalTo record(Todo::owner) implies canEdit()
     * admin implies canRead()
     * admin implies canChange(Todo::archived)
     * ```
     *
     * This permission gives access to one column, even on records the principal can't otherwise
     * change. In the example, admins can archive any todo, but can't change its title.
     *
     * Once a column has a grant like this, [canUpdate] no longer covers it. In the example, owners
     * can change everything about their todos except `archived`. To let owners archive too, grant it
     * to them as well: `principal() equalTo record(Todo::owner) implies canChange(Todo::archived)`.
     *
     * The framework checks this permission when a change gives [column] a new value. It doesn't
     * check it when a record is added. To control the value a new record starts with, use
     * [onlyAllows].
     *
     * @param column The column. It must be a `var`.
     */
    public fun <V> canChange(column: KMutableProperty1<T, V>): Permission<T, P> =
        permission("canChange(${column.name})") { columnGrants.getOrPut(column.name) { mutableListOf() } += it }

    /**
     * Lets principals hand a record on, by setting [column] to a candidate that [to] accepts.
     *
     * ```kotlin
     * // Whoever a task is assigned to can reassign it to a teammate.
     * principal() equalTo record(Task::assignee) implies canEdit()
     * principal() equalTo record(Task::assignee) implies canReassign(Task::assignee) { candidate ->
     *     candidate map User::team equalTo (principal() map User::team)
     * }
     * ```
     *
     * Without this permission, [canUpdate] refuses the change: afterwards, the principal would no
     * longer be the assignee, so they couldn't change the task. With it, the framework checks the
     * rest of the change as if [column] hadn't moved. The task can go to a teammate, but nothing
     * else is relaxed.
     *
     * A new record can start out already handed on, when [column] holds principals: Alice can add
     * a task assigned to Bob, because she could have added it for herself and then reassigned it.
     *
     * Once a column has a grant like this, [canUpdate] no longer covers giving it a new value: only
     * grants like this one can hand the record on. That includes principals whose [canUpdate] grant
     * holds whatever the record says, such as admins. To let admins reassign records, grant this to
     * them too.
     *
     * Setting [column] to `null` isn't handing the record on, so [canUpdate] still covers it.
     *
     * @param column The column that says who the record belongs to. It must be a `var`.
     * @param to Returns the condition a candidate must meet. It receives the candidate as a value,
     *   described as "the candidate" in refusal messages.
     */
    public fun <V : Record?> canReassign(
        column: KMutableProperty1<T, V>,
        to: (candidate: Value<T, P, V & Any>) -> Condition<T, P>,
    ): Permission<T, P> {
        val candidate = Value<T, P, V & Any>("the candidate", isStatement = false) { env ->
            // The candidate is the column's new value, so it has the column's type.
            @Suppress("UNCHECKED_CAST")
            env.candidate as V
        }
        val accepts = to(candidate)
        return permission("canReassign(${column.name})") { reassignments += Reassignment(it, column, accepts) }
    }

    /**
     * Lets principals offer a record to someone by setting [via], and lets that person accept it.
     *
     * ```kotlin
     * @Entity
     * class Doc(@Owner owner: User, text: String) : Record() {
     *     var owner: User by reference(owner)
     *     var offeredTo: User? by reference()
     *     var text: String by column(text)
     *
     *     companion object : Policy<Doc, User> by policy({
     *         principal() equalTo record(Doc::owner) implies canEdit()
     *         principal() equalTo record(Doc::owner) implies canOffer(Doc::owner, via = Doc::offeredTo)
     *     })
     * }
     *
     * with(alice) { doc.update { offeredTo = bob } }                 // Alice offers it to Bob.
     * with(bob) { doc.update { owner = bob; offeredTo = null } }     // Bob accepts it.
     * ```
     *
     * Only principals who meet the condition can change [via]: setting it offers the record, and
     * setting it back to `null` withdraws the offer. To limit who a record can be offered to, add an
     * [onlyAllows] rule for [via].
     *
     * The principal a record is offered to can see it, so they can decide whether to accept. The only
     * change they can make is accepting: setting [column] to themselves and [via] to `null`, both in
     * the same `update { }`, and nothing else. Nobody can push a record onto someone else this way.
     * Only the recipient can make it theirs.
     *
     * Accepting an offer becomes the only way [column] gets a new value. [canUpdate] doesn't cover
     * it, even for principals whose [canUpdate] grant holds whatever the record says, such as
     * admins.
     *
     * @param column The column that says who owns the record. It must be a `var`.
     * @param via The column that says who the record is offered to, or `null` if nobody.
     */
    public fun canOffer(column: KMutableProperty1<T, P>, via: KMutableProperty1<T, P?>): Permission<T, P> =
        permission("canOffer(${column.name})") {
            offers += Offer(column, via)
            columnGrants.getOrPut(via.name) { mutableListOf() } += it
        }

    /**
     * Grants [permission] to every principal for whom this condition holds.
     *
     * ```kotlin
     * principal() equalTo record(Todo::owner) implies canEdit()
     * ```
     *
     * Every permission must be granted this way. A permission on a line of its own grants nothing,
     * so the policy refuses to build, with an error that names it.
     */
    public infix fun Condition<T, P>.implies(permission: Permission<T, P>) {
        check(unclaimed.remove(permission)) { "${permission.description} is granted twice. Create it again for each grant." }
        permission.grant(this)
        granted += permission to this
    }

    // ---- Grants, written subject first ------------------------------------------------------------

    /**
     * Returns a condition that holds when the principal is the user in [field] of the record.
     *
     * ```kotlin
     * userIn(Todo::owner).canEdit()
     * // Same as: (principal() equalTo record(Todo::owner)) describedAs "the principal is its owner" implies canEdit()
     * ```
     *
     * If [field] is `null`, the condition doesn't hold.
     *
     * @param field The record's field that holds a user.
     * @return The condition, described as "the principal is its ‹field›".
     */
    public fun userIn(field: KProperty1<T, P?>): Condition<T, P> =
        principal() equalTo record(field) describedAs "the principal is its ${field.name}"

    /**
     * Returns a condition that holds when the principal's [property] is true, whatever the record.
     *
     * ```kotlin
     * usersWhere(User::admin, "the principal is an admin").canDelete()
     * // Same as: (principal() map User::admin) describedAs "the principal is an admin" implies canDelete()
     * ```
     *
     * @param property The principal's `Boolean` field.
     * @param description Describes the condition in refusal messages. Default: "the principal's
     *   ‹property› is true".
     * @return The condition.
     */
    public fun usersWhere(property: KProperty1<P, Boolean?>, description: String? = null): Condition<T, P> {
        val condition = principal() map property
        return if (description == null) condition else condition describedAs description
    }

    /**
     * Returns a condition that holds when the principal's own group, in [membership], is [group].
     *
     * ```kotlin
     * membersOf(record(Todo::team), membership = User::team).canRead()
     * // Same as: principal() map User::team equalTo record(Todo::team) implies canRead()
     * ```
     *
     * If either group is missing, the condition doesn't hold.
     *
     * @param group The group to be a member of, usually `record(…)`.
     * @param membership The principal's field that holds their group.
     * @return The condition, described as "the principal's ‹membership› is ‹group›".
     */
    public fun <G : Any> membersOf(group: Value<T, P, G>, membership: KProperty1<P, G?>): Condition<T, P> =
        principal() map membership equalTo group

    /**
     * Returns a condition that holds when the principal's groups, in [memberships], include [group].
     *
     * ```kotlin
     * membersOf(record(Todo::team), memberships = User::teams).canRead()
     * // Same as: principal() map User::teams contains record(Todo::team) implies canRead()
     * ```
     *
     * Name the argument `memberships =`. That's what tells this function apart from the one for a
     * single group.
     *
     * @param group The group to be a member of, usually `record(…)`.
     * @param memberships The principal's field that holds their groups.
     * @return The condition, described as "the principal's ‹memberships› include ‹group›".
     */
    @JvmName("membersOfAny")
    public fun <G : Any> membersOf(group: Value<T, P, G>, memberships: KProperty1<P, Collection<G>>): Condition<T, P> =
        principal() map memberships contains group

    /** Grants [canRead] to principals for whom this condition holds: `condition.canRead()`. */
    public fun Condition<T, P>.canRead(): Unit = this implies this@PolicyRules.canRead()

    /** Grants [canCreate] to principals for whom this condition holds: `condition.canCreate()`. */
    public fun Condition<T, P>.canCreate(): Unit = this implies this@PolicyRules.canCreate()

    /** Grants [canUpdate] to principals for whom this condition holds: `condition.canUpdate()`. */
    public fun Condition<T, P>.canUpdate(): Unit = this implies this@PolicyRules.canUpdate()

    /** Grants [canDelete] to principals for whom this condition holds: `condition.canDelete()`. */
    public fun Condition<T, P>.canDelete(): Unit = this implies this@PolicyRules.canDelete()

    /** Grants [canEdit] to principals for whom this condition holds: `condition.canEdit()`. */
    public fun Condition<T, P>.canEdit(): Unit = this implies this@PolicyRules.canEdit()

    /**
     * Grants [canChange] for [column] to principals for whom this condition holds:
     * `condition.canChange(column)`.
     */
    public fun <V> Condition<T, P>.canChange(column: KMutableProperty1<T, V>): Unit =
        this implies this@PolicyRules.canChange(column)

    /**
     * Grants [canReassign] for [column] to principals for whom this condition holds:
     * `condition.canReassign(column) { candidate -> … }`.
     */
    public fun <V : Record?> Condition<T, P>.canReassign(
        column: KMutableProperty1<T, V>,
        to: (candidate: Value<T, P, V & Any>) -> Condition<T, P>,
    ): Unit = this implies this@PolicyRules.canReassign(column, to)

    /**
     * Grants [canOffer] for [column] to principals for whom this condition holds:
     * `condition.canOffer(column, via)`.
     */
    public fun Condition<T, P>.canOffer(column: KMutableProperty1<T, P>, via: KMutableProperty1<T, P?>): Unit =
        this implies this@PolicyRules.canOffer(column, via)

    // ---- Rules that limit the grants --------------------------------------------------------------

    /**
     * Lets this column be set only to values that [allowed] accepts.
     *
     * ```kotlin
     * // A todo can only be shared with the principal's own team.
     * Todo::team.onlyAllows("the principal's own team") { team, principal ->
     *     team == principal.team || principal.admin
     * }
     * ```
     *
     * The framework checks this rule when a record is added, and when a change gives the column a
     * new value, whichever grant allowed the change. [allowed] receives the new value and the
     * principal making the change.
     *
     * The rule is never asked about `null`. `null` means the column is empty, which isn't a value to
     * allow or forbid:
     *
     * - Whether the column can be empty at all is decided by its type. A `Team?` column can, and a
     *   `Team` column can't.
     * - Who can empty it is decided by the grants, like any other change to the column. In the
     *   example, anyone who can update a todo can unshare it by setting `team = null`.
     *
     * That's why [allowed] receives a non-null value, and why the example needs no `team == null`
     * case.
     *
     * @param description Completes the sentence "‹column› can only be set to …" in the refusal
     *   message, for example, "the principal's own team".
     * @param allowed Returns true if the principal can give the column the value; false otherwise.
     */
    public fun <V> KMutableProperty1<T, V>.onlyAllows(
        description: String,
        allowed: (value: V & Any, principal: P) -> Boolean,
    ) {
        check(valueRules.put(name, ValueRule(this, description, allowed)) == null) {
            "onlyAllows is declared twice for $name."
        }
    }

    /**
     * Keeps every principal for whom [condition] doesn't hold away from every record, whatever the
     * grants allow.
     *
     * ```kotlin
     * // Suspended users can't see or change anything, even their own todos.
     * alwaysRequires(not(principal() map User::suspended))
     * ```
     *
     * The framework adds this to every check: reading, adding, changing, and deleting. For a change,
     * it checks the record before and after. If you declare it more than once, every declaration
     * has to hold.
     */
    public fun alwaysRequires(condition: Condition<T, P>) {
        requirements += condition
    }

    private fun permission(description: String, grant: (Condition<T, P>) -> Unit): Permission<T, P> =
        Permission(description, grant).also { unclaimed += it }

    /** Throws if a permission was created but never granted. */
    internal fun validate() {
        unclaimed.firstOrNull()?.let { permission ->
            error(
                "${permission.description} isn't granted to anyone, so it has no effect. " +
                    "Write it after a condition: `condition implies ${permission.description}`.",
            )
        }
    }
}

/**
 * Something a grant lets a principal do, such as [PolicyRules.canEdit]. It has no effect until a
 * condition grants it with [PolicyRules.implies].
 */
public class Permission<T : Record, P : Principal> internal constructor(
    internal val description: String,
    internal val grant: (Condition<T, P>) -> Unit,
) {
    override fun toString(): String = description
}

/**
 * A value in a condition: the principal, a field of the record, or something reached from them.
 *
 * Create values with [PolicyRules.principal] and [PolicyRules.record], and reach further with [map].
 * A value can be missing, for example, when the field it reads is `null`. No comparison with a
 * missing value holds.
 *
 * A value of type `Boolean` is a [Condition].
 *
 * @property description Describes the value in refusal messages, for example, "the principal's
 *   team".
 */
public class Value<T : Record, P : Principal, out V> internal constructor(
    public val description: String,
    internal val isStatement: Boolean,
    private val evaluate: (Env) -> V?,
) {
    internal fun valueIn(env: Env): V? = evaluate(env)

    override fun toString(): String = description
}

/**
 * A condition: a `Boolean` value that says whether a grant applies. It holds when the value is
 * true, and doesn't hold when it's false or missing.
 */
public typealias Condition<T, P> = Value<T, P, Boolean>

/**
 * Gets [property] of this value: `principal() map User::team` is the principal's team.
 *
 * ```kotlin
 * principal() map User::team equalTo record(Todo::team) implies canRead()
 * record(Todo::team) map Team::organization equalTo (principal() map User::organization) implies canRead()
 * ```
 *
 * If this value is missing, so is the result. If [property] is `null`, the result is missing.
 *
 * @return The field's value, described as "‹this›'s ‹field›" in refusal messages.
 */
public infix fun <T : Record, P : Principal, V : Any, W> Value<T, P, V>.map(property: KProperty1<V, W>): Value<T, P, W & Any> =
    Value("$description's ${property.name}", isStatement = false) { env -> valueIn(env)?.let { property.get(it) } }

/**
 * Checks whether this value and [other] are the same.
 *
 * ```kotlin
 * principal() equalTo record(Todo::owner) implies canEdit()    // "the principal is its owner"
 * ```
 *
 * The condition doesn't hold if either value is missing. A todo with no team isn't visible to users
 * with no team, even though both teams are `null`.
 *
 * Both values must be of the same kind. Comparing a team with a name compiles, because Kotlin
 * treats both as `Any`, but it throws [IllegalStateException] the first time the policy compares
 * them, instead of never matching.
 *
 * @return The condition, described as "‹this› is ‹other›".
 */
public infix fun <T : Record, P : Principal, V : Any> Value<T, P, V>.equalTo(other: Value<T, P, V>): Condition<T, P> {
    val statement = "$description is ${other.description}"
    return Value(statement, isStatement = true) { env ->
        val left = valueIn(env)
        val right = other.valueIn(env)
        if (left == null || right == null) {
            false
        } else {
            check(comparable(left, right)) { mismatch(statement, left, right) }
            left == right
        }
    }
}

/**
 * Checks whether this collection includes [element].
 *
 * ```kotlin
 * // Users on several teams can see todos shared with any of them.
 * principal() map User::teams contains record(Todo::team) implies canRead()
 * ```
 *
 * The condition doesn't hold if either value is missing.
 *
 * @return The condition, described as "‹this› include ‹element›".
 */
public infix fun <T : Record, P : Principal, V : Any> Value<T, P, Collection<V>>.contains(element: Value<T, P, V>): Condition<T, P> {
    val statement = "$description include ${element.description}"
    return Value(statement, isStatement = true) { env ->
        val items = valueIn(env)
        val item = element.valueIn(env)
        if (items == null || item == null) {
            false
        } else {
            check(items.isEmpty() || items.any { comparable(it, item) }) { mismatch(statement, items.first(), item) }
            item in items
        }
    }
}

/**
 * Checks whether both conditions hold.
 *
 * ```kotlin
 * principal() equalTo record(Todo::owner) and not(principal() map User::suspended) implies canEdit()
 * ```
 *
 * There's no `or`. Write two grants instead, because grants add up.
 */
public infix fun <T : Record, P : Principal> Condition<T, P>.and(other: Condition<T, P>): Condition<T, P> =
    Value("$statement and ${other.statement}", isStatement = true) { env -> holds(env) && other.holds(env) }

/**
 * Checks whether [condition] doesn't hold.
 *
 * ```kotlin
 * alwaysRequires(not(principal() map User::suspended))
 * ```
 *
 * A condition doesn't hold when a value it compares is missing, so `not` of it does. For example,
 * `not(principal() equalTo record(Doc::lockedBy))` holds for everyone when a document isn't locked by anyone.
 */
public fun <T : Record, P : Principal> not(condition: Condition<T, P>): Condition<T, P> =
    Value(
        if (condition.isStatement) "it isn't the case that ${condition.description}" else "${condition.description} is false",
        isStatement = true,
    ) { env -> !condition.holds(env) }

/**
 * Describes this condition in refusal messages as [statement].
 *
 * ```kotlin
 * val admin = (principal() map User::admin) describedAs "the principal is an admin"
 * admin implies canChange(Todo::archived)
 * // Refused: archived can only be changed if the principal is an admin
 * ```
 *
 * Without it, the condition above is described as "the principal's admin is true".
 */
public infix fun <T : Record, P : Principal> Condition<T, P>.describedAs(statement: String): Condition<T, P> =
    Value(statement, isStatement = true) { env -> valueIn(env) }

/** What a condition is asked about: a record, a principal, and, for a reassignment, a candidate. */
internal class Env(val record: Any?, val principal: Principal, val candidate: Any? = null)

/** Checks whether this condition holds in [env]: whether it's true, not false or missing. */
internal fun Condition<*, *>.holds(env: Env): Boolean = valueIn(env) == true

/** This condition as a sentence, for refusal messages. */
internal val Condition<*, *>.statement: String get() = if (isStatement) description else "$description is true"

private fun comparable(a: Any, b: Any): Boolean = a::class.isInstance(b) || b::class.isInstance(a)

private fun mismatch(statement: String, a: Any, b: Any): String =
    "The condition \"$statement\" compares a value of type ${a::class.simpleName} with one of type " +
        "${b::class.simpleName}, so it could never hold. Compare values of the same kind."

/**
 * Carries the reason for a refusal from a [RulesPolicy] to [Gate], on the current thread.
 *
 * [Gate] calls [clear], asks the policy, and if the policy refuses, calls [take]. Policies run
 * synchronously on the thread that asks them, so the reason can't come from another write.
 */
internal object Refusals {
    private val last = ThreadLocal<String?>()

    /** Forgets any earlier reason. */
    fun clear() {
        last.remove()
    }

    /** Remembers [reason] if it isn't `null`, and returns whether it's `null`: whether it's allowed. */
    fun note(reason: String?): Boolean {
        if (reason != null) last.set(reason)
        return reason == null
    }

    /** Returns the last reason noted since [clear], and forgets it. */
    fun take(): String? = last.get().also { last.remove() }
}

/** A [PolicyRules.onlyAllows] rule. It's never asked about `null`. */
internal class ValueRule<T : Record, P : Principal, V>(
    private val column: KProperty1<T, V>,
    val description: String,
    private val allowed: (V & Any, P) -> Boolean,
) {
    /** Checks whether [value], which the column is about to get, is allowed. `null` always is. */
    fun accepts(value: Any?, principal: P): Boolean {
        if (value == null) return true
        // The value came from the generated property with this rule's column's name and type.
        @Suppress("UNCHECKED_CAST")
        return allowed(value as (V & Any), principal)
    }

    /** Checks whether [record]'s current value is allowed. */
    fun acceptsCurrent(record: T, principal: P): Boolean = accepts(column.get(record), principal)
}

/** A [PolicyRules.canReassign] grant. */
internal class Reassignment<T : Record, P : Principal>(
    val who: Condition<T, P>,
    val column: KMutableProperty1<T, *>,
    private val to: Condition<T, P>,
) {
    val candidates: String get() = to.statement

    /** Checks whether giving the column [value] hands [record] to a candidate [to] accepts. */
    fun handsTo(record: T, value: Any?, principal: P): Boolean =
        value != null && to.holds(Env(record, principal, candidate = value))

    /**
     * Checks whether [record], which is being added, could have been added with [principal] in the
     * column and then handed on. [couldCreate] decides whether the principal could have added it.
     */
    fun couldHaveHandedOn(record: T, principal: P, couldCreate: (T) -> Boolean): Boolean {
        val value = column.get(record) ?: return false
        // The principal can only take the candidate's place if the column can hold them.
        if (value == principal || !value::class.isInstance(principal) || !handsTo(record, value, principal)) return false
        // isInstance checked that the column's type fits the principal.
        @Suppress("UNCHECKED_CAST")
        val writable = column as KMutableProperty1<T, Any?>
        return Trials.run(apply = { writable.set(record, principal) }) {
            couldCreate(record) && who.holds(Env(record, principal))
        }
    }
}

/** A [PolicyRules.canOffer] grant. */
internal class Offer<T : Record, P : Principal>(
    val column: KMutableProperty1<T, P>,
    val offeredTo: KMutableProperty1<T, P?>,
) {
    /** Checks whether [record] is offered to [principal]. */
    fun isOfferedTo(record: T, principal: P): Boolean = offeredTo.get(record)?.let { it == principal } == true

    /** Checks whether [change] is [principal] accepting an offer, and nothing else. */
    fun isAcceptance(change: Change<T>, principal: P): Boolean =
        isOfferedTo(change.record, principal) &&
            change.columns.mapTo(HashSet()) { it.name } == setOf(column.name, offeredTo.name) &&
            change.newValue(column) == principal &&
            change.newValue(offeredTo) == null
}

/**
 * The [Policy] that [policy] returns.
 *
 * Each `refuse…` function returns why the grants don't allow something, or `null` if they do. The
 * [Policy] methods return whether that's `null`, and note the reason in [Refusals] for [Gate] to put
 * in [AccessDenied]. They can't return it directly: an entity's companion object usually delegates
 * to this class with `by policy { }`, so [Gate] only ever sees the companion object.
 */
internal class RulesPolicy<T : Record, P : Principal>(declare: PolicyRules<T, P>.() -> Unit) : Policy<T, P> {
    private val rules by lazy { PolicyRules<T, P>().apply(declare).also { it.validate() } }

    override fun canRead(record: T, principal: P): Boolean =
        refuseRequirements(record, principal) == null && sees(record, principal)

    /**
     * Checks whether [principal] can change anything about [record]. The generated `canUpdate()`
     * asks this, so a page shows edit controls to anyone with a grant they could use.
     */
    override fun canWrite(record: T, principal: P): Boolean {
        if (refuseRequirements(record, principal) != null) return false
        val env = Env(record, principal)
        return rules.updaters.holdFor(env) ||
            (sees(record, principal) && rules.columnGrants.values.any { it.holdFor(env) }) ||
            rules.reassignments.any { it.who.holds(env) }
    }

    override fun canWrite(record: T, column: Column<T>, principal: P): Boolean {
        if (refuseRequirements(record, principal) != null) return false
        val env = Env(record, principal)
        return coversColumn(record, column.name, principal) ||
            rules.reassignments.any { it.column.name == column.name && it.who.holds(env) }
    }

    override fun canCreate(record: T, principal: P): Boolean = Refusals.note(refuseCreate(record, principal))

    override fun canDelete(record: T, principal: P): Boolean = Refusals.note(refuseDelete(record, principal))

    override fun canChange(change: Change<T>, principal: P): Boolean = Refusals.note(refuseChange(change, principal))

    @JetlinDbTooling
    override fun describeGrants(): List<GrantDescription> = rules.granted.map { (permission, condition) ->
        GrantDescription(permission.description, condition.statement) { record, principal ->
            condition.holds(Env(record, principal))
        }
    }

    /** Returns why [principal] can't add [record], or `null` if they can. */
    fun refuseCreate(record: T, principal: P): String? {
        refuseRequirements(record, principal)?.let { return it }
        for ((name, rule) in rules.valueRules) {
            if (!rule.acceptsCurrent(record, principal)) return "$name can only be set to ${rule.description}"
        }
        if (rules.creators.holdFor(Env(record, principal))) return null
        val handedOn = rules.reassignments.any { reassignment ->
            reassignment.couldHaveHandedOn(record, principal) { rules.creators.holdFor(Env(it, principal)) }
        }
        return if (handedOn) null else only("created", rules.creators)
    }

    /** Returns why [principal] can't delete [record], or `null` if they can. */
    fun refuseDelete(record: T, principal: P): String? =
        refuseRequirements(record, principal)
            ?: only("deleted", rules.deleters).takeUnless { rules.deleters.holdFor(Env(record, principal)) }

    /** Returns why [principal] can't make [change], or `null` if they can. */
    fun refuseChange(change: Change<T>, principal: P): String? {
        val record = change.record
        val env = Env(record, principal)
        refuseRequirements(record, principal)?.let { return it }
        for (column in change.columns) {
            val rule = rules.valueRules[column.name] ?: continue
            if (!rule.accepts(change.newValueOf(column), principal)) {
                return "${column.name} can only be set to ${rule.description}"
            }
        }
        if (rules.offers.any { it.isAcceptance(change, principal) }) {
            return change.afterwards { refuseRequirements(it, principal) }
        }

        // Find the grant that covers each changed column. Columns covered by a reassignment or by
        // their own grants keep their old values in the check afterwards, so the rest of the change
        // is judged as if they hadn't moved.
        val keep = HashSet<String>()
        var updated = false
        for (column in change.columns) {
            val name = column.name
            val value = change.newValueOf(column)
            val reassignments = rules.reassignments.filter { it.column.name == name && it.who.holds(env) }
            val grants = rules.columnGrants[name]
            when {
                reassignments.any { it.handsTo(record, value, principal) } -> keep += name
                grants != null -> {
                    if (!sees(record, principal) || !grants.holdFor(env)) {
                        return "$name can only be changed if ${grants.statements()}"
                    }
                    keep += name
                }
                // canReassign and canOffer say how the record changes hands, so canUpdate can't hand
                // it on another way. It can still clear the column, which hands it to nobody.
                value != null && changesHands(name) -> return refuseHandingOn(name, reassignments)
                rules.updaters.holdFor(env) -> updated = true
                else -> return only("updated", rules.updaters)
            }
        }
        return change.afterwards(keeping = keep) { changed ->
            refuseRequirements(changed, principal)?.let { return@afterwards it }
            if (!updated || rules.updaters.holdFor(Env(changed, principal))) return@afterwards null
            "afterwards, it could only be updated if ${rules.updaters.statements()}"
        }
    }

    /**
     * Checks whether the column named [name] says who the record belongs to, according to a
     * [PolicyRules.canReassign] or [PolicyRules.canOffer] grant. [PolicyRules.canUpdate] doesn't cover
     * giving such a column a new value.
     */
    private fun changesHands(name: String): Boolean =
        rules.reassignments.any { it.column.name == name } || rules.offers.any { it.column.name == name }

    /**
     * Returns why setting the column named [name] to a new value is refused, given the [reassignments]
     * of it that the principal could use.
     */
    private fun refuseHandingOn(name: String, reassignments: List<Reassignment<T, P>>): String {
        reassignments.firstOrNull()?.let { return "$name can only be reassigned if ${it.candidates}" }
        val anyone = rules.reassignments.filter { it.column.name == name }
        if (anyone.isNotEmpty()) return "$name can only be reassigned if ${anyone.map { it.who }.statements()}"
        return "$name can only change hands when someone accepts an offer"
    }

    /** Checks whether [principal] can see [record], ignoring [PolicyRules.alwaysRequires]. */
    private fun sees(record: T, principal: P): Boolean {
        val env = Env(record, principal)
        return rules.readers.holdFor(env) ||
            rules.updaters.holdFor(env) ||
            rules.deleters.holdFor(env) ||
            rules.offers.any { it.isOfferedTo(record, principal) }
    }

    /** Checks whether a grant lets [principal] change the column named [name] on [record]. */
    private fun coversColumn(record: T, name: String, principal: P): Boolean {
        val env = Env(record, principal)
        val grants = rules.columnGrants[name] ?: return !changesHands(name) && rules.updaters.holdFor(env)
        return sees(record, principal) && grants.holdFor(env)
    }

    private fun refuseRequirements(record: T, principal: P): String? {
        val env = Env(record, principal)
        return rules.requirements.firstOrNull { !it.holds(env) }?.let { "it requires that ${it.statement}" }
    }

    private fun List<Condition<T, P>>.holdFor(env: Env): Boolean = any { it.holds(env) }

    private fun List<Condition<T, P>>.statements(): String = joinToString(", or if ") { it.statement }

    /**
     * Returns the refusal for something only [grants] allow. [done] is the action's past
     * participle, such as "updated".
     */
    private fun only(done: String, grants: List<Condition<T, P>>): String =
        if (grants.isEmpty()) "nothing lets anyone ${done.removeSuffix("d")} it" else "it can only be $done if ${grants.statements()}"
}
