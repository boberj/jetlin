package jetlin.samples.teams

import jetlin.db.Column
import jetlin.db.Entity
import jetlin.db.Owner
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.owned

/**
 * The sample's entities and their access policies.
 *
 * The entities cover the three access patterns the framework is designed for:
 *
 * 1. Owner only: [Note], with the `owned()` policy.
 * 2. Shared through a related record: its owner can read a [Todo], and so can anyone on the team
 *    it's shared with.
 * 3. Widely readable, with one restricted column: only an admin can write [Todo.archived].
 *
 * Each policy is ordinary Kotlin code over live objects, and reads state that can change while a page
 * is open. That's what makes revocation reactive. When a todo is unshared from a team, it disappears
 * from the teammates' open pages, and there's no invalidation code anywhere in the sample.
 */
@Entity
class Team(name: String) : Record() {
    var name: String by column(name)

    companion object : Policy<Team, User> {
        /** A team is visible to its members. */
        override fun canRead(record: Team, principal: User): Boolean = principal.team == record

        /** Only an admin can change a team. */
        override fun canWrite(record: Team, principal: User): Boolean = principal.admin
    }
}

/** A person who can sign in, and the principal that every policy in the sample checks. */
@Entity
class User(
    name: String,
    email: String,
    admin: Boolean = false,
) : Record(), Principal {
    var name: String by column(name)
    var email: String by column(email)
    var admin: Boolean by column(admin)

    /**
     * The user's team, or `null`.
     *
     * [Todo]'s policy reads this, so moving a user to another team updates what their open pages
     * show, without any code notifying them.
     */
    var team: Team? by reference()

    companion object : Policy<User, User> {
        /** Every signed-in user can see other users, so a shared todo can show who owns it. */
        override fun canRead(record: User, principal: User): Boolean = true

        /** Users can change themselves, and an admin can change anyone. */
        override fun canWrite(record: User, principal: User): Boolean = record == principal || principal.admin
    }
}

/** Pattern 1: only the owner can read or write a note. */
@Entity
class Note(
    @Owner val owner: User,
    text: String,
) : Record() {
    var text: String by column(text)

    companion object : Policy<Note, User> by owned(Note::owner)
}

/** A todo that its owner can share with a team. */
@Entity
class Todo(
    @Owner val owner: User,
    title: String,
    done: Boolean = false,
) : Record() {
    var title: String by column(title)
    var done: Boolean by column(done)

    /**
     * The team the todo is shared with. Setting it shares the todo with the team, and clearing it
     * unshares it.
     */
    var team: Team? by reference()

    /** Whether the todo is archived. Only an admin can change it. */
    var archived: Boolean by column(false)

    companion object : Policy<Todo, User> {
        /** Pattern 2: the owner can read it, and so can members of the team it's shared with. */
        override fun canRead(record: Todo, principal: User): Boolean =
            record.owner == principal || (record.team != null && record.team == principal.team)

        /** Sharing only grants read access. Teammates can see the todo but can't change it. */
        override fun canWrite(record: Todo, principal: User): Boolean = record.owner == principal

        /** Pattern 3: only an admin can change `archived`, whoever owns the todo. */
        override fun canWrite(record: Todo, column: Column<Todo>, principal: User): Boolean = when (column) {
            Todos.archived -> principal.admin
            else -> canWrite(record, principal)
        }
    }
}
