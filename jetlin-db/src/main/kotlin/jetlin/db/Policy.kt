package jetlin.db

/**
 * The user, or other identity, that the application acts for. Usually a `User` entity.
 *
 * Every read and write in `jetlin-db` happens on behalf of a principal, and every access rule is a
 * question about one: "is the principal the owner?", "is the principal an admin?". The framework
 * never guesses who that is. Your application finds the signed-in user and passes it in.
 *
 * ```kotlin
 * @Entity
 * class User(name: String, admin: Boolean = false) : Record(), Principal {
 *     var name: String by column(name)
 *     var admin: Boolean by column(admin)
 *     // ...
 * }
 * ```
 *
 * Implementing this interface is all it takes. It exists so that a policy's principal type can't
 * accidentally be a `String` or an ID. Use the stored record itself, not a copy taken at sign-in.
 * Then a rule that reads `principal.admin` sees the current value, and removing someone's admin role
 * takes effect on their open pages right away.
 */
public interface Principal

/**
 * The access rules for one entity: who can see its records, and who can create, change, and delete
 * them.
 *
 * Every entity needs a policy. Declare it on the entity's companion object. The KSP processor fails
 * the build for an entity without one.
 *
 * ## Choose how to write it
 *
 * Most policies are built from conditions and grants with [policy]. Each grant reads like a
 * sentence, and the framework checks each one at every point it matters:
 *
 * ```kotlin
 * companion object : Policy<Todo, User> by policy({
 *     principal() equalTo record(Todo::owner) implies canEdit()
 *     principal() map User::team equalTo record(Todo::team) implies canRead()
 * })
 * ```
 *
 * If only the owner can do anything with a record, use [owned]:
 *
 * ```kotlin
 * companion object : Policy<Note, User> by owned(Note::owner)
 * ```
 *
 * If neither fits, implement this interface yourself. Only [canWrite] is required. Every other
 * method has a default that builds on it:
 *
 * ```kotlin
 * companion object : Policy<User, User> {
 *     // Users can change their own record, and admins can change anyone's.
 *     override fun canWrite(record: User, principal: User) = record == principal || principal.admin
 *
 *     // Everyone can see every user.
 *     override fun canRead(record: User, principal: User) = true
 * }
 * ```
 *
 * ## When each method runs
 *
 * | You do this                     | The framework asks                                   |
 * |---------------------------------|------------------------------------------------------|
 * | Read a collection or look up    | [canRead], for each record                           |
 * | `db.todos.add(todo)`            | [canCreate]                                          |
 * | `todo.update { title = "x" }`   | [canChange], which by default asks [canWrite], then  |
 * |                                 | [canWrite] for each changed column, then [canCreate] |
 * |                                 | on the todo as it would be afterwards                |
 * | `todo.delete()`                 | [canDelete]                                          |
 *
 * A record the principal can't read is left out of collections, and a lookup returns `null`, as if
 * the record didn't exist. A refused create, change, or delete throws [AccessDenied].
 *
 * ## Keep policies fast and free of side effects
 *
 * Policies run while pages render. Reading a collection runs [canRead] for every record in it, every
 * time the page reads it. The framework doesn't cache the answers, because a cached answer could
 * outlive the data it was based on: someone who just lost access would keep seeing the record. So a
 * policy must be quick, and must only read. Don't do I/O, don't call suspending functions, and don't
 * change any state. Everything a policy needs is already in memory.
 *
 * The principal is a normal parameter, not a context parameter, because only the framework calls
 * these methods. The functions your application calls, such as `update { }`, take the principal
 * from context instead, so calling them without a principal in scope doesn't compile.
 */
public interface Policy<T : Record, P : Principal> {

    /**
     * Checks whether [principal] can change [record].
     *
     * This is the one method you have to write. Everything else builds on it by default: someone who
     * can change a record can also read it, delete it, and create one like it.
     *
     * ```kotlin
     * override fun canWrite(record: Todo, principal: User) = record.owner == principal
     * ```
     *
     * @param record The record as it is now.
     * @param principal The principal that wants to change it.
     * @return True if [principal] can change [record]; false otherwise.
     */
    public fun canWrite(record: T, principal: P): Boolean

    /**
     * Checks whether [principal] can see [record].
     *
     * By default, only someone who can change a record can see it. Override this to show a record
     * to more people than can change it:
     *
     * ```kotlin
     * // Teammates can see a shared todo, but only its owner can change it.
     * override fun canRead(record: Todo, principal: User) =
     *     canWrite(record, principal) || (record.team != null && record.team == principal.team)
     * ```
     *
     * @return True if [principal] can see [record]; false otherwise.
     */
    public fun canRead(record: T, principal: P): Boolean = canWrite(record, principal)

