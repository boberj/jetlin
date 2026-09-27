package jetlin.samples.teams

import jetlin.db.Column
import jetlin.db.Entity
import jetlin.db.Owner
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.describedAs
import jetlin.db.equalTo
import jetlin.db.map
import jetlin.db.owned
import jetlin.db.policy

/**
 * The sample's entities and their access policies.
 *
 * The entities cover the three access patterns the framework is designed for:
 *
 * 1. Owner only: [Note], with the `owned()` policy.
 * 2. Shared through a related record: its owner or an admin can read and change a [Todo], and
 *    anyone on the team it's shared with can read it.
 * 3. One restricted column: only an admin can change [Todo.archived].
 *
 * [Todo]'s policy is built from conditions and grants with `policy { }`. [Team] and [User] implement
 * `Policy` directly, which shows the lower-level interface the rules are built on.
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

        /**
         * Users can rename themselves, but only an admin can change what someone is allowed to do.
         *
         * `admin` and `team` are what the other policies grant access by, and `email` is what signing
         * in looks a user up by. Without this rule, a user could make themselves an admin, join any team
         * whose record they can reach (through `someone.team`, for example), or take another user's
         * email address.
         */
        override fun canWrite(record: User, column: Column<User>, principal: User): Boolean = when (column) {
            Users.admin, Users.team, Users.email -> principal.admin
            else -> canWrite(record, principal)
        }
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

    /**
     * The todo's grants, read aloud: "If the principal is its owner, they can edit it. If the
     * principal is an admin, they can edit it. If the principal's team is its team, they can read
     * it. If the principal is an admin, they can change archived."
     */
    companion object : Policy<Todo, User> by policy({
        val admin = (principal() map User::admin) describedAs "the principal is an admin"

        principal() equalTo record(Todo::owner) implies canEdit()
        admin implies canEdit()
        // Pattern 2: sharing a todo with a team lets its members see it, not change it.
        principal() map User::team equalTo record(Todo::team) implies canRead()
        // Pattern 3: with its own grant, archived is no longer covered by the owner's canEdit().
        admin implies canChange(Todo::archived)
        // Without this, an owner could share a todo with a team they aren't on. Unsharing, by
        // setting team to null, is never checked here: a value rule is only asked about values.
        Todo::team.onlyAllows("the principal's own team") { team, principal ->
            team == principal.team || principal.admin
        }
    })
}
