package jetlin.db

import kotlin.reflect.KClass

/**
 * The only route from application code to stored records.
 *
 * Every way of obtaining a record, whether a collection, a lookup by ID, or a relation, goes through
 * this object and is checked against the entity's policy. The central design decision is that
 * holding a reference grants access. Once application code has a record, reading its fields isn't
 * checked. Checking every field read would add work to every recomposition, and the application
 * would handle a per-principal view object instead of the entity itself.
 *
 * That model isn't leak-proof, so these safeguards make leaks unlikely and easy to find:
 *
 * 1. Application code can't reach an unchecked lookup. The members of `IdentityMap` that return
 *    records are internal, and this object is the only public way to reach them.
 * 2. Relation collections are filtered on every read, at the cost of running the policy for each
 *    record.
 * 3. Writes are checked again, because a reference can outlive the check that produced it.
 * 4. There's exactly one way to bypass the checks, [unsafe], and its name makes every use easy to
 *    find.
 * 5. [LeakDetector] turns a leaked reference into a test failure that includes the stack trace of
 *    where the record was obtained.
 *
 * Application code calls the generated accessors, which call these functions. They're public only
 * because the generated code is compiled into the application's module. Don't call them by hand.
 */
public object Gate {

    /** Returns a live list of the records in [table] that [principal] can read. */
    public fun <T : Record, P : Principal> view(
        db: Db,
        table: Table<T>,
        policy: Policy<T, P>,
        principal: P,
    ): View<T> {
        val gate = Gated(db, table, policy, principal)
        return View(db.resident.records(table.type), gate::canRead, gate)
    }

    /**
     * Returns the record with [id], or `null` if it doesn't exist or [principal] can't read it.
     *
     * Both cases return `null`, so they can't be told apart. A separate error for "not allowed" would
     * confirm to someone probing IDs that the record exists.
     */
    public fun <T : Record, P : Principal> find(
        db: Db,
        table: Table<T>,
        policy: Policy<T, P>,
        principal: P,
        id: Id<T>,
    ): T? {
        val record = db.resident.find(table.type, id) ?: return null
        return record.takeIf { Gated(db, table, policy, principal).canRead(it) }
    }

    /**
     * Returns a live list of the records in [table] that satisfy [match] and that [principal] can read.
     *
     * This implements inverse relations: `project.tasks` is every task whose `project` is this
     * project, except the ones this principal can't see. The filter runs on every read instead of
     * being cached, so when a project is shared with someone, its tasks appear on their open page
     * without any invalidation code.
     */
    public fun <T : Record, P : Principal> related(
        db: Db,
        table: Table<T>,
        policy: Policy<T, P>,
        principal: P,
        match: (T) -> Boolean,
    ): View<T> {
        val gate = Gated(db, table, policy, principal)
        return View(
            db.resident.records(table.type),
            { record -> match(record) && gate.canRead(record) },
            gate = null,
        )
    }

    /** Stores [record] if [principal] can create it, and throws [AccessDenied] otherwise. */
    public fun <T : Record, P : Principal> add(
        db: Db,
        policy: Policy<T, P>,
        principal: P,
        record: T,
    ): T {
        Refusals.clear()
        if (!unsafeInEffect && !policy.canCreate(record, principal)) {
            throw AccessDenied(refusal(principal, "create", record, Refusals.take()))
        }
        return db.transact { db.insert(record) }
    }

    /**
     * Returns whether [delete] would let [principal] delete [record].
     *
     * The generated `canDelete()` calls this so a page can hide a control that would be refused.
     * [delete] makes the same check, so the page and the enforcement can't disagree.
     */
    public fun <T : Record, P : Principal> canDelete(record: T, policy: Policy<T, P>, principal: P): Boolean =
        unsafeInEffect || policy.canDelete(record, principal)

    /** Deletes [record] if [principal] can delete it, and throws [AccessDenied] otherwise. */
    public fun <T : Record, P : Principal> delete(record: T, policy: Policy<T, P>, principal: P) {
        Refusals.clear()
        if (!canDelete(record, policy, principal)) {
            throw AccessDenied(refusal(principal, "delete", record, Refusals.take()))
        }
        val db = record.database ?: return // It isn't stored, so there's nothing to delete.
        db.transact { db.delete(record) }
    }