    /**
     * Checks whether [principal] can change [column] of [record].
     *
     * By default, anyone who can change the record can change every column of it. Override this to
     * protect one column more than the rest:
     *
     * ```kotlin
     * // Owners can edit their todos, but only admins can archive them.
     * override fun canWrite(record: Todo, column: Column<Todo>, principal: User) = when (column) {
     *     Todos.archived -> principal.admin
     *     else -> canWrite(record, principal)
     * }
     * ```
     *
     * The default [canChange] asks this only for principals who passed [canWrite] for the whole
     * record, so this rule can only take permissions away. In the example above, an admin who can't
     * change the todo still can't archive it. If admins should be able to archive anyone's todo, let
     * them pass [canWrite] too.
     *
     * [record] is the record as it was before the change, so this rule can't see the new value. To
     * limit the values a column can take, use [canCreate].
     *
     * @return True if [principal] can change [column]; false otherwise.
     */
    public fun canWrite(record: T, column: Column<T>, principal: P): Boolean = canWrite(record, principal)

    /**
     * Checks whether [principal] can store [record] with the values it has.
     *
     * The framework asks this when a record is added, and, by default, again after every change, on
     * the record as it would be afterwards. So an update can never produce a record that adding it
     * would have refused. Without that second check, someone could create a record they're allowed
     * to, then change its owner to someone else.
     *
     * This makes it the place for rules about values:
     *
     * ```kotlin
     * // A todo can only be shared with the principal's own team.
     * override fun canCreate(record: Todo, principal: User) =
     *     canWrite(record, principal) && (record.team == null || record.team == principal.team)
     * ```
     *
     * By default, it's [canWrite].
     *
     * @return True if [principal] can store [record]; false otherwise.
     */
    public fun canCreate(record: T, principal: P): Boolean = canWrite(record, principal)

    /**
     * Checks whether [principal] can delete [record]. By default, it's [canWrite].
     *
     * @return True if [principal] can delete [record]; false otherwise.
     */
    public fun canDelete(record: T, principal: P): Boolean = canWrite(record, principal)

    /**
     * Checks whether [principal] can make [change], and decides every `update { }`.
     *
     * [change] holds the record as it is now, the values the update wants to set, and a way to look
     * at the record as it would be afterwards. By default, a change is allowed if all of these hold:
     *
     * 1. [principal] can change the record as it is now ([canWrite]).
     * 2. [principal] can change each column whose value changes ([canWrite] for that column).
     * 3. [principal] could store the record as it would be afterwards ([canCreate]).
     *
     * Override this only when a rule needs the old and the new values together, and [policy] has no
     * grant for it. For example, to let the person a document is offered to accept it:
     *
     * ```kotlin
     * override fun canChange(change: Change<Doc>, principal: User): Boolean {
     *     val doc = change.record
     *     val accepting = doc.offeredTo == principal &&
     *         change.columns == setOf(Docs.owner, Docs.offeredTo) &&
     *         change.newValue(Doc::owner) == principal &&
     *         change.newValue(Doc::offeredTo) == null
     *     return accepting || super.canChange(change, principal)
     * }
     * ```
     *
     * When you override it, check both sides. A rule that only looks at the new values lets anyone
     * who holds a record rewrite it into one they're allowed to have: for example, by setting its
     * owner to themselves.
     *
     * @return True if [principal] can make [change]; false otherwise.
     */
    public fun canChange(change: Change<T>, principal: P): Boolean {
        val record = change.record
        return canWrite(record, principal) &&
            change.columns.all { column -> canWrite(record, column, principal) } &&
            change.afterwards { changed -> canCreate(changed, principal) }
    }

    /**
     * Describes this policy's grants for tooling, such as `jetlin-db-testing`, or returns `null` if
     * the policy wasn't built with [policy].
     *
     * You don't need to override it. Policies built with [policy] describe themselves, and tooling
     * treats a hand-written policy as one it can only test from the outside.
     *
     * @return The grants, or `null` if they can't be described.
     */
    @JetlinDbTooling
    public fun describeGrants(): List<GrantDescription>? = null
}

/**
 * Returns a policy for records that only their owner can see or change.
 *
 * ```kotlin
 * @Entity
 * class Note(@Owner val owner: User, text: String) : Record() {
 *     var text: String by column(text)
 *
 *     companion object : Policy<Note, User> by owned(Note::owner)
 * }
 * ```
 *
 * This is the most common policy. Using it avoids the small mistakes that hand-written rules are
 * prone to, such as writing `||` where `&&` was meant, which exposes records without any error.
 *
 * @param owner Returns the record's owner.
 */
public fun <T : Record, P : Principal> owned(owner: (T) -> P): Policy<T, P> =
    object : Policy<T, P> {
        override fun canWrite(record: T, principal: P): Boolean = owner(record) == principal
    }

/**
 * Thrown when a policy refuses to let a principal create, change, or delete a record.
 *
 * The message says who was refused, what they tried, and, for policies built with [policy], which
 * grant they were missing:
 *
 * ```
 * User#2 may not change Todo#7: archived can only be changed by admins
 * ```
 *
 * Reading never throws this. A record the principal can't see is left out, as if it didn't exist,
 * because an error would tell them that it does. A refused write throws, because it means the
 * application offered something it shouldn't have, or someone is sending events the page never
 * showed them.
 *
 * Throwing it inside a transaction rolls back the whole transaction, so nothing is saved, and no
 * page sees any of the changes.
 */
public class AccessDenied internal constructor(message: String) : RuntimeException(message)
