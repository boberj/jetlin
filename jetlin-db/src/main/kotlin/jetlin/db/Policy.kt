package jetlin.db

/**
 * The identity the framework acts on behalf of, usually a `User` entity.
 *
 * This marker interface keeps a policy's principal type from accidentally being a string or an ID.
 * The principal should be a live record, not a copy taken at sign-in. A policy that reads
 * `principal.isAdmin` then reads a cell, so revoking the role takes effect immediately.
 */
public interface Principal

/**
 * The access rules for an entity: who can read, change, create, and delete its records.
 *
 * The entity's companion object implements its policy. The KSP processor fails the build for any
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
 * Because all records are in memory, a policy is ordinary Kotlin code that works on live objects.
 * It doesn't have to be translated to SQL, so there's no expression tree and no second
 * representation to keep consistent with the schema. That's the main benefit of keeping the data in
 * memory.
 *
 * ## Before you write a policy
 *
 * Policies run during recomposition. Reading a policy-filtered collection runs the policy for each
 * record on every read. Results aren't cached, because a cached decision can outlive the state it
 * was based on, which would break reactive revocation. So a policy must be cheap, pure, and free of
 * side effects: no I/O and no suspending calls.
 *
 * The principal is a normal parameter, not a context parameter. Only the framework calls policies,
 * so there's nothing to enforce here. The application-facing API uses context parameters instead,
 * where they make a change without a principal in scope a compile error.
 */
public interface Policy<T : Record, P : Principal> {

    /** Returns whether [principal] can obtain and read [record]. */
    public fun canRead(record: T, principal: P): Boolean

    /**
     * Returns whether [principal] can change [record]. By default, anyone who can read it can
     * change it.
     */
    public fun canWrite(record: T, principal: P): Boolean = canRead(record, principal)

    /**
     * Returns whether [principal] can change [column] of [record]. By default, it's [canWrite] for
     * the whole record.
     *
     * This per-column check is why `update { }` takes a block instead of allowing direct assignment.
     * Each assignment in the block is checked separately, so `title = "x"` can be allowed while
     * `archived = true` is refused.
     */
    public fun canWrite(record: T, column: Column<T>, principal: P): Boolean = canWrite(record, principal)

    /** Returns whether [principal] can store the new [record]. By default, it's [canWrite]. */
    public fun canCreate(record: T, principal: P): Boolean = canWrite(record, principal)

    /** Returns whether [principal] can delete [record]. By default, it's [canWrite]. */
    public fun canDelete(record: T, principal: P): Boolean = canWrite(record, principal)
}

/**
 * Returns a policy for records that only their owner can access.
 *
 * ```kotlin
 * companion object : Policy<Todo, User> by owned(Todo::owner)
 * ```
 *
 * This is by far the most common policy. A ready-made version avoids mistakes in hand-written ones,
 * such as `||` where `&&` was meant, which would expose records without any error.
 *
 * @param owner returns the record's owner.
 */
public fun <T : Record, P : Principal> owned(owner: (T) -> P): Policy<T, P> =
    object : Policy<T, P> {
        override fun canRead(record: T, principal: P): Boolean = owner(record) == principal
    }

/**
 * Thrown when a policy refuses a write, a create, or a delete.
 *
 * Reads never throw this. A record the principal can't read is absent: it's missing from collections,
 * and lookups return `null`, because an error would reveal that the record exists. A refused write
 * does throw, because it means a bug or an attack. The caller asked to do something it isn't allowed
 * to do, instead of asking about something it can't see.
 *
 * Thrown inside a transaction, it rolls back the whole transaction. Nothing is committed or applied,
 * so no patch is sent.
 */
public class AccessDenied internal constructor(message: String) : RuntimeException(message)