    /**
     * Runs an `update { }` block for [principal] as one transaction, if [policy] allows the change.
     *
     * The block runs against [draft], which holds its writes back. Then the framework asks
     * [Policy.canChange] about the whole change: the record as it is, the new values, and the record
     * as it would be afterwards. If the policy allows it, the new values are stored and committed.
     * If it refuses, this throws [AccessDenied], and nothing is stored.
     *
     * Because the policy sees the whole change at once, the order of the assignments in the block
     * doesn't matter.
     *
     * A record that isn't stored yet isn't checked here. Nobody else can have obtained it, and
     * [add] checks it with [Policy.canCreate] when it's stored. That's what lets you set up a new
     * record's fields before storing it.
     */
    public fun <T : Record, P : Principal, D : Draft<T>> update(
        record: T,
        policy: Policy<T, P>,
        principal: P,
        draft: D,
        block: D.() -> Unit,
    ) {
        val db = record.database
        if (db == null) {
            draft.block()
            draft.storePending()
            return
        }
        db.transact {
            draft.block()
            val change = Change(record, draft)
            Refusals.clear()
            if (!unsafeInEffect && !policy.canChange(change, principal)) {
                val reason = Refusals.take() ?: explainChange(change, policy, principal)
                throw AccessDenied(refusal(principal, "change", record, reason))
            }
            draft.storePending()
        }
    }

    /**
     * Returns whether [update] would allow the change that [block] describes, without making it.
     *
     * The generated `canUpdate { }` calls this, so a page can ask about a specific change before
     * offering it, for example, whether the principal can assign a task to Bob. [block] runs against
     * [draft], which records its writes without applying them, and the policy decides as [update]
     * would. Nothing is stored. [block] should only set fields.
     */
    public fun <T : Record, P : Principal, D : Draft<T>> canUpdate(
        record: T,
        policy: Policy<T, P>,
        principal: P,
        draft: D,
        block: D.() -> Unit,
    ): Boolean {
        if (unsafeInEffect || record.database == null) return true
        draft.block()
        return policy.canChange(Change(record, draft), principal)
    }

    /**
     * Returns why [policy] refused [change], for the [AccessDenied] message, by working out which of
     * the default [Policy.canChange] checks failed.
     *
     * A policy built with [policy] says which grant was missing through [Refusals] instead, so [Gate]
     * calls this only for other policies.
     */
    private fun <T : Record, P : Principal> explainChange(change: Change<T>, policy: Policy<T, P>, principal: P): String? {
        val record = change.record
        if (!policy.canWrite(record, principal)) return null
        change.columns.firstOrNull { !policy.canWrite(record, it, principal) }?.let { return "they may not change ${it.name}" }
        if (!change.afterwards { policy.canCreate(it, principal) }) {
            return "they couldn't create it as it would be afterwards"
        }
        return null
    }

    /** Returns the [AccessDenied] message for [principal] being refused [action] on [record]. */
    private fun refusal(principal: Principal, action: String, record: Record, reason: String?): String =
        "$principal may not $action $record" + (reason?.let { ": $it" } ?: "")

    /**
     * Returns whether [principal] can edit [record] at all: [Policy.canWrite].
     *
     * The generated `canUpdate()` calls this, so a page can show edit controls only to principals
     * who can use them. A principal who passes can still be refused a particular change, for example,
     * one that sets a column to a value the policy doesn't allow. To ask about a particular change,
     * use the `canUpdate { }` overload.
     */
    public fun <T : Record, P : Principal> canUpdate(record: T, policy: Policy<T, P>, principal: P): Boolean =
        unsafeInEffect || policy.canWrite(record, principal)

