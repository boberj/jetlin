package jetlin.db.testing

import jetlin.db.Policy
import jetlin.db.and
import jetlin.db.equalTo
import jetlin.db.map
import jetlin.db.not
import jetlin.db.policy
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the policy checks against policies with one known flaw each.
 *
 * Each test states the flaw, checks that the right check finds it with a story a developer can
 * follow, and, where there's a fix, checks that the fixed policy is clean.
 */
class PolicyCheckTest {

    private val ownerOnly = policy<Note, Member> { userIn(Note::owner).canEdit() }

    private val ownersAndAdmins = policy<Note, Member> {
        userIn(Note::owner).canEdit()
        usersWhere(Member::admin).canEdit()
    }

    // ---- Passing and failing ----------------------------------------------------------------------

    @Test
    fun `a sound policy passes`(): Unit {
        val report = checkPolicies(schemaOf(notes = ownerOnly))

        assertEquals(emptyList(), report.problems)
    }

    @Test
    fun `a finding fails the check, with a report that says what to do`(): Unit {
        val failure = assertFailsWith<AssertionError> {
            checkPolicies(schemaOf(members = SelfServiceMembers, notes = ownersAndAdmins))
        }

        val message = failure.message.orEmpty()
        assertContains(message, "Self-escalation: Member: changing admin can give a principal access to other records")
        assertContains(message, "If this is intended, allow it:")
        assertContains(message, "allow(Check.SelfEscalation, Member::admin, because = \"…\")")
    }

    @Test
    fun `allowing a finding with a reason passes the check, and keeps the reason`(): Unit {
        val report = checkPolicies(schemaOf(notes = ownersAndAdmins)) {
            allow(Check.PushOntoOthers, because = "admins create notes for people")
        }

        assertEquals(emptyList(), report.problems)
        assertTrue(report.allowed.all { it.allowedBecause == "admins create notes for people" })
        assertTrue(report.allowed.isNotEmpty())
    }

    @Test
    fun `the same seed finds the same problems, described the same way`(): Unit {
        val schema = schemaOf(members = SelfServiceMembers, notes = ownersAndAdmins)

        assertEquals(findPolicyProblems(schema).describe(), findPolicyProblems(schema).describe())
    }

    // ---- The checks -------------------------------------------------------------------------------

    @Test
    fun `a member who can make themselves an admin is found, in the smallest world that shows it`(): Unit {
        val report = findPolicyProblems(schemaOf(members = SelfServiceMembers, notes = ownersAndAdmins))

        val escalation = report.problems.single { it.check == Check.SelfEscalation }
        assertContains(escalation.details, "admin false → true. The policy allows it.")
        assertFalse("Member3" in escalation.details, "shrunk to two members:\n${escalation.details}")
        assertFalse("Note1" in escalation.details, "no note is needed to show it:\n${escalation.details}")
    }

    @Test
    fun `guarding the admin column fixes it`(): Unit {
        val report = findPolicyProblems(schemaOf(members = GuardedMembers, notes = ownersAndAdmins))

        assertFalse(report.problems.any { it.check == Check.SelfEscalation }, report.describe())
    }

    @Test
    fun `access that depends on two missing values matching is found`(): Unit {
        val nullTeams = object : Policy<Note, Member> {
            override fun canWrite(record: Note, principal: Member) = record.owner == principal
            override fun canRead(record: Note, principal: Member) =
                canWrite(record, principal) || record.team == principal.team
        }

        val report = findPolicyProblems(schemaOf(notes = nullTeams))

        val match = report.problems.single { it.check == Check.MissingValuesMatch }
        assertContains(match.details, "but only because")
        assertContains(match.details, ".team are both null")
    }

    @Test
    fun `conditions never match two missing values`(): Unit {
        val teams = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            (principal() map Member::team equalTo (record() map Note::team)).canRead()
        }

        val report = findPolicyProblems(schemaOf(notes = teams))

