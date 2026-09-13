package jetlin.db

import kotlin.reflect.KClass
import org.slf4j.LoggerFactory

/**
 * The one doorway between application code and stored records.
 *
 * Every way of obtaining a record — a collection, a lookup, a relation — goes through here and is
 * checked against the entity's policy. That is the load-bearing decision of the whole design: **a
 * reference is authority.** Once application code holds a record, reading its fields is unchecked,
 * because a check on every field read would sit on the recomposition hot path and would make the type
 * flowing through the application a view rather than an entity.
 *
 * What makes that survivable is not that it is safe — it is that its failure mode is findable. The
 * guardrails, all of which live here or next to it:
 *
 * 1. Nothing ungated reaches application code. `IdentityMap`'s record-returning members are internal,
 *    and this object is the only public way through.
 * 2. Relation collections are filtered per read, at the cost of evaluating the policy per row.
 * 3. Writes re-check, because a reference can outlive the check that produced it.
 * 4. There is exactly one escape hatch, it is called [unsafe], and it logs.
 * 5. [LeakDetector] turns a leaked reference from an invisible property into a test failure with the
 *    acquisition site attached.
 *
 * Generated code calls these functions; application code calls the generated accessors. They are public
 * because generated code lives in the application's module, not because they are a pleasant API.
 */
public object Gate {

    /** Every row of [table] this principal may read, as a live list. */
    public fun <T : Record, P : Principal> view(
        db: Db,
        table: Table<T>,
        policy: Policy<T, P>,
        principal: P,
    ): View<T> {
        val gate = Gated(db, table, policy, principal)
        return View(db.resident.rows(table.type), gate::canRead, gate)
    }

    /**
     * The row with this id, or null — including when the row exists and this principal may not read it.
     *
     * Null rather than an exception, and deliberately indistinguishable from "no such row": a
     * distinguishable refusal tells whoever is probing that the row exists.
     */
    public fun <T : Record, P : Principal> find(
        db: Db,
        table: Table<T>,
        policy: Policy<T, P>,
        principal: P,
        id: Id<T>,
    ): T? {
        val row = db.resident.find(table.type, id) ?: return null
        return row.takeIf { Gated(db, table, policy, principal).canRead(it) }
    }

    /**
     * The rows of [table] that [match], filtered by what this principal may read.
     *
     * What an inverse relation is: `project.tasks` is every task whose `project` is this one, minus the
     * ones this principal cannot see. Filtered per read rather than cached, so sharing a project with
     * someone adds its tasks to their open page with no invalidation code anywhere.
     */
    public fun <T : Record, P : Principal> related(
        db: Db,
        table: Table<T>,
        policy: Policy<T, P>,
        principal: P,
        match: (T) -> Boolean,
    ): View<T> {
        val gate = Gated(db, table, policy, principal)
        return View(db.resident.rows(table.type), { row -> match(row) && gate.canRead(row) }, gate = null)
    }

    /** Stores [row], if this principal may create it. */
    public fun <T : Record, P : Principal> add(
        db: Db,
        policy: Policy<T, P>,
        principal: P,
        row: T,
    ): T {
        if (!unsafeInEffect && !policy.canCreate(row, principal)) {
            throw AccessDenied("$principal may not create $row")
        }
        return db.transact { db.insert(row) }
    }

    /** Removes [row], if this principal may delete it. */
    public fun <T : Record, P : Principal> delete(row: T, policy: Policy<T, P>, principal: P) {
        if (!unsafeInEffect && !policy.canDelete(row, principal)) {
            throw AccessDenied("$principal may not delete $row")
        }
        val db = row.database ?: return // Never stored: there is nothing to remove.
        db.transact { db.delete(row) }
    }

    /**
     * Runs a draft block as one transaction, having checked that this principal may write the record at all.
     *
     * The row-level check happens here and the column-level checks happen in the draft's setters, which
     * is what lets one block have `title = "x"` accepted and `archived = true` refused.
     */
    public fun <T : Record, P : Principal> update(
        row: T,
        policy: Policy<T, P>,
        principal: P,
        block: () -> Unit,
    ) {
        if (!unsafeInEffect && !policy.canWrite(row, principal)) {
            throw AccessDenied("$principal may not change $row")
        }
        // A record that was never stored has nothing to commit, so it needs no transaction — which is
        // also the only way to build one up before storing it.
        val db = row.database
        if (db == null) block() else db.transact(block)
    }

    /** Checked by a draft's setter before it writes one column. */
    public fun <T : Record, P : Principal> requireWrite(
        row: T,
        column: Column<T>,
        policy: Policy<T, P>,
        principal: P,
    ) {
        if (!unsafeInEffect && !policy.canWrite(row, column, principal)) {
            throw AccessDenied("$principal may not change ${row::class.simpleName}.${column.name} on $row")
        }
    }
}