    /**
     * Returns whether [principal] can edit [record] and change [column] of it: [Policy.canWrite] for
     * the record, and then for the column.
     *
     * The generated `canUpdate(column)` calls this, so a page can disable a control for a column the
     * principal can't change, such as an **Archived** checkbox that only admins can use. Like
     * `canUpdate()`, it doesn't know the new value. To ask about a particular value, use the
     * `canUpdate { }` overload.
     */
    public fun <T : Record, P : Principal> canUpdate(
        record: T,
        column: Column<T>,
        policy: Policy<T, P>,
        principal: P,
    ): Boolean = canUpdate(record, policy, principal) && canWriteColumn(record, column, policy, principal)

    private fun <T : Record, P : Principal> canWriteColumn(
        record: T,
        column: Column<T>,
        policy: Policy<T, P>,
        principal: P,
    ): Boolean = unsafeInEffect || policy.canWrite(record, column, principal)
}

/**
 * Looks up a record without any policy check, to find out who the principal is.
 *
 * Finding the principal has to happen before there's a principal to check against, so it can't be
 * checked.
 *
 * ```kotlin
 * attributes { call ->
 *     val email = call.sessions.get<Auth>()?.email
 *     mapOf(PrincipalKey to email?.let { db.authenticate(User::class) { user -> user.email == it } })
 * }
 * ```
 *
 * This is the framework's privileged entry point. §4.4 of the design plan calls for exactly one,
 * because finding the principal can't require a principal. It runs no policy, so don't use it for
 * anything else. A `:conventions` test lists it as an allowed exception, and fails if someone adds
 * another unchecked entry point.
 *
 * @return the first record of [type] that satisfies [match], or `null` if none does.
 */
public fun <T : Record> Db.authenticate(type: KClass<T>, match: (T) -> Boolean): T? =
    resident.records(type).firstOrNull(match)

/**
 * Stores a record without a policy check, for seeding, fixtures, and backfills.
 *
 * You can call it only inside [unsafe]. Seeding needs an unchecked insert because the first user in
 * an empty database has no principal to be checked against. Normal
 * writes use `db.todos.add(…)`, which is checked.
 *
 * @param id the ID to store the record under, for fixtures where the ID matters, such as a seeded
 *   record that something links to by number, or a test that requests `/todo/1`. The ID sequence
 *   advances past [id], so it won't be allocated again.
 * @throws IllegalStateException if called outside [unsafe].
 * @throws IllegalArgumentException if [id] is already in use.
 */
public fun <T : Record> Db.insertUnchecked(record: T, id: Long? = null): T {
    check(unsafeInEffect) {
        "insertUnchecked stores $record without checking any policy, so it is only allowed inside " +
            "unsafe { }, which says why."
    }
    if (id != null) {
        record.adoptStoredId(id)
        Ids.advanceTo(record::class, id)
    }
    return transact { insert(record) }
}

/**
 * Creates a record of [table] from column values, and stores it without a policy check.
 *
 * Tooling, such as `jetlin-db-testing`, uses it to build throwaway worlds, which needs records built
 * from raw values instead of constructors. Like the other [insertUnchecked], it works only inside
 * [unsafe].
 *
 * Every column needs a value, keyed by name. A reference's value is the record it points to, which
 * must be stored in this database.
 *
 * @throws IllegalStateException if called outside [unsafe].
 */
@JetlinDbTooling
public fun <T : Record> Db.insertUnchecked(table: Table<T>, values: Map<String, Any?>): T {
    val row = table.columns.associate { column ->
        require(column.name in values) { "No value for ${table.name}.${column.name}" }
        column.name to when (val value = values[column.name]) {
            is Record -> value.id
            is Boolean -> if (value) 1L else 0L
            else -> value
        }
    }
    return insertUnchecked(table.instantiate(Row(row, resident)))
}

/**
 * Returns the database that a stored record belongs to.
 *
 * Generated inverse relations use this, so `project.tasks` doesn't need a database argument.
 * Application code shouldn't need it.
 *
 * @throws IllegalStateException if [record] isn't stored.
 */
public fun databaseOf(record: Record): Db = record.database ?: error(
    "$record is not stored, so it has no database: an inverse relation of an unstored record is empty by " +
        "definition. Store it first.",
)

/**
 * A policy bound to one principal.
 *
 * This wrapper hides the principal type, so a [View] can hold it without a `P` type parameter. The
 * application then works with `View<Todo>` instead of `View<Todo, User>`, and the principal type
 * doesn't spread into every signature.
 */
