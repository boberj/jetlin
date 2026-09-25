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
 * A record whose owner can change hands, by offer and acceptance.
 *
 * Its owner is a `var`, so KSP generates `transferTo`. The owner offers it with
 * `update { offeredTo = bob }`, and only Bob can then take it.
 */
@Entity
internal class Doc(@Owner owner: User, text: String) : Record() {
    var owner: User by reference(owner)
    var offeredTo: User? by reference()
    var text: String by column(text)

    companion object : Policy<Doc, User> {
        override fun canWrite(record: Doc, principal: User): Boolean = record.owner == principal

        /** Whoever it's offered to can see it, so they can decide whether to take it. */
        override fun canRead(record: Doc, principal: User): Boolean =
            canWrite(record, principal) || record.offeredTo == principal

        /** Only the person it's offered to can take it, and only for themselves. */
        override fun canTransfer(record: Doc, to: User, principal: User): Boolean =
            to == principal && record.offeredTo == principal
    }
}

/** Returns the generated list of tables, in load order. */
internal fun schema(): List<Table<out Record>> = JetlinSchema.tables
