package jetlin.db

import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests policies built with `policy { }`: conditions, grants, and the rules that limit them.
 *
 * Most tests build a small policy for [Doc] and drive it through [Gate] directly, so each test shows
 * one grant on its own. The offer tests use [Doc]'s own policy through the generated `update { }`,
 * the way an application would. Refusal messages are checked in full, because they're how a
 * developer finds out which grant was missing.
 */
class PolicyRulesTest {

    // ---- canCreate, canUpdate, canDelete, canEdit -------------------------------------------------

    private val ownerOnly = policy<Doc, User> { principal() equalTo (record() map Doc::owner) implies canEdit() }

    @Test
    fun `only a principal granted canUpdate can change a record`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val doc = db.doc(alice)

        ownerOnly.update(doc, alice) { text = "Plan B" }

        assertEquals(
            "$bob may not change $doc: it can only be updated if the principal is the record's owner",
            refusal { ownerOnly.update(doc, bob) { text = "Bob's plan" } },
        )
        assertEquals("Plan B", doc.text)
    }

    @Test
    fun `canUpdate doesn't let anyone change a record into one they couldn't update`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val doc = db.doc(alice)

        assertEquals(
            "$alice may not change $doc: afterwards, it could only be updated if the principal is the record's owner",
            refusal { ownerOnly.update(doc, alice) { owner = bob } },
        )
        assertEquals(alice, doc.owner)
    }

    @Test
    fun `canCreate is checked against the new record`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val forBob = Doc(bob, "Spam")

        assertEquals(
            "$alice may not create $forBob: it can only be created if the principal is the record's owner",
            refusal { Gate.add(db, ownerOnly, alice, forBob) },
        )
        Gate.add(db, ownerOnly, alice, Doc(alice, "Mine"))
    }

    @Test
    fun `updaters and deleters can see what they can change, and nobody else can`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val doc = db.doc(alice)

        assertTrue(ownerOnly.canRead(doc, alice))
        assertFalse(ownerOnly.canRead(doc, bob))
    }

    @Test
    fun `grants add up, and the refusal quotes every condition`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            (principal() map User::admin) describedAs "the principal is an admin" implies canUpdate()
        }

        policy.update(doc, root) { text = "Reviewed" }

        assertEquals(
            "$bob may not change $doc: it can only be updated if the principal is the record's owner, " +
                "or if the principal is an admin",
            refusal { policy.update(doc, bob) { text = "Bob's plan" } },
        )
        assertEquals("Reviewed", doc.text)
    }

    @Test
    fun `each action needs its own grant`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canUpdate()
            principal() map User::admin implies canDelete()
        }

        policy.update(doc, alice) { text = "Plan B" }

        val draft = Doc(alice, "New")
        assertEquals("$alice may not create $draft: nothing lets anyone create it", refusal { Gate.add(db, policy, alice, draft) })
        assertEquals(
            "$alice may not delete $doc: it can only be deleted if the principal's admin is true",
            refusal { Gate.delete(doc, policy, alice) },
        )
        Gate.delete(doc, policy, root)
    }

    @Test
    fun `adding a record doesn't let the principal see it`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val root = db.user("Root", admin = true)
        // Anyone can submit feedback, and only admins can read it.
        val policy = policy<Doc, User> {
            anyone implies canCreate()
            principal() map User::admin implies canRead()
        }

        val feedback = Gate.add(db, policy, alice, Doc(alice, "Great app"))

        assertFalse(policy.canRead(feedback, alice))
        assertTrue(policy.canRead(feedback, root))
    }

    @Test
    fun `a policy with no grants allows nothing`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val doc = db.doc(alice)
        val policy = policy<Doc, User> { }

        assertFalse(policy.canRead(doc, alice))
        assertEquals(
            "$alice may not change $doc: nothing lets anyone update it",
            refusal { policy.update(doc, alice) { text = "Plan B" } },
        )
    }

    // ---- Conditions -------------------------------------------------------------------------------

    @Test
    fun `conditions describe themselves`(): Unit {
        lateinit var described: List<String>
        val policy = policy<Doc, User> {
            val owner = principal() equalTo (record() map Doc::owner)
            val sameTeam = principal() map User::team equalTo (record() map Doc::team)
            val admin = principal() map User::admin
            described = listOf(owner, sameTeam, owner and sameTeam, admin, not(admin), not(owner), anyone, not(anyone))
                .map { it.statement }
            owner implies canEdit()
        }
        policy.canRead(Doc(User("Alice"), "Plan"), User("Alice"))

        assertEquals(
            listOf(
                "the principal is the record's owner",
                "the principal's team is the record's team",
                "the principal is the record's owner and the principal's team is the record's team",
                "the principal's admin is true",
                "the principal's admin is false",
                "it isn't the case that the principal is the record's owner",
                "anyone may do this",
                "it isn't the case that anyone may do this",
            ),
            described,
        )
    }

    @Test
    fun `a condition can compare the principal with the record itself`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val users = policy<User, User> {
            anyone implies canRead()
            principal() equalTo record() implies canUpdate()
        }

        Gate.update(alice, users, alice, UserDraft(alice)) { name = "Alicia" }

        assertEquals(
            "$bob may not change $alice: it can only be updated if the principal is the record",
            refusal { Gate.update(alice, users, bob, UserDraft(alice)) { name = "Bob's now" } },
        )
        assertEquals("Alicia", alice.name)
    }

    @Test
    fun `and requires both conditions`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val policy = policy<Doc, User> {
            (principal() equalTo (record() map Doc::owner)) and (principal() map User::team equalTo (record() map Doc::team)) implies canEdit()
        }

        assertTrue(policy.canRead(db.doc(alice, team = "acme"), alice))
        assertFalse(policy.canRead(db.doc(alice, team = "globex"), alice))
    }

    @Test
    fun `a condition on a missing value never holds, not even for two missing values`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val dave = db.user("Dave")
        val unshared = db.doc(alice)
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() map User::team equalTo (record() map Doc::team) implies canRead()
        }

        // Neither the document nor Dave has a team. That's not the same team.
        assertFalse(policy.canRead(unshared, dave))
    }

    @Test
    fun `contains matches principals in several groups`(): Unit {
        val policy = policy<Card, Member> {
            principal() map Member::teams contains (record() map Card::team) implies canRead()
        }
        val member = Member(setOf("acme", "globex"))

        assertTrue(policy.canRead(Card("globex"), member))
        assertFalse(policy.canRead(Card("initech"), member))
        assertFalse(policy.canRead(Card(null), member))
    }

    @Test
    fun `comparing values of different types fails the first time, instead of never matching`(): Unit {
        // This compiles, because Kotlin treats a String and an Int as Any.
        val policy = policy<Card, Member> {
            principal() map Member::team equalTo (record() map Card::size) implies canRead()
        }

        val failure = assertFailsWith<IllegalStateException> { policy.canRead(Card("acme"), Member(setOf("acme"))) }

        assertEquals(
            "The condition \"the principal's team is the record's size\" compares a value of type String with one of " +
                "type Int, so it could never hold. Compare values of the same kind.",
            failure.message,
        )
    }

    @Test
    fun `anyone always holds`(): Unit = withDb { db ->
        val root = db.user("Root", admin = true)
        val bob = db.user("Bob")
        val announcement = db.doc(root)
        val policy = policy<Doc, User> {
            principal() map User::admin implies canEdit()
            anyone implies canRead()
        }

        assertTrue(policy.canRead(announcement, bob))
        assertFalse(policy.canWrite(announcement, bob))
    }

    // ---- canRead ----------------------------------------------------------------------------------

    @Test
    fun `canRead shows a record without letting anyone else change it`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val carol = db.user("Carol", team = "globex")
        val doc = db.doc(alice, team = "acme")
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() map User::team equalTo (record() map Doc::team) implies canRead()
        }

        assertTrue(policy.canRead(doc, bob))
        assertFalse(policy.canRead(doc, carol))
        assertEquals(
            "$bob may not change $doc: it can only be updated if the principal is the record's owner",
            refusal { policy.update(doc, bob) { text = "Bob's plan" } },
        )
    }

    // ---- canChange --------------------------------------------------------------------------------

    @Test
    fun `a column grant takes the column out of canUpdate`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            val admin = (principal() map User::admin) describedAs "the principal is an admin"
            principal() equalTo (record() map Doc::owner) implies canEdit()
            admin implies canEdit()
            admin implies canChange(Doc::locked)
        }

        policy.update(doc, alice) { text = "Plan B" }
        assertEquals(
            "$alice may not change $doc: locked can only be changed if the principal is an admin",
            refusal { policy.update(doc, alice) { text = "Plan C"; locked = true } },
        )
        assertEquals("Plan B", doc.text, "the whole change was refused, not just the locked column")
        policy.update(doc, root) { locked = true }

        assertTrue(doc.locked)
    }

    @Test
    fun `a column grant works on any record the principal can see, and only for that column`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() map User::admin implies canRead()
            principal() map User::admin implies canChange(Doc::locked)
        }

        policy.update(doc, root) { locked = true }

        assertTrue(doc.locked)
        assertEquals(
            "$root may not change $doc: it can only be updated if the principal is the record's owner",
            refusal { policy.update(doc, root) { text = "Root's plan" } },
        )
    }

    @Test
    fun `a column grant needs the principal to see the record`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() map User::admin implies canChange(Doc::locked)
        }

        assertFailsWith<AccessDenied> { policy.update(doc, root) { locked = true } }
        assertFalse(doc.locked)
    }

    @Test
    fun `setting a column to the value it already has isn't a change`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() map User::admin implies canChange(Doc::locked)
        }

        policy.update(doc, alice) { locked = false; text = "Plan B" }

        assertEquals("Plan B", doc.text)
    }

    // ---- onlyAllows -------------------------------------------------------------------------------

    private val ownTeamOnly = policy<Doc, User> {
        principal() equalTo (record() map Doc::owner) implies canEdit()
        Doc::team.onlyAllows("the principal's own team") { team, principal -> team == principal.team }
    }

    @Test
    fun `onlyAllows limits what a column can be set to`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val doc = db.doc(alice)

        ownTeamOnly.update(doc, alice) { team = "acme" }

        assertEquals(
            "$alice may not change $doc: team can only be set to the principal's own team",
            refusal { ownTeamOnly.update(doc, alice) { team = "globex" } },
        )
        assertEquals("acme", doc.team)
    }

    @Test
    fun `onlyAllows also applies to new records`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val forGlobex = Doc(alice, "Plan").also { it.team = "globex" }

        assertEquals(
            "$alice may not create $forGlobex: team can only be set to the principal's own team",
            refusal { Gate.add(db, ownTeamOnly, alice, forGlobex) },
        )
    }

    @Test
    fun `onlyAllows is never asked about null, so the grants decide who can empty a column`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val asked = mutableListOf<String>()
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            Doc::team.onlyAllows("the principal's own team") { team, principal ->
                asked += team
                team == principal.team
            }
        }
        val doc = db.doc(alice, team = "acme")

        policy.update(doc, alice) { team = null }
        Gate.add(db, policy, alice, Doc(alice, "Unshared"))

        assertNull(doc.team)
        assertEquals(emptyList(), asked, "the rule never saw null")
        // Emptying is still a change, which the grants decide.
        doc.also { store -> db.transact { store.team = "acme" } }
        assertFailsWith<AccessDenied> { policy.update(doc, bob) { team = null } }
        assertEquals("acme", doc.team)
    }

    // ---- canReassign ------------------------------------------------------------------------------

    private val toTeammates = policy<Doc, User> {
        principal() equalTo (record() map Doc::owner) implies canEdit()
        principal() equalTo (record() map Doc::owner) implies canReassign(Doc::owner) { candidate ->
            candidate map User::team equalTo (principal() map User::team)
        }
    }

    @Test
    fun `an owner can hand a record to a teammate`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val doc = db.doc(alice)

        toTeammates.update(doc, alice) { owner = bob }

        assertEquals(bob, doc.owner)
        toTeammates.update(doc, bob) { text = "Bob's now" }
        assertFalse(toTeammates.canWrite(doc, alice), "alice handed it on")
    }

    @Test
    fun `a record can't be handed to a candidate the condition refuses`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val carol = db.user("Carol", team = "globex")
        val doc = db.doc(alice)

        assertEquals(
            "$alice may not change $doc: owner can only be reassigned if the candidate's team is the principal's team",
            refusal { toTeammates.update(doc, alice) { owner = carol } },
        )
        assertEquals(alice, doc.owner)
    }

    @Test
    fun `principals on no team have no teammates`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val dave = db.user("Dave")
        val doc = db.doc(alice)

        assertFailsWith<AccessDenied> { toTeammates.update(doc, alice) { owner = dave } }
    }

    @Test
    fun `a reassignment relaxes nothing but the reassigned column`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val policy = policy<Doc, User> {
            (principal() equalTo (record() map Doc::owner)) and (principal() map User::team equalTo (record() map Doc::team)) implies canEdit()
            principal() equalTo (record() map Doc::owner) implies canReassign(Doc::owner) { candidate ->
                candidate map User::team equalTo (principal() map User::team)
            }
        }
        val doc = db.doc(alice, team = "acme")

        // Moving the document to a team alice isn't on is refused, reassigned or not.
        assertEquals(
            "$alice may not change $doc: afterwards, it could only be updated if the principal is the record's owner " +
                "and the principal's team is the record's team",
            refusal { policy.update(doc, alice) { owner = bob; team = "globex" } },
        )
        policy.update(doc, alice) { owner = bob }

        assertEquals(bob, doc.owner)
        assertEquals("acme", doc.team)
    }

    @Test
    fun `a principal can reassign records they can't otherwise change`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val root = db.user("Root", team = "acme", admin = true)
        val doc = db.doc(alice)
        // Admins triage: they can move anyone's document to a teammate, but can't edit it.
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() map User::admin implies canRead()
            principal() map User::admin implies canReassign(Doc::owner) { candidate ->
                candidate map User::team equalTo (principal() map User::team)
            }
        }

        policy.update(doc, root) { owner = bob }

        assertEquals(bob, doc.owner)
        assertFailsWith<AccessDenied> { policy.update(doc, root) { text = "Root's plan" } }
    }

    @Test
    fun `a candidate can be compared with the record, not just the principal`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "globex")
        val carol = db.user("Carol", team = "acme")
        // A document can only move within its own team, whatever team the principal is on.
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            principal() equalTo (record() map Doc::owner) implies canReassign(Doc::owner) { candidate ->
                candidate map User::team equalTo (record() map Doc::team)
            }
        }
        val doc = db.doc(alice, team = "globex")

        assertFailsWith<AccessDenied> { policy.update(doc, alice) { owner = carol } }
        policy.update(doc, alice) { owner = bob }

        assertEquals(bob, doc.owner)
    }

    @Test
    fun `a new record can start out handed to a teammate`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val carol = db.user("Carol", team = "globex")

        Gate.add(db, toTeammates, alice, Doc(bob, "For Bob"))

        val forCarol = Doc(carol, "For Carol")
        assertEquals(
            "$alice may not create $forCarol: it can only be created if the principal is the record's owner",
            refusal { Gate.add(db, toTeammates, alice, forCarol) },
        )
    }

    @Test
    fun `a page can list who a record can be handed to without handing it on`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val carol = db.user("Carol", team = "globex")
        val doc = db.doc(alice)

        val candidates = listOf(bob, carol).filter { toTeammates.allows(doc, alice) { owner = it } }

        assertEquals(listOf(bob), candidates)
        assertEquals(alice, doc.owner, "asking changed nothing")
    }

    @Test
    fun `once a column can be reassigned, canUpdate can't hand the record on`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        // Admins can update every document, and their grant holds before and after any change. Without
        // the rule, that would let them change its owner to anyone.
        val policy = policy<Doc, User> {
            userIn(Doc::owner).canEdit()
            usersWhere(User::admin).canUpdate()
            userIn(Doc::owner).canReassign(Doc::owner) { candidate -> candidate map User::team equalTo (principal() map User::team) }
        }

        assertEquals(
            "$root may not change $doc: owner can only be reassigned if the principal is the record's owner",
            refusal { policy.update(doc, root) { owner = bob } },
        )
        policy.update(doc, root) { text = "Reviewed" }
        assertFalse(policy.canWrite(doc, Docs.owner, root), "a page shouldn't offer it either")
        assertTrue(policy.canWrite(doc, Docs.owner, alice))

        assertEquals(alice, doc.owner)
        assertEquals("Reviewed", doc.text)
    }

    @Test
    fun `canUpdate can still clear a column that changes hands`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val carol = db.user("Carol", team = "globex")
        val policy = policy<Doc, User> {
            userIn(Doc::owner).canEdit()
            userIn(Doc::owner).canReassign(Doc::offeredTo) { candidate -> candidate map User::team equalTo (principal() map User::team) }
        }
        val doc = db.store(Doc(alice, "Plan").also { it.offeredTo = bob })

        // Clearing it hands the document to nobody, so it's an ordinary update.
        policy.update(doc, alice) { offeredTo = null }
        assertFailsWith<AccessDenied> { policy.update(doc, alice) { offeredTo = carol } }
        policy.update(doc, alice) { offeredTo = bob }

        assertEquals(bob, doc.offeredTo)
    }

    // ---- canOffer, through Doc's own policy -------------------------------------------------------

    @Test
    fun `an offered record only changes hands when its recipient accepts, even for admins`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val root = db.user("Root", admin = true)
        val doc = db.doc(alice)
        val policy = policy<Doc, User> {
            userIn(Doc::owner).canEdit()
            usersWhere(User::admin).canUpdate()
            userIn(Doc::owner).canOffer(Doc::owner, via = Doc::offeredTo)
        }

        assertEquals(
            "$root may not change $doc: owner can only change hands when someone accepts an offer",
            refusal { policy.update(doc, root) { owner = root } },
        )
        assertEquals(alice, doc.owner)
    }


    @Test
    fun `an offered record changes hands when its recipient accepts it`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val doc = db.doc(alice)

        with(alice) { doc.update { offeredTo = bob } }
        with(bob) {
            assertNotNull(Docs.find(db, Id(doc.id)), "the recipient can see what they're offered")
            assertTrue(doc.canUpdate { owner = bob; offeredTo = null })
            doc.update { owner = bob; offeredTo = null }
        }

        assertEquals(bob, doc.owner)
        assertNull(doc.offeredTo)
        assertNull(with(alice) { Docs.find(db, Id(doc.id)) }, "alice gave it away")
    }

    @Test
    fun `accepting is the only change a recipient can make`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val carol = db.user("Carol")
        val doc = db.doc(alice)
        with(alice) { doc.update { offeredTo = bob } }

        with(bob) {
            assertEquals(
                "$bob may not change $doc: it can only be updated if the principal is the record's owner",
                refusal { doc.update { text = "Bob's plan" } },
            )
            assertEquals(
                "$bob may not change $doc: offeredTo can only be changed if the principal is the record's owner",
                refusal { doc.update { offeredTo = carol } },
            )
            // Taking it while leaving the offer open isn't accepting it.
            assertFailsWith<AccessDenied> { doc.update { owner = bob } }
            assertFailsWith<AccessDenied> { doc.update { owner = bob; offeredTo = null; text = "Bob's plan" } }
        }

        assertEquals(alice, doc.owner)
    }

    @Test
    fun `nobody can push a record onto someone else, or take one without an offer`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val mallory = db.user("Mallory")
        val doc = db.doc(alice)
        val spam = db.doc(mallory)

        with(mallory) {
            assertFailsWith<AccessDenied> { spam.update { owner = alice } }
            assertFailsWith<AccessDenied> { doc.update { owner = mallory; offeredTo = null } }
        }
        with(alice) { doc.update { offeredTo = bob } }
        with(mallory) { assertFailsWith<AccessDenied> { doc.update { owner = mallory; offeredTo = null } } }

        assertEquals(mallory, spam.owner)
        assertEquals(alice, doc.owner)
    }

    @Test
    fun `withdrawing an offer takes back the right to accept it`(): Unit = withDb { db ->
        val alice = db.user("Alice")
        val bob = db.user("Bob")
        val doc = db.doc(alice)

        with(alice) { doc.update { offeredTo = bob } }
        with(alice) { doc.update { offeredTo = null } }

        with(bob) {
            assertFalse(doc.canUpdate { owner = bob; offeredTo = null })
            assertFailsWith<AccessDenied> { doc.update { owner = bob; offeredTo = null } }
        }
    }

    // ---- alwaysRequires ---------------------------------------------------------------------------

    @Test
    fun `alwaysRequires applies to every check, before and after a change`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            alwaysRequires(principal() map User::team equalTo (record() map Doc::team))
        }
        val inAcme = db.doc(alice, team = "acme")
        val inGlobex = db.doc(alice, team = "globex")

        assertTrue(policy.canRead(inAcme, alice))
        assertEquals(
            "$alice may not change $inAcme: it requires that the principal's team is the record's team",
            refusal { policy.update(inAcme, alice) { team = "globex" } },
        )
        // Alice owns this one, but it's in a team she isn't on.
        assertFalse(policy.canRead(inGlobex, alice))
        assertFailsWith<AccessDenied> { policy.update(inGlobex, alice) { text = "Plan B" } }
    }

    // ---- Grants, written subject first ------------------------------------------------------------

    @Test
    fun `grants and conditions build the same policy`(): Unit = withDb { db ->
        val conditions = policy<Doc, User> {
            val admin = (principal() map User::admin) describedAs "the principal is an admin"
            principal() equalTo (record() map Doc::owner) describedAs "the principal is the record's owner" implies canEdit()
            admin implies canEdit()
            principal() map User::team equalTo (record() map Doc::team) implies canRead()
            admin implies canChange(Doc::locked)
        }
        val grants = policy<Doc, User> {
            val admin = usersWhere(User::admin, "the principal is an admin")
            userIn(Doc::owner).canEdit()
            admin.canEdit()
            membersOf(record() map Doc::team, membership = User::team).canRead()
            admin.canChange(Doc::locked)
        }
        val users = listOf(
            db.user("Alice", team = "acme"),
            db.user("Bob", team = "acme"),
            db.user("Carol"),
            db.user("Root", team = "globex", admin = true),
        )
        val docs = users.flatMap { owner -> listOf("acme", "globex", null).map { team -> db.doc(owner, team) } }

        for (doc in docs) {
            for (principal in users) {
                fun both(question: (Policy<Doc, User>) -> Any?) =
                    assertEquals(question(conditions), question(grants), "$principal on $doc")

                both { it.canRead(doc, principal) }
                both { it.canWrite(doc, principal) }
                both { it.canWrite(doc, Docs.locked, principal) }
                both { it.canDelete(doc, principal) }
                both { it.canCreate(Doc(doc.owner, "New").also { new -> new.team = doc.team }, principal) }
                both { it.allows(doc, principal) { locked = true } }
                both { it.allows(doc, principal) { text = "Edited" } }
                both { runCatching { it.update(doc, principal) { locked = !locked } }.exceptionOrNull()?.message }
            }
        }
    }

    @Test
    fun `the shorthand conditions describe themselves`(): Unit {
        lateinit var described: List<String>
        val docs = policy<Doc, User> {
            described = listOf(
                userIn(Doc::owner),
                usersWhere(User::admin),
                usersWhere(User::admin, "the principal is an admin"),
                membersOf(record() map Doc::team, membership = User::team),
            ).map { it.statement }
            anyone.canRead()
        }
        docs.canRead(Doc(User("Alice"), "Plan"), User("Alice"))

        assertEquals(
            listOf(
                "the principal is the record's owner",
                "the principal's admin is true",
                "the principal is an admin",
                "the principal's team is the record's team",
            ),
            described,
        )
    }

    @Test
    fun `membersOf with memberships matches principals in several groups`(): Unit {
        val cards = policy<Card, Member> { membersOf(record() map Card::team, memberships = Member::teams).canRead() }
        val member = Member(setOf("acme", "globex"))

        assertTrue(cards.canRead(Card("globex"), member))
        assertFalse(cards.canRead(Card("initech"), member))
        assertFalse(cards.canRead(Card(null), member))
    }

    @Test
    fun `every permission can be granted subject first`(): Unit = withDb { db ->
        val alice = db.user("Alice", team = "acme")
        val bob = db.user("Bob", team = "acme")
        val root = db.user("Root", team = "acme", admin = true)
        val policy = policy<Doc, User> {
            val owner = userIn(Doc::owner)
            owner.canCreate()
            owner.canUpdate()
            usersWhere(User::admin).canRead()
            usersWhere(User::admin).canDelete()
            usersWhere(User::admin).canChange(Doc::locked)
            owner.canReassign(Doc::owner) { candidate -> candidate map User::team equalTo (principal() map User::team) }
        }
        val doc = Gate.add(db, policy, alice, Doc(alice, "Plan"))

        policy.update(doc, root) { locked = true }
        policy.update(doc, alice) { owner = bob }
        Gate.delete(doc, policy, root)

        assertTrue(doc.locked)
        assertEquals(bob, doc.owner)
    }

    // ---- Building a policy ------------------------------------------------------------------------

    @Test
    fun `a permission without a condition fails the first time the policy is used`(): Unit {
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canUpdate()
            canDelete()
        }

        val failure = assertFailsWith<IllegalStateException> { policy.canRead(Doc(User("Alice"), "Plan"), User("Alice")) }

        assertEquals(
            "canDelete() isn't granted to anyone, so it has no effect. Write it after a condition: " +
                "`condition implies canDelete()`.",
            failure.message,
        )
    }

    @Test
    fun `onlyAllows declared twice for one column fails the first time the policy is used`(): Unit {
        val policy = policy<Doc, User> {
            principal() equalTo (record() map Doc::owner) implies canEdit()
            Doc::team.onlyAllows("acme") { team, _ -> team == "acme" }
            Doc::team.onlyAllows("globex") { team, _ -> team == "globex" }
        }

        val failure = assertFailsWith<IllegalStateException> { policy.canRead(Doc(User("Alice"), "Plan"), User("Alice")) }

        assertEquals("onlyAllows is declared twice for team.", failure.message)
    }
}