internal class Gated<T : Record, P : Principal>(
    private val db: Db,
    private val table: Table<T>,
    private val policy: Policy<T, P>,
    private val principal: P,
) {
    /** Returns whether the principal can read [record], and records the acquisition if so. */
    fun canRead(record: T): Boolean {
        if (unsafeInEffect) return true
        val permitted = policy.canRead(record, principal)
        // Every way of obtaining a record passes this check, so record the acquisition when it
        // succeeds.
        if (permitted && LeakDetector.enabled) record.recordAcquisition(principal)
        return permitted
    }

    /** Stores [record] if the principal can create it. See [Gate.add]. */
    fun add(record: T): T = Gate.add(db, policy, principal, record)

    override fun toString(): String = "Gated(${table.name}, $principal)"
}

/**
 * The principal for the current thread, if one is set.
 *
 * It's a thread-local instead of a parameter because its only user, the leak detector, runs inside
 * field reads, where there's no signature to pass it through. A thread-local is safe here for the
 * same reason the transaction's write set is: each session runs on a dispatcher that executes one
 * task at a time, so the value is set and cleared within one task.
 *
 * Authorization doesn't depend on it. Access checks take the principal as an explicit argument. This
 * value only lets [LeakDetector] find leaked references.
 */
public object CurrentPrincipal {
    private val ambient = ThreadLocal<Principal?>()

    /** The current thread's principal, or `null` if none is set. */
    internal val current: Principal? get() = ambient.get()

    /** Runs [block] with [principal] as the thread's current principal. */
    public fun <T> with(principal: Principal?, block: () -> T): T {
        val previous = ambient.get()
        ambient.set(principal)
        return try {
            block()
        } finally {
            ambient.set(previous)
        }
    }
}

/**
 * Detects a record being read by a principal that never obtained it.
 *
 * Because holding a reference grants access, the typical bug is a leaked reference: a record
 * obtained for one principal and later read by another, because it was cached, captured in a
 * closure, or kept in a field that outlived the request. The detector doesn't prevent this, but it
 * makes it visible. When it's on, reading a field under a principal that never obtained the record
 * throws [LeakDetected], with the stack trace of the original acquisition as the cause.
 *
 * It's off unless the system property `jetlin.db.leakDetector` is `true`. When it's off, it costs one
 * branch on a flag. Turn it on in tests and development builds. The test task of `:jetlin-db` does.
 *
 * Reads are checked only when a [CurrentPrincipal] is set. Without one, there's no way to know whose
 * read it is.
 */
public object LeakDetector {
    /** Whether the detector is on. It defaults to the `jetlin.db.leakDetector` system property. */
    public var enabled: Boolean =
        System.getProperty("jetlin.db.leakDetector")?.toBooleanStrictOrNull() ?: false
}

/** Thrown by [LeakDetector] when a record is read by a principal that never obtained it. */
public class LeakDetected internal constructor(message: String, acquiredAt: Throwable?) :
    RuntimeException(message, acquiredAt)

/** How many [unsafe] blocks the current thread is inside. */
private val unsafeDepth = ThreadLocal.withInitial { 0 }

/** Whether the current thread is inside an [unsafe] block, where policy checks are off. */
internal val unsafeInEffect: Boolean get() = unsafeDepth.get() > 0

/**
 * Runs [block] with all policy checks disabled.
 *
 * This is the only way to bypass policies. It has a distinctive name, so every use is easy to
 * search for and stands out in review. Use it for work that the application does on its own behalf
 * instead of for a user, such as a migration that backfills a column, an admin console, or a test
 * fixture.
 *
 * Blocks can nest. The checks stay off until the outermost block returns. They're off only on the
 * current thread.
 *
 * @param reason why the bypass is needed, not what the block does.
 */
public fun <T> unsafe(reason: String, block: () -> T): T {
    unsafeDepth.set(unsafeDepth.get() + 1)
    return try {
        block()
    } finally {
        unsafeDepth.set(unsafeDepth.get() - 1)
    }
}
