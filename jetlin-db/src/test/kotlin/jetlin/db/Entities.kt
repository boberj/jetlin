package jetlin.db

/**
 * The entities this module's tests use.
 *
 * Together, they cover the three access patterns from §4.3 of the plan: owner only, shared through
 * a related record, and widely readable with one restricted column. The design is meant to support
 * those patterns, so each one needs a test.
 *
 * `:jetlin-db-ksp` generates their tables, column objects, drafts, and policy-checked accessors.
 */
@Entity
internal class User(name: String, admin: Boolean = false) : Record(), Principal {
    var name: String by column(name)
    var admin: Boolean by column(admin)

    /** The user's team, or `null`. `PolicyRulesTest` uses it for rules about groups. */
    var team: String? by column<String?>(null)

    companion object : Policy<User, User> {
        override fun canRead(record: User, principal: User): Boolean = true

        /** Users can rename themselves, and only an admin can rename someone else. */
        override fun canWrite(record: User, principal: User): Boolean = record == principal || principal.admin

        /** Only an admin can change who's an admin, including themselves. */
        override fun canWrite(record: User, column: Column<User>, principal: User): Boolean = when (column) {
            Users.admin -> principal.admin
            else -> canWrite(record, principal)
        }
    }
}

@Entity
internal class Project(
    @Owner val owner: User,
    name: String,
    shared: Boolean = false,
) : Record() {
    var name: String by column(name)
    var shared: Boolean by column(shared)

    companion object : Policy<Project, User> {
        override fun canRead(record: Project, principal: User): Boolean =
            record.owner == principal || record.shared
        override fun canWrite(record: Project, principal: User): Boolean = record.owner == principal
    }
}

@Entity
internal class Task(
    @Owner val owner: User,
    title: String,
    done: Boolean = false,
) : Record() {
    var title: String by column(title)
    var done: Boolean by column(done)
    var archived: Boolean by column(false)
    var project: Project? by reference()

    companion object : Policy<Task, User> {
        /** The owner can change it, and so can an admin, whoever owns it. */
        override fun canWrite(record: Task, principal: User): Boolean = record.owner == principal || principal.admin

        /**
         * Shared through a related record: anyone who can change it can read it, and so can everyone
         * once its project is shared.
         *
         * `record.project?.shared` reads a cell of another record, so sharing and unsharing a
         * project updates open pages without any invalidation code.
         */
        override fun canRead(record: Task, principal: User): Boolean =
            canWrite(record, principal) || record.project?.shared == true

        /**
         * One column that only an admin can change. A column rule only narrows the record-level
         * [canWrite], which is why that one admits admins.
         */
        override fun canWrite(record: Task, column: Column<Task>, principal: User): Boolean = when (column) {
            Tasks.archived -> principal.admin
            else -> canWrite(record, principal)
        }
    }
}

/**
 * A document whose owner can change hands, with a policy built from grants.
 *
 * Its own policy lets the owner edit it and offer it to someone, who can then accept it.
 * `PolicyRulesTest` also checks it against other policies built with `policy { }`, which is why it
 * has a column for each kind of grant: [team] for groups, and [locked] for a column grant.
 */
@Entity
internal class Doc(@Owner owner: User, text: String) : Record() {
    var owner: User by reference(owner)
    var offeredTo: User? by reference()
    var text: String by column(text)
    var team: String? by column<String?>(null)
    var locked: Boolean by column(false)

    // Written as grants. PolicyRulesTest checks that grants and conditions build the same policy.
    companion object : Policy<Doc, User> by policy({
        userIn(Doc::owner).canEdit()
        userIn(Doc::owner).canOffer(Doc::owner, via = Doc::offeredTo)
    })
}

/** Returns the generated list of tables, in load order. */
internal fun schema(): List<Table<out Record>> = JetlinSchema.tables