/** A principal on several teams at once. It's never stored, so it needs no entity or policy. */
private class Member(val teams: Set<String>) : Record(), Principal {
    val team: String? get() = teams.firstOrNull()
}

/** A record that belongs to one team, or none. */
private class Card(val team: String?, val size: Int = 1) : Record()

private fun Policy<Doc, User>.update(doc: Doc, principal: User, block: DocDraft.() -> Unit) =
    Gate.update(doc, this, principal, DocDraft(doc), block)

private fun Policy<Doc, User>.allows(doc: Doc, principal: User, block: DocDraft.() -> Unit): Boolean =
    Gate.canUpdate(doc, this, principal, DocDraft(doc), block)

/** Runs [block], which must be refused, and returns the refusal's message. */
private fun refusal(block: () -> Unit): String = assertFailsWith<AccessDenied>(block = block).message.orEmpty()

private fun Db.user(name: String, team: String? = null, admin: Boolean = false): User =
    store(User(name, admin).also { it.team = team })

private fun Db.doc(owner: User, team: String? = null): Doc = store(Doc(owner, "Plan").also { it.team = team })

/** A database in a temporary file. Fixtures are stored without any policy check. */
@OptIn(ExperimentalPathApi::class)
private fun withDb(block: (Db) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        Db.open(directory.resolve("test.db"), schema()).use(block)
    } finally {
        directory.deleteRecursively()
    }
}

private fun <T : Record> Db.store(record: T): T = transact { insert(record) }
