package jetlin.db

/**
 * The identity the framework acts on behalf of, usually a `User` entity.
 *
 * This marker interface stops a policy's principal type from accidentally being a string or an id.
 * The principal is meant to be a live record, not a copy taken at sign-in. A policy that reads
 * `principal.isAdmin` then reads a cell, so revoking the role takes effect immediately.
 */
public interface Principal

/**
 * Access rules for an entity: who may read, change, create and delete its records.
 *
 * A policy is implemented by the entity's companion object. The KSP processor fails the build for any
 * entity without one, so every entity has access rules.
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
 * Because all records are in memory, a policy is ordinary Kotlin code operating on live objects. It
 * doesn't have to be translated to SQL, so there is no expression tree and no second representation
 * to keep consistent with the schema. This is the main benefit of keeping the data in memory.
 *
 * ## Things to know before writing a policy
 *
 * **Policies run during recomposition.** Reading a policy-filtered collection evaluates the policy for
 * each record on every read. Results are not cached, because a cached decision can outlive the state
 * it was based on, which would break reactive revocation. A policy must therefore be cheap, pure and
 * free of side effects: no IO and no suspending calls.
 *
 * **The principal is a normal parameter, not a context parameter.** Only the framework calls policies,
 * so there is nothing to enforce here. Context parameters are used in the application-facing API
 * instead, where they make a mutation without a principal in scope a compile error.
 */
public interface Policy<T : Record, P : Principal> {

    /** Whether [principal] may obtain and read [record]. */
    public fun canRead(record: T, principal: P): Boolean

    /** Whether [principal] may change [record]. By default, anyone who can read it can change it. */
    public fun canWrite(record: T, principal: P): Boolean = canRead(record, principal)

    /**
     * Whether [principal] may change a specific column of [record].
     *
     * This per-column check is why `update { }` takes a block instead of allowing direct assignment:
     * each assignment in the block is checked separately, so `title = "x"` can be allowed while
     * `archived = true` is refused.
     */
    public fun canWrite(record: T, column: Column<T>, principal: P): Boolean = canWrite(record, principal)

    /** Whether [principal] may store a new [record]. */
    public fun canCreate(record: T, principal: P): Boolean = canWrite(record, principal)

    public fun canDelete(record: T, principal: P): Boolean = canWrite(record, principal)
}

/**
 * A policy for records that only their owner may access.
 *
 * ```kotlin
 * companion object : Policy<Todo, User> by owned(Todo::owner)
 * ```
 *
 * This is by far the most common policy. Having a ready-made version avoids hand-written mistakes,
 * such as using `||` where `&&` was meant, which would silently expose records.
 */
public fun <T : Record, P : Principal> owned(owner: (T) -> P): Policy<T, P> =
    object : Policy<T, P> {
        override fun canRead(record: T, principal: P): Boolean = owner(record) == principal
    }

/**
 * Thrown when a policy refuses a write, create or delete.
 *
 * Reads never throw this. A record the principal may not read is simply absent: missing from
 * collections and `null` from lookups, because an error would reveal that the record exists. A refused
 * write does throw, because it indicates a bug or an attack; the caller asked to do something it isn't
 * allowed to, rather than asking about something it can't see.
 *
 * When thrown inside a transaction, it rolls back the entire transaction. Nothing is committed or
 * applied, so no patch is sent.
 */
public class AccessDenied internal constructor(message: String) : RuntimeException(message)
