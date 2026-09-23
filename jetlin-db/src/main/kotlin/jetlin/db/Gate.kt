package jetlin.db

import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

/**
 * The only route from application code to stored records.
 *
 * Every way of obtaining a record (a collection, a lookup by id, a relation) goes through this object
 * and is checked against the entity's policy. The central design decision is that **holding a
 * reference grants access**: once application code has a record, reading its fields is not checked.
 * Checking every field read would add work to every recomposition, and it would mean the application
 * handles a per-principal view object instead of the entity itself.
 *
 * That model is not leak-proof, so the following safeguards exist to make leaks unlikely and easy to
 * find:
 *
 * 1. No unchecked lookup is reachable from application code. The members of `IdentityMap` that return
 *    records are internal, and this object is the only public way to reach them.
 * 2. Relation collections are filtered on every read, at the cost of evaluating the policy for each
 *    record.
 * 3. Writes are checked again, because a reference can outlive the check that produced it.
 * 4. There is exactly one way to bypass the checks, [unsafe], and it logs every use.
 * 5. [LeakDetector] turns a leaked reference into a test failure that includes the stack trace where
 *    the record was obtained.
 *
 * Application code calls the generated accessors, and those call these functions. They are public only
 * because the generated code is compiled into the application's module; they are not meant to be
 * called by hand.
 */
public object Gate {

    /** A live list of the records in [table] that this principal may read. */
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
     * Returns the record with this id, or null if it doesn't exist or this principal may not read it.
     *
     * Both cases return null so they can't be told apart. A distinct error for "not allowed" would
     * confirm to someone probing ids that the record exists.
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
     * The records in [table] that satisfy [match] and that this principal may read.
     *
     * This implements inverse relations: `project.tasks` is every task whose `project` is this project,
     * excluding the ones this principal can't see. The filter runs on every read instead of being
     * cached, so when a project is shared with someone, its tasks appear on their open page without any
     * invalidation code.
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

    /** Stores [record] if this principal may create it, and throws [AccessDenied] otherwise. */
    public fun <T : Record, P : Principal> add(
        db: Db,
        policy: Policy<T, P>,
        principal: P,
        record: T,
    ): T {
        if (!unsafeInEffect && !policy.canCreate(record, principal)) {
            throw AccessDenied("$principal may not create $record")
        }
        return db.transact { db.insert(record) }
    }

    /** Deletes [record] if this principal may delete it, and throws [AccessDenied] otherwise. */
    public fun <T : Record, P : Principal> delete(record: T, policy: Policy<T, P>, principal: P) {
        if (!unsafeInEffect && !policy.canDelete(record, principal)) {
            throw AccessDenied("$principal may not delete $record")
        }
        val db = record.database ?: return // Not stored, so there is nothing to delete.
        db.transact { db.delete(record) }
    }

    /**
     * Checks that this principal may write the record, then runs a draft block as one transaction.
     *
     * This function performs the record-level check. Column-level checks happen in the draft's
     * setters, so a single block can be allowed to set `title` and refused for `archived`. A refused
     * column throws and rolls back the whole block.
     */
    public fun <T : Record, P : Principal> update(
        record: T,
        policy: Policy<T, P>,
        principal: P,
        block: () -> Unit,
    ) {
        if (!unsafeInEffect && !policy.canWrite(record, principal)) {
            throw AccessDenied("$principal may not change $record")
        }
        // A record that hasn't been stored has nothing to commit, so no transaction is needed. This is
        // also what allows setting up a new record's fields before storing it.
        val db = record.database
        if (db == null) block() else db.transact(block)
    }

    /** Called by a draft's setter before writing a column. Throws [AccessDenied] if not permitted. */
    public fun <T : Record, P : Principal> requireWrite(
        record: T,
        column: Column<T>,
        policy: Policy<T, P>,
        principal: P,
    ) {
        if (!unsafeInEffect && !policy.canWrite(record, column, principal)) {
            throw AccessDenied(
                "$principal may not change ${record::class.simpleName}.${column.name} on $record",
            )
        }
    }
}

/**
 * Looks up a record without any policy check. This is only for determining who the principal is,
 * which by definition has to happen before there is a principal to check against.
 *
 * ```kotlin
 * attributes { call ->
 *     val email = call.sessions.get<Auth>()?.email
 *     mapOf(PrincipalKey to email?.let { db.authenticate(User::class) { user -> user.email == it } })
 * }
 * ```
 *
 * This is the framework's privileged entry point. §4.4 of the design plan calls for exactly one,
 * because resolving the principal can't require a principal. Since it evaluates no policy, don't use
 * it for anything else. A `:conventions` test lists it as an allowed exception, and fails if another
 * unchecked entry point is added.
 */
