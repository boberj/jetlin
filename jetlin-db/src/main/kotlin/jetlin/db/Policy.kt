package jetlin.db

/**
 * Whoever the framework is acting on behalf of — normally a `User` entity.
 *
 * A marker, so that a policy's principal type cannot accidentally be a string or an id. The principal is
 * deliberately a live record rather than a snapshot taken at login: a policy that reads
 * `principal.isAdmin` reads a cell, which is what makes revocation reactive.
 */
public interface Principal

/**
 * Who may see and change a record.
 *
 * Declared on the entity's companion, which is what makes it impossible to have an entity with no
 * access rules — KSP fails the build on one.
 *
 * ```kotlin
 * // Owner only.
 * companion object : Policy<Todo, User> by owned(Todo::owner)
 *
 * // Shared by team, written by the owner.
 * companion object : Policy<Todo, User> {
 *     override fun canRead(record: Todo, principal: User) =
 *         record.owner == principal || record.project?.team in principal.teams
 *     override fun canWrite(record: Todo, principal: User) = record.owner == principal
 * }
 *
 * // Read by the team, one column admin-only.
 * override fun canWrite(record: Todo, column: Column<Todo>, principal: User) = when (column) {
 *     Todos.archived -> principal.isAdmin
 *     else -> record.project?.team in principal.teams
 * }
 * ```
 *
 * Because the graph is resident, a policy is a plain Kotlin expression over live objects. There is no
 * SQL translation, no expression tree and no second representation to keep in step with the schema —
 * which is the main thing the memory image buys.
 *
 * ## Two consequences worth knowing before writing one
 *
 * **Policies sit on the recomposition hot path.** Reading a policy-filtered collection evaluates the
 * policy per record, per read, and deliberately does not cache: a cached decision outlives the state it
 * was based on, which is exactly how reactive revocation gets broken. So a policy must be cheap, pure
 * and free of side effects. No IO, no suspending calls.
 *
 * **The principal is an ordinary parameter, not a context parameter.** Policies are called by the
 * framework and never by application code, so there is nothing to protect at this layer. Context
 * parameters are for the application-facing API, where they stop a mutation from compiling without a
 * principal in scope.
 */
public interface Policy<T : Record, P : Principal> {

    /** Whether [principal] may obtain and read [record] at all. */
    public fun canRead(record: T, principal: P): Boolean

    /** Whether [principal] may change [record]. Defaults to "whoever can read it can write it". */
    public fun canWrite(record: T, principal: P): Boolean = canRead(record, principal)

    /**
     * Whether [principal] may change one particular column.
     *
     * The reason `update { }` takes a block rather than assigning fields directly: one block can have
     * `title = "x"` accepted and `archived = true` refused.
     */
    public fun canWrite(record: T, column: Column<T>, principal: P): Boolean = canWrite(record, principal)

    /** Whether [principal] may store [record] in the first place. */
    public fun canCreate(record: T, principal: P): Boolean = canWrite(record, principal)

    public fun canDelete(record: T, principal: P): Boolean = canWrite(record, principal)
}

/**
 * The policy for a record only its owner may see.
 *
 * ```kotlin
 * companion object : Policy<Todo, User> by owned(Todo::owner)
 * ```
 *
 * The commonest shape by a wide margin, and the one most likely to be written wrong by hand — an
 * `||` that was meant to be `&&` in a policy is a silent disclosure, so the shape that needs no
 * expression is worth having.
 */
public fun <T : Record, P : Principal> owned(owner: (T) -> P): Policy<T, P> =
    object : Policy<T, P> {
        override fun canRead(record: T, principal: P): Boolean = owner(record) == principal
    }

/**
 * Thrown when a principal tries to change or store something a policy refuses.
 *
 * Reads do not throw: a record a principal may not read is absent — missing from collections, `null` from a
 * lookup — because a thrown read would disclose that the record exists. Writes do throw, because a write
 * a policy refuses is a bug or an attack, and either way the caller asked for something impossible
 * rather than asking about something invisible.
 *
 * Thrown inside a transaction it unwinds the whole of it: nothing is committed and nothing is applied,
 * so the denial produces no patch at all.
 */
public class AccessDenied internal constructor(message: String) : RuntimeException(message)
