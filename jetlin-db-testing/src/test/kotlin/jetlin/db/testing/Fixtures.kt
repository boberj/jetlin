package jetlin.db.testing

import jetlin.db.Column
import jetlin.db.Entity
import jetlin.db.Owner
import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.Schema
import jetlin.db.Table
import jetlin.db.policy

/**
 * The principal in these tests. Its own policy is replaced in each test, through [schemaOf].
 */
@Entity
internal class Member(name: String, admin: Boolean = false) : Record(), Principal {
    var name: String by column(name)
    var admin: Boolean by column(admin)
    var team: String? by column<String?>(null)

    companion object : Policy<Member, Member> by policy({ anyone implies canRead() })
}

/** A record with an owner, an offer, a group, and a flag: something for every kind of rule. */
@Entity
internal class Note(@Owner owner: Member, text: String) : Record() {
    var owner: Member by reference(owner)
    var offeredTo: Member? by reference()
    var text: String by column(text)
    var team: String? by column<String?>(null)
    var archived: Boolean by column(false)

    companion object : Policy<Note, Member> by policy({ userIn(Note::owner).canEdit() })
}

/** Returns a schema of the test entities, with [members] and [notes] as their policies. */
internal fun schemaOf(members: Policy<Member, Member> = everyoneVisible, notes: Policy<Note, Member>): Schema =
    object : Schema {
        override val tables: List<Table<out Record>> = listOf(Members.table, Notes.table)

        override fun policyFor(table: Table<out Record>): Policy<out Record, out Principal> =
            if (table == Members.table) members else notes
    }

/** Members everyone can see, and nobody can change. */
internal val everyoneVisible: Policy<Member, Member> = policy { anyone implies canRead() }

/**
 * A common hand-written member policy, with a loophole: members can change their own record, and
 * nothing stops them from making themselves admins.
 */
internal object SelfServiceMembers : Policy<Member, Member> {
    override fun canRead(record: Member, principal: Member): Boolean = true
    override fun canWrite(record: Member, principal: Member): Boolean = record == principal || principal.admin
}

/** [SelfServiceMembers], fixed: only admins can change what someone is allowed to do. */
internal object GuardedMembers : Policy<Member, Member> {
    override fun canRead(record: Member, principal: Member): Boolean = true
    override fun canWrite(record: Member, principal: Member): Boolean = record == principal || principal.admin
    override fun canWrite(record: Member, column: Column<Member>, principal: Member): Boolean = when (column) {
        Members.admin, Members.team -> principal.admin
        else -> canWrite(record, principal)
    }
}
