package jetlin.samples.teams

import jetlin.db.Column
import jetlin.db.Entity
import jetlin.db.Owner
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.owned

/**
 * What this sample stores, and who may see it.
 *
 * The three access shapes the framework claims to cover are all here, so that the claim can be read
 * rather than taken on trust:
 *
 * 1. **Owner only** — [Note], with the `owned()` shorthand.
 * 2. **Shared by a property of a related record** — [Todo], readable by whoever owns it and by anyone on
 *    the team it was shared with.
 * 3. **Readable by many, one column restricted** — [Todo.archived], which only an admin may write.
 *
 * Every policy is a plain Kotlin expression over live objects, and every one of them reads state that can
 * change while somebody is looking at a page. That is what makes revocation reactive: move a todo off the
 * team and it leaves the teammates' open pages, with no invalidation code anywhere in this file or any
 * other.
 */
@Entity
class Team(name: String) : Record() {
    var name: String by column(name)

    companion object : Policy<Team, User> {
        /** A team is visible to its members. Nothing else about it is interesting. */
        override fun canRead(row: Team, viewer: User): Boolean = viewer.team == row

        override fun canWrite(row: Team, viewer: User): Boolean = viewer.admin
    }
}

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
     * The team this user belongs to, or none.
     *
     * Read by [Todo]'s policy, which is why moving someone between teams changes what their open pages
     * show without anything being told.
     */
    var team: Team? by reference()

    companion object : Policy<User, User> {
        /** Names are visible to everyone signed in: a shared todo has to be able to say whose it is. */
        override fun canRead(row: User, viewer: User): Boolean = true

        override fun canWrite(row: User, viewer: User): Boolean = row == viewer || viewer.admin
    }
}

/** Shape 1: nobody but the owner, which is the whole of the rule. */
@Entity
class Note(
    @Owner val owner: User,
    text: String,
) : Record() {
    var text: String by column(text)

    companion object : Policy<Note, User> by owned(Note::owner)
}

@Entity
class Todo(
    @Owner val owner: User,
    title: String,
    done: Boolean = false,
) : Record() {
    var title: String by column(title)
    var done: Boolean by column(done)

    /** Set to share this todo with everyone on that team; cleared to take it back. */
    var team: Team? by reference()

    var archived: Boolean by column(false)

    companion object : Policy<Todo, User> {
        /** Shape 2: mine, or my team's. */
        override fun canRead(row: Todo, viewer: User): Boolean =
            row.owner == viewer || (row.team != null && row.team == viewer.team)

        /** Shared means shared for reading: a teammate sees it and cannot change it. */
        override fun canWrite(row: Todo, viewer: User): Boolean = row.owner == viewer

        /** Shape 3: archiving is an administrative act, whoever owns the row. */
        override fun canWrite(row: Todo, column: Column<Todo>, viewer: User): Boolean = when (column) {
            Todos.archived -> viewer.admin
            else -> canWrite(row, viewer)
        }
    }
}