        assertFalse(report.problems.any { it.check == Check.MissingValuesMatch }, report.describe())
    }

    @Test
    fun `values chosen for a column steer the worlds`(): Unit {
        val nullTeams = object : Policy<Note, Member> {
            override fun canWrite(record: Note, principal: Member) = record.owner == principal
            override fun canRead(record: Note, principal: Member) =
                canWrite(record, principal) || record.team == principal.team
        }

        // Every member is on a team, so no member's team is ever missing.
        val report = findPolicyProblems(schemaOf(notes = nullTeams)) {
            entity(Member::class) { values(Member::team, "acme", "globex") }
        }

        assertFalse(report.problems.any { it.check == Check.MissingValuesMatch }, report.describe())
    }

    @Test
    fun `a grant whose condition can never hold is found`(): Unit {
        val dead = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            (userIn(Note::owner) and not(userIn(Note::owner))).canDelete()
        }

        val report = findPolicyProblems(schemaOf(notes = dead))

        val grant = report.problems.single { it.check == Check.DeadGrant }
        assertEquals("Note: the grant of canDelete() never applied", grant.summary)
    }

    @Test
    fun `a change that hides a record from everyone is found`(): Unit {
        val hidesArchived = object : Policy<Note, Member> {
            override fun canWrite(record: Note, principal: Member) = record.owner == principal
            override fun canRead(record: Note, principal: Member) = record.owner == principal && !record.archived
        }

        val report = findPolicyProblems(schemaOf(notes = hidesArchived))

        val orphan = report.problems.single { it.check == Check.Orphan }
        assertContains(orphan.details, "archived false → true")
        assertContains(orphan.details, "nobody can see Note1")
    }

    @Test
    fun `a change its maker can't undo is found, and handing a record on is one`(): Unit {
        val handOn = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            userIn(Note::owner).canReassign(Note::owner) { candidate -> candidate equalTo candidate }
        }

        val report = findPolicyProblems(schemaOf(notes = handOn))

        assertEquals(setOf(Check.CannotUndo, Check.PushOntoOthers), report.problems.mapNotNull { it.check }.toSet())
        assertContains(report.problems.first { it.check == Check.CannotUndo }.details, "can't set owner back to")
    }

    @Test
    fun `a change that reaches a record the principal couldn't add is found`(): Unit {
        val triage = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            usersWhere(Member::admin).canRead()
            usersWhere(Member::admin).canChange(Note::archived)
        }

        val report = findPolicyProblems(schemaOf(notes = triage)) {
            allow(Check.PushOntoOthers, Note::class, because = "admins can see new notes to triage them")
        }

        val unreachable = report.problems.single { it.check == Check.NotCreatable }
        assertContains(unreachable.details, "couldn't have added directly")
    }

    @Test
    fun `accepting an offer is a takeover, and making one pushes the note onto the recipient`(): Unit {
        val offers = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            userIn(Note::owner).canOffer(Note::owner, via = Note::offeredTo)
        }

        val report = findPolicyProblems(schemaOf(notes = offers))

        assertEquals(setOf(Check.TakeOver, Check.PushOntoOthers), report.problems.mapNotNull { it.check }.toSet())
        val push = report.problems.single { it.check == Check.PushOntoOthers }
        assertContains(push.details, "allow(Check.PushOntoOthers, Note::offeredTo, because = \"…\")")
        checkPolicies(schemaOf(notes = offers)) {
            allow(Check.TakeOver, Note::owner, because = "accepting an offer makes the note yours")
            allow(Check.PushOntoOthers, Note::offeredTo, because = "an offer lets the recipient see the note")
        }
    }

    @Test
    fun `admins who can update everything can't reassign a record once reassignment is declared`(): Unit {
        val withoutReassignment = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            usersWhere(Member::admin).canUpdate()
        }
        val withReassignment = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            usersWhere(Member::admin).canUpdate()
            userIn(Note::owner).canReassign(Note::owner) { candidate -> candidate equalTo candidate }
        }

        // An admin changing the owner produces a note they couldn't have added. The owner reassigning
        // it doesn't, because they could have added it for themselves and handed it on.
        val before = findPolicyProblems(schemaOf(notes = withoutReassignment))
        assertTrue(before.problems.any { it.check == Check.NotCreatable && "owner" in it.summary }, before.describe())
        val after = findPolicyProblems(schemaOf(notes = withReassignment))
        assertFalse(after.problems.any { it.check == Check.NotCreatable && "owner" in it.summary }, after.describe())
    }

    // ---- Properties -------------------------------------------------------------------------------

    @Test
    fun `an action that breaks a never property is found`(): Unit {
        val report = findPolicyProblems(schemaOf(notes = ownerOnly)) {
            never("anyone archives a note") { event -> event.changes(Note::archived) && event.after(Note::archived) }
        }

        val broken = report.problems.single()
        assertEquals("Never: anyone archives a note", broken.summary)
        assertContains(broken.details, "archived false → true. The policy allows it.")
    }

    @Test
    fun `an always property is checked for every record and principal`(): Unit {
        val hidesArchived = object : Policy<Note, Member> {
            override fun canWrite(record: Note, principal: Member) = record.owner == principal
            override fun canRead(record: Note, principal: Member) = record.owner == principal && !record.archived
        }
        val ownersRead: PolicyCheck.() -> Unit = {
            allow(Check.Orphan, Note::archived, because = "archiving hides a note")
            always(Note::class, Member::class, "an owner can see their own notes") { note, member, access ->
                note.owner != member || access.read
            }
        }

        assertEquals(emptyList(), findPolicyProblems(schemaOf(notes = ownerOnly), ownersRead).problems)
        val broken = findPolicyProblems(schemaOf(notes = hidesArchived), ownersRead).problems.single()
        assertEquals("Always: an owner can see their own notes", broken.summary)
        assertContains(broken.details, "archived = true")
    }

    // ---- Comparing policies -----------------------------------------------------------------------

    @Test
    fun `two ways of writing the same policy decide the same`(): Unit {
        val conditions = policy<Note, Member> {
            principal() equalTo (record() map Note::owner) implies canEdit()
            principal() map Member::team equalTo (record() map Note::team) implies canRead()
        }
        val grants = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            membersOf(record() map Note::team, membership = Member::team).canRead()
        }

        comparePolicies(schemaOf(notes = conditions), Notes.table, old = conditions, new = grants).assertSame()
    }

    @Test
    fun `a comparison names a question the two policies answer differently`(): Unit {
        val shared = policy<Note, Member> {
            userIn(Note::owner).canEdit()
            (principal() map Member::team equalTo (record() map Note::team)).canRead()
        }

        val comparison = comparePolicies(schemaOf(notes = ownerOnly), Notes.table, old = ownerOnly, new = shared)

        val difference = comparison.differences.single()
        assertContains(difference, "the old policy says no, and the new one says yes")
        assertFailsWith<AssertionError> { comparison.assertSame() }
    }
}