/**
 * Resolves a record with no principal, for the one case that cannot have one: working out who the principal is.
 *
 * ```kotlin
 * attributes { call ->
 *     val email = call.sessions.get<Auth>()?.email
 *     mapOf(PrincipalKey to email?.let { db.authenticate(User::class) { user -> user.email == it } })
 * }
 * ```
 *
 * This is the framework's privileged root, and §4.4 of the design calls for exactly one: a system that
 * cannot resolve a principal without a principal cannot start. It evaluates no policy, so it must only be
 * used for that — a `:conventions` test names it as an exception and will fail on a second one added
 * without the same argument.
 */
public fun <T : Record> Db.authenticate(type: KClass<T>, match: (T) -> Boolean): T? =
    resident.rows(type).firstOrNull(match)

/**
 * Stores a record with no policy check, for seeding, fixtures and backfills.
 *
 * Only inside [unsafe], which logs: the first user in an empty database has no principal to be checked
 * against, and the alternative to admitting that is an application with a second, quieter way in. An
 * ordinary write goes through `db.todos.add(…)` and is checked.
 *
 * [id] stores the record under an id of the caller's choosing, for the case where the id is part of the
 * fixture rather than an accident of insertion order — a seeded row something links to by number, a test
 * that asserts on `/todo/1`. The sequence is advanced past it, so an id chosen here is never handed out
 * again; an id that is already resident is refused.
 */
public fun <T : Record> Db.insertUnchecked(row: T, id: Long? = null): T {
    check(unsafeInEffect) {
        "insertUnchecked stores $row without checking any policy, so it is only allowed inside " +
            "unsafe { } — which says why, in the log, every time it runs."
    }
    if (id != null) {
        row.adoptStoredId(id)
        Ids.advanceTo(row::class, id)
    }
    return transact { insert(row) }
}

/**
 * The database a stored record belongs to.
 *
 * Generated inverse relations use it, so that `project.tasks` does not make the caller pass a database it
 * obtained the project from. Application code has no reason to: a record it holds came from somewhere.
 */
public fun databaseOf(row: Record): Db = row.database ?: error(
    "$row is not stored, so it has no database: an inverse relation of an unstored record is empty by " +
        "definition. Store it first.",
)

/**
 * A policy bound to the principal it was resolved for.
 *
 * Exists to close over the principal type so that a [View] can hold the gate without carrying `P` in its
 * own signature — `View<Todo>` rather than `View<Todo, User>`, which would spread the principal type
 * across every signature in an application.
 */
internal class Gated<T : Record, P : Principal>(
    private val db: Db,
    private val table: Table<T>,
    private val policy: Policy<T, P>,
    private val principal: P,
) {
    fun canRead(row: T): Boolean {
        if (unsafeInEffect) return true
        val permitted = policy.canRead(row, principal)
        // The read check is also the acquisition: every way of obtaining a record passes through here.
        if (permitted && LeakDetector.enabled) row.recordAcquisition(principal)
        return permitted
    }

    fun add(row: T): T = Gate.add(db, policy, principal, row)

    override fun toString(): String = "Gated(${table.name}, $principal)"
}

/**
 * The thread's current principal, when something has installed one.
 *
 * Thread-confined rather than passed around because the thing that needs it — the leak detector — runs
 * at field-read depth, underneath any signature that could carry it. Sound here for the same reason the
 * transaction's write set is: a session composes on a dispatcher that runs one task at a time, so the
 * ambient is installed and removed within a single dispatch.
 *
 * Nothing load-bearing depends on it. Authorization happens at acquisition, with the principal passed
 * explicitly; this only makes a leaked reference detectable.
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
 * Catches a record read by a principal that never obtained it.
 *
 * Option A's failure mode is a leaked reference: a record acquired for one principal and then read by
 * another — stashed in a cache, captured in a closure, held in a field that outlived the request. This
 * does not make that impossible. It makes it *findable*: with the detector on, reading a field of a
 * record under a principal that never acquired it fails, and the failure carries the stack trace of where
 * the record was acquired.
 *
 * Off unless `-Djetlin.db.leakDetector=true`, and the check is a branch on a flag read once, so
 * production pays for nothing. Tests and development builds should turn it on; `:jetlin-db`'s own test
 * task does.
 *
 * Limitation worth knowing: the check can only fire when something has installed a [CurrentPrincipal]. A
 * read with no ambient principal is not checked, because nothing knows whose read it is.
 */
public object LeakDetector {
    public var enabled: Boolean =
        System.getProperty("jetlin.db.leakDetector")?.toBooleanStrictOrNull() ?: false
}

/** Thrown by [LeakDetector] when a record is read under a principal that never acquired it. */
public class LeakDetected internal constructor(message: String, acquiredAt: Throwable?) :
    RuntimeException(message, acquiredAt)

private val logger = LoggerFactory.getLogger("jetlin.db")

private val unsafeDepth = ThreadLocal.withInitial { 0 }

internal val unsafeInEffect: Boolean get() = unsafeDepth.get() > 0

/**
 * Runs [block] with every policy check skipped.
 *
 * The one escape hatch, deliberately singular, deliberately greppable, and logged at WARN every time it
 * runs so that it cannot become load-bearing quietly. For the handful of things an application does as
 * itself rather than as a user: a migration backfilling a column, an admin console, a test fixture.
 *
 * [reason] is written to the log, so it should say why rather than what.
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
