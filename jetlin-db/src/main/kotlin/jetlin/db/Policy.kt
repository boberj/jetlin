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
 *     override fun canWrite(record: Todo, principal: User) = record.owner == principal
 *     override fun canRead(record: Todo, principal: User) =
 *         canWrite(record, principal) || record.project?.team in principal.teams
 * }
 *
 * // Written by the owner or an admin, one column admin-only.
 * override fun canWrite(record: Todo, principal: User) = record.owner == principal || principal.isAdmin
 * override fun canWrite(record: Todo, column: Column<Todo>, principal: User) = when (column) {
 *     Todos.archived -> principal.isAdmin
 *     else -> canWrite(record, principal)
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

    /** Returns whether [principal] can change [record]. */
    public fun canWrite(record: T, principal: P): Boolean

    /**
     * Returns whether [principal] can obtain and read [record]. By default, only those who can
     * change it can read it.
     *
     * Override this to share a record more widely than who can change it. The default keeps
     * access as narrow as possible, so sharing is always an explicit choice.
     */
    public fun canRead(record: T, principal: P): Boolean = canWrite(record, principal)

    /**
     * Returns whether [principal] can change [column] of [record]. By default, it's [canWrite] for
     * the whole record.
     *
     * This per-column check is why `update { }` takes a block instead of allowing direct assignment.
     * Each column the block sets is checked separately, so `title = "x"` can be allowed while
     * `archived = true` is refused. [record] is always the record as it was before the block, so the
     * order of the assignments doesn't matter. This check can't see the new value: to restrict
     * *what* a column is set to, use [canCreate].
     *
     * A column rule can only narrow access, never widen it. `update { }` checks [canWrite] for the
     * whole record before the block runs, so this is only asked of principals who can already change
     * the record. Returning `true` here for anyone else has no effect. That's why the admin-only
     * example above also admits admins in [canWrite] for the whole record: without that, only an
     * admin who owned the todo could archive it. To let a principal change a column of a record they
     * otherwise can't, widen [canWrite] for the whole record and narrow the other columns here.
     */
    public fun canWrite(record: T, column: Column<T>, principal: P): Boolean = canWrite(record, principal)

    /**
     * Returns whether [principal] can store [record] as it is. By default, it's [canWrite].
     *
     * This is checked when a record is added, and again on the finished record after every
     * `update { }`. An update can't leave a record in a state its principal couldn't have created,
     * so this is where rules about values belong, such as "a todo can only be shared with your own
     * team". Without it, a principal could create a record that's allowed and then change it into
     * one that isn't, such as handing it to another owner.
     */
    public fun canCreate(record: T, principal: P): Boolean = canWrite(record, principal)

    /** Returns whether [principal] can delete [record]. By default, it's [canWrite]. */
    public fun canDelete(record: T, principal: P): Boolean = canWrite(record, principal)

    /**
     * Returns whether [principal] can make [to] the owner of [record]. By default, nobody can.
     *
     * A transfer is the one write that's meant to leave a record in a state its principal couldn't
     * have created, so `update { }` refuses it: it checks the finished record with [canCreate]. The
     * generated `transferTo(to)` is the explicit path instead, and this is its whole rule. It doesn't
     * also require [canWrite], so a policy can decide who may pull a record as well as who may give
     * one away:
     *
     * ```kotlin
     * // The owner can hand it to a teammate.
     * override fun canTransfer(record: Doc, to: User, principal: User) =
     *     record.owner == principal && to.team == principal.team
     *
     * // The owner offers it with `update { offeredTo = bob }`, and only Bob can take it.
     * override fun canTransfer(record: Doc, to: User, principal: User) =
     *     to == principal && record.offeredTo == principal
     * ```
     *
     * `transferTo` exists only for an entity whose `@Owner` column is a `var` of the principal type.
     */
    public fun canTransfer(record: T, to: P, principal: P): Boolean = false
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
        override fun canWrite(record: T, principal: P): Boolean = owner(record) == principal
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
