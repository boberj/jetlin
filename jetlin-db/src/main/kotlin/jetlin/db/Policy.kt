package jetlin.db

/**
 * Whoever the framework is acting on behalf of — normally a `User` entity.
 *
 * A marker, so that a policy's viewer type cannot accidentally be a string or an id. The viewer is
 * deliberately a live record rather than a snapshot taken at login: a policy that reads
 * `viewer.isAdmin` reads a cell, which is what makes revocation reactive.
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
 *     override fun canRead(row: Todo, viewer: User) =
 *         row.owner == viewer || row.project?.team in viewer.teams
 *     override fun canWrite(row: Todo, viewer: User) = row.owner == viewer
 * }
 *
 * // Read by the team, one column admin-only.
 * override fun canWrite(row: Todo, column: Column<Todo>, viewer: User) = when (column) {
 *     Todos.archived -> viewer.isAdmin
 *     else -> row.project?.team in viewer.teams
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
 * policy per row, per read, and deliberately does not cache: a cached decision outlives the state it
 * was based on, which is exactly how reactive revocation gets broken. So a policy must be cheap, pure
 * and free of side effects. No IO, no suspending calls.
 *
 * **The viewer is an ordinary parameter, not a context parameter.** Policies are called by the
 * framework and never by application code, so there is nothing to protect at this layer. Context
 * parameters are for the application-facing API, where they stop a mutation from compiling without a
 * viewer in scope.
 */
public interface Policy<T : Record, V : Principal> {

    /** Whether [viewer] may obtain and read [row] at all. */
    public fun canRead(row: T, viewer: V): Boolean

    /** Whether [viewer] may change [row]. Defaults to "whoever can read it can write it". */
    public fun canWrite(row: T, viewer: V): Boolean = canRead(row, viewer)

    /**
     * Whether [viewer] may change one particular column.
     *
     * The reason `update { }` takes a block rather than assigning fields directly: one block can have
     * `title = "x"` accepted and `archived = true` refused.
     */
    public fun canWrite(row: T, column: Column<T>, viewer: V): Boolean = canWrite(row, viewer)

    /** Whether [viewer] may store [row] in the first place. */
    public fun canCreate(row: T, viewer: V): Boolean = canWrite(row, viewer)

    public fun canDelete(row: T, viewer: V): Boolean = canWrite(row, viewer)
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
public fun <T : Record, V : Principal> owned(owner: (T) -> V): Policy<T, V> =
    object : Policy<T, V> {
        override fun canRead(row: T, viewer: V): Boolean = owner(row) == viewer
    }

/**
 * Thrown when a viewer tries to change or store something a policy refuses.
 *
 * Reads do not throw: a row a viewer may not read is absent — missing from collections, `null` from a
 * lookup — because a thrown read would disclose that the row exists. Writes do throw, because a write
 * a policy refuses is a bug or an attack, and either way the caller asked for something impossible
 * rather than asking about something invisible.
 *
 * Thrown inside a transaction it unwinds the whole of it: nothing is committed and nothing is applied,
 * so the denial produces no patch at all.
 */
public class AccessDenied internal constructor(message: String) : RuntimeException(message)
