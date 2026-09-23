package jetlin.db

import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

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
 * 4. There's exactly one way to bypass the checks, [unsafe], and it logs every use.
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
        if (!unsafeInEffect && !policy.canCreate(record, principal)) {
            throw AccessDenied("$principal may not create $record")
        }
        return db.transact { db.insert(record) }
    }

    /** Deletes [record] if [principal] can delete it, and throws [AccessDenied] otherwise. */
    public fun <T : Record, P : Principal> delete(record: T, policy: Policy<T, P>, principal: P) {
        if (!unsafeInEffect && !policy.canDelete(record, principal)) {
            throw AccessDenied("$principal may not delete $record")
        }
        val db = record.database ?: return // It isn't stored, so there's nothing to delete.
        db.transact { db.delete(record) }
    }

    /**
     * Checks that [principal] can write [record], then runs a draft block as one transaction.
     *
     * This function performs the record-level check. Column-level checks happen in the draft's
     * setters, so one block can be allowed to set `title` and refused for `archived`. A refused column
     * throws [AccessDenied] and rolls back the whole block.
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
        // A record that isn't stored has nothing to commit, so it needs no transaction. That's also
        // what lets you set up a new record's fields before storing it.
        val db = record.database
        if (db == null) block() else db.transact(block)
    }

    /**
     * Checks that [principal] can write [column] of [record]. A draft's setter calls this first.
     *
     * @throws AccessDenied if the write isn't allowed.
     */
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
 * You can call it only inside [unsafe], which logs each use. Seeding needs an unchecked insert
 * because the first user in an empty database has no principal to be checked against. Requiring
 * [unsafe] keeps this from becoming a second, unlogged way around the policies. Normal writes use
 * `db.todos.add(…)`, which is checked.
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
            "unsafe { } — which says why, in the log, every time it runs."
    }
    if (id != null) {
        record.adoptStoredId(id)
        Ids.advanceTo(record::class, id)
    }
    return transact { insert(record) }
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

/** The logger for policy bypasses. */
private val logger = LoggerFactory.getLogger("jetlin.db")

/** How many [unsafe] blocks the current thread is inside. */
private val unsafeDepth = ThreadLocal.withInitial { 0 }

/** Whether the current thread is inside an [unsafe] block, where policy checks are off. */
internal val unsafeInEffect: Boolean get() = unsafeDepth.get() > 0

/**
 * Runs [block] with all policy checks disabled.
 *
 * This is the only way to bypass policies. It has a distinctive name, so uses are easy to search
 * for, and it logs a warning on every call, so nobody comes to rely on it without noticing. Use it
 * for work that the application does on its own behalf instead of for a user, such as a migration
 * that backfills a column, an admin console, or a test fixture.
 *
 * Blocks can nest. The checks stay off until the outermost block returns. They're off only on the
 * current thread.
 *
 * @param reason why the bypass is needed, not what the block does. It's written to the log.
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