public fun <T : Record> Db.authenticate(type: KClass<T>, match: (T) -> Boolean): T? =
    resident.records(type).firstOrNull(match)

/**
 * Stores a record without a policy check, for seeding, fixtures and backfills.
 *
 * It may only be called inside [unsafe], which logs each use. Seeding needs an unchecked insert
 * because the first user in an empty database has no principal to be checked against. Requiring
 * [unsafe] keeps this from becoming a second, unlogged way around the policies. Normal writes use
 * `db.todos.add(…)`, which is checked.
 *
 * Pass [id] to store the record under a specific id, for fixtures where the id matters, such as a
 * seeded record that something links to by number or a test that requests `/todo/1`. The id sequence
 * is advanced past [id] so it won't be allocated again. An id that is already in use is rejected.
 */
public fun <T : Record> Db.insertUnchecked(record: T, id: Long? = null): T {
    check(unsafeInEffect) {
        "insertUnchecked stores $record without checking any policy, so it is only allowed inside " +
            "unsafe { } — which says why, in the log, every time it runs."
    }
    if (id != null) {
        record.adoptStoredId(id)
        Ids.advanceTo(record::class, id)
    }
    return transact { insert(record) }
}

/**
 * Returns the database a stored record belongs to.
 *
 * Generated inverse relations use this so that `project.tasks` doesn't need a database argument.
 * Application code shouldn't need it.
 */
public fun databaseOf(record: Record): Db = record.database ?: error(
    "$record is not stored, so it has no database: an inverse relation of an unstored record is empty by " +
        "definition. Store it first.",
)

/**
 * A policy bound to a specific principal.
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
    fun canRead(record: T): Boolean {
        if (unsafeInEffect) return true
        val permitted = policy.canRead(record, principal)
        // Every way of obtaining a record passes this check, so a successful check is where an
        // acquisition is recorded.
        if (permitted && LeakDetector.enabled) record.recordAcquisition(principal)
        return permitted
    }

    fun add(record: T): T = Gate.add(db, policy, principal, record)

    override fun toString(): String = "Gated(${table.name}, $principal)"
}

/**
 * The principal for the current thread, if one has been set.
 *
 * This is a thread-local instead of a parameter because its only user, the leak detector, runs inside
 * field reads, where there is no signature to pass it through. A thread-local is safe here for the
 * same reason the transaction's write set is: each session runs on a dispatcher that executes one
 * task at a time, so the value is set and cleared within a single task.
 *
 * Authorization does not depend on it. Access checks take the principal as an explicit argument; this
 * value only lets leaked references be detected.
 */
public object CurrentPrincipal {
    private val ambient = ThreadLocal<Principal?>()

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
 * Because holding a reference grants access, the typical bug is a leaked reference: a record obtained
 * for one principal and later read by another, because it was cached, captured in a closure, or kept
 * in a field that outlived the request. The detector doesn't prevent this, but it makes it visible.
 * When it is enabled, reading a field under a principal that never obtained the record throws
 * [LeakDetected], with the stack trace of the original acquisition as the cause.
 *
 * It is disabled unless `-Djetlin.db.leakDetector=true` is set. When disabled, the cost is one branch
 * on a flag that is read once. Enable it in tests and development builds; `:jetlin-db`'s own test task
 * does.
 *
 * Limitation: reads are only checked when a [CurrentPrincipal] has been set. Without one, there is no
 * way to know whose read it is.
 */
public object LeakDetector {
    public var enabled: Boolean =
        System.getProperty("jetlin.db.leakDetector")?.toBooleanStrictOrNull() ?: false
}

/** Thrown by [LeakDetector] when a record is read by a principal that never obtained it. */
public class LeakDetected internal constructor(message: String, acquiredAt: Throwable?) :
    RuntimeException(message, acquiredAt)

private val logger = LoggerFactory.getLogger("jetlin.db")

private val unsafeDepth = ThreadLocal.withInitial { 0 }

internal val unsafeInEffect: Boolean get() = unsafeDepth.get() > 0

/**
 * Runs [block] with all policy checks disabled.
 *
 * This is the only way to bypass policies. It has a distinctive name so uses are easy to search for,
 * and it logs a warning on every call so that nobody comes to rely on it without noticing. Use it for
 * work the application does on its own behalf rather than for a user, such as a migration backfilling
 * a column, an admin console or a test fixture.
 *
 * [reason] is written to the log. Describe why the bypass is needed, not what the block does.
 */
public fun <T> unsafe(reason: String, block: () -> T): T {
    logger.warn("jetlin-db: policy checks bypassed — {}", reason)
    unsafeDepth.set(unsafeDepth.get() + 1)
    return try {
        block()
    } finally {
        unsafeDepth.set(unsafeDepth.get() - 1)
    }
}
