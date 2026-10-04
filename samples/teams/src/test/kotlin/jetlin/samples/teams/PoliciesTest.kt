package jetlin.samples.teams

import jetlin.db.Policy
import jetlin.db.Principal
import jetlin.db.Record
import jetlin.db.Schema
import jetlin.db.Table
import jetlin.db.testing.Check
import jetlin.db.testing.PolicyCheck
import jetlin.db.testing.checkPolicies
import jetlin.db.testing.findPolicyProblems
import kotlin.test.Test
import kotlin.test.assertContains

/**
 * Checks the sample's policies for loopholes with jetlin-db-testing.
 *
 * Every allowance below is a finding that's intended, with the reason. Together, they describe what
 * the policies let people do to each other, which is worth knowing whether or not you write
 * policies yourself.
 */
class PoliciesTest {

    private val intended: PolicyCheck.() -> Unit = {
        allow(Check.PushOntoOthers, User::admin, because = "admins can make other users admins")
        allow(Check.PushOntoOthers, User::team, because = "admins put users on teams")
        allow(Check.SelfEscalation, User::team, because = "admins can put themselves on a team, like anyone else")
        allow(
            Check.CannotUndo, User::admin,
            because = "an admin who gives up the role needs another admin to restore it",
        )
        allow(Check.PushOntoOthers, Todo::team, because = "sharing a todo with a team is the point")
        allow(
            Check.NotCreatable, Todo::class,
            because = "an owner can keep editing a todo that an admin shared with another team",
        )
        allow(
            Check.CannotUndo, Todo::team,
            because = "owners can only share with their own team, so only an admin can restore an admin's share",
        )
    }

    @Test
    fun `the policies have no loopholes beyond the intended ones`(): Unit {
        checkPolicies(JetlinSchema, intended)
    }

    @Test
    fun `the check catches users making themselves admins, the loophole the sample once had`(): Unit {
        // The User policy before its column rule: users can change their own record, all of it.
        val selfService = object : Policy<User, User> {
            override fun canRead(record: User, principal: User): Boolean = true
            override fun canWrite(record: User, principal: User): Boolean = record == principal || principal.admin
        }
        val withLoophole = object : Schema {
            override val tables: List<Table<out Record>> = JetlinSchema.tables
            override fun policyFor(table: Table<out Record>): Policy<out Record, out Principal> =
                if (table == Users.table) selfService else JetlinSchema.policyFor(table)
        }

        val report = findPolicyProblems(withLoophole, intended)

        val escalation = report.problems.first { it.check == Check.SelfEscalation }
        assertContains(escalation.details, "admin false → true. The policy allows it.")
    }
}
