package jetlin.db

import androidx.compose.runtime.key
import jetlin.html.Div
import jetlin.html.Span
import jetlin.html.Text
import jetlin.protocol.Op
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
import kotlinx.coroutines.test.runTest

/**
 * Tests access policies, and how open pages react when access changes.
 *
 * The policies under test are the three patterns from §4.3 of the plan, declared on the entities in
 * `Entities.kt`.
 */
class PolicyTest {

    // ---- Policies are plain functions -------------------------------------------------------------

    @Test
    fun `a policy is a function over live objects and needs no database at all`(): Unit {
        val alice = User("Alice")
        val bob = User("Bob")
        val root = User("Root", admin = true)
        val task = Task(alice, "Read the plan")

        assertTrue(Task.canRead(task, alice))
        assertFalse(Task.canRead(task, bob))

        // The column-level rule: only an admin can archive, whoever owns the record.
        assertFalse(Task.canWrite(task, Tasks.archived, alice))
        assertTrue(Task.canWrite(task, Tasks.archived, root))
        assertTrue(Task.canWrite(task, Tasks.title, alice))
        assertFalse(Task.canWrite(task, Tasks.title, bob))
    }

    @Test
    fun `sharing the project a task belongs to makes the task readable`(): Unit {
        val alice = User("Alice")
        val bob = User("Bob")
        val project = Project(alice, "Inbox")
        val task = Task(alice, "Read the plan").also { it.project = project }

        assertFalse(Task.canRead(task, bob))

        project.shared = true

        assertTrue(Task.canRead(task, bob), "the policy reads a live cell, so this needs no re-checking")
        assertFalse(Task.canWrite(task, bob), "shared means readable, not writable")
    }

    @Test
    fun `the owned shorthand is the same rule, written once`(): Unit {
        val alice = User("Alice")
        val bob = User("Bob")
        val policy = owned(Task::owner)
        val task = Task(alice, "Read the plan")

        assertTrue(policy.canWrite(task, alice))
        assertFalse(policy.canWrite(task, bob))
        assertTrue(policy.canRead(task, alice), "canRead defaults to canWrite")
        assertFalse(policy.canRead(task, bob))
    }

    // ---- Obtaining records -----------------------------------------------------------------------

    @Test
    fun `a collection contains only the records the principal may read`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        db.store(Task(alice, "alice's"))
        db.store(Task(bob, "bob's"))

        assertEquals(listOf("alice's"), with(alice) { db.tasks.map { it.title } })
        assertEquals(listOf("bob's"), with(bob) { db.tasks.map { it.title } })
    }

    @Test
    fun `a lookup of someone else's record is indistinguishable from no such record`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val task = db.store(Task(alice, "alice's"))

        assertEquals(task, with(alice) { Tasks.find(db, Id(task.id)) })
        assertNull(with(bob) { Tasks.find(db, Id(task.id)) })
        assertNull(with(bob) { Tasks.find(db, Id(9999)) })
    }

    @Test
    fun `an inverse relation is filtered the same way`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val project = db.store(Project(alice, "Inbox", shared = true))
        with(alice) {
            db.store(Task(alice, "filed").also { it.project = project })
            db.store(Task(alice, "elsewhere"))
        }

        assertEquals(listOf("filed"), with(alice) { project.tasks.map { it.title } })
        // Bob sees the project's tasks because the project is shared, and none of Alice's other records.
        assertEquals(listOf("filed"), with(bob) { project.tasks.map { it.title } })
        assertEquals(listOf("filed"), with(bob) { db.tasks.map { it.title } })
    }

    // ---- Writing -----------------------------------------------------------------------------------

    @Test
    fun `update writes through the draft`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val task = db.store(Task(alice, "Read the plan"))

        with(alice) { task.update { title = "Read it twice" } }

        assertEquals("Read it twice", task.title)
    }

    @Test
    fun `a write the policy refuses throws and changes nothing`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val task = db.store(Task(alice, "Read the plan"))

        val failure = assertFailsWith<AccessDenied> { with(bob) { task.update { title = "mine now" } } }

        assertContains(failure.message.orEmpty(), "may not change")
        assertEquals("Read the plan", task.title)
    }

    @Test
    fun `a refused column fails the whole update rather than being skipped`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val task = db.store(Task(alice, "Read the plan"))

        assertFailsWith<AccessDenied> {
            with(alice) {
                task.update {
                    title = "allowed"
                    archived = true // Only an admin can set this.
                }
            }
        }

        // The whole transaction is rolled back, including the allowed write. A partly applied update
        // is exactly what this design prevents.
        assertEquals("Read the plan", task.title)
        assertFalse(task.archived)
    }

    @Test
    fun `an admin may write the column nobody else may`(): Unit = withDb { db ->
        val root = db.store(User("Root", admin = true))
        val task = db.store(Task(root, "Read the plan"))

        with(root) { task.update { archived = true } }

        assertTrue(task.archived)
    }

    @Test
    fun `an admin may archive a task they do not own, and its owner may not`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val root = db.store(User("Root", admin = true))
        val task = db.store(Task(alice, "Read the plan"))

        assertFailsWith<AccessDenied> { with(alice) { task.update { archived = true } } }
        with(root) { task.update { archived = true } }

        assertTrue(task.archived)
    }

    @Test
    fun `add and delete are gated too`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))

        val mine = with(alice) { db.tasks.add(Task(alice, "mine")) }
        assertEquals(listOf(mine), with(alice) { db.tasks.toList() })

        // The same rule that hides a record from you refuses creating a record owned by someone else.
        assertFailsWith<AccessDenied> { with(bob) { db.tasks.add(Task(alice, "theirs")) } }
        assertFailsWith<AccessDenied> { with(bob) { mine.delete() } }

        with(alice) { mine.delete() }
        assertEquals(emptyList(), with(alice) { db.tasks.toList() })
    }

    @Test
    fun `a derived view has nothing to add to`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val project = db.store(Project(alice, "Inbox"))

        val failure = assertFailsWith<IllegalStateException> {
            with(alice) { project.tasks.add(Task(alice, "x")) }
        }

        assertContains(failure.message.orEmpty(), "derived")
    }

    @Test
    fun `a block reads back its own writes`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val task = db.store(Task(alice, "Read"))

        with(alice) {
            task.update {
                title = "$title the plan"
                title = "$title twice"
            }
        }

        assertEquals("Read the plan twice", task.title)
    }

    @Test
    fun `columns are checked against the record as it was, whatever the order of assignments`(): Unit =
        withDb { db ->
            val alice = db.store(User("Alice"))
            // A column rule that reads another column: a done task can't be renamed.
            val policy = object : Policy<Task, User> {
                override fun canWrite(record: Task, principal: User): Boolean = record.owner == principal
                override fun canWrite(record: Task, column: Column<Task>, principal: User): Boolean =
                    column != Tasks.title || !record.done
            }
            val first = db.store(Task(alice, "One"))
            val second = db.store(Task(alice, "Two"))

            // Both were not done when the block started, so both orders are allowed.
            Gate.update(first, policy, alice, TaskDraft(first)) { done = true; title = "One, done" }
            Gate.update(second, policy, alice, TaskDraft(second)) { title = "Two, done"; done = true }

            assertEquals("One, done", first.title)
            assertEquals("Two, done", second.title)
            // Now they're done, and renaming is refused whatever else the block does.
            assertFailsWith<AccessDenied> {
                Gate.update(first, policy, alice, TaskDraft(first)) { done = false; title = "One again" }
            }
            assertEquals("One, done", first.title)
            assertTrue(first.done, "the whole block rolled back")
        }

    @Test
    fun `an update cannot leave a record in a state that adding it would refuse`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val alices = db.store(Project(alice, "Alice's"))
        val bobs = db.store(Project(bob, "Bob's"))
        // Anyone can change their own task, but a task can only be filed in a project its creator owns.
        val policy = object : Policy<Task, User> {
            override fun canWrite(record: Task, principal: User): Boolean = record.owner == principal
            override fun canCreate(record: Task, principal: User): Boolean =
                canWrite(record, principal) && (record.project == null || record.project?.owner == principal)
        }
        val task = db.store(Task(alice, "Read the plan"))

        // Adding it straight into Bob's project is refused...
        assertFailsWith<AccessDenied> {
            Gate.add(db, policy, alice, Task(alice, "Spam").also { it.project = bobs })
        }
        // ...and so is getting there in two steps. Every check before the change passes: alice owns
        // the task, and the default column rule only asks that. Only the finished record is wrong.
        val failure = assertFailsWith<AccessDenied> {
            Gate.update(task, policy, alice, TaskDraft(task)) { project = bobs }
        }

        assertContains(failure.message.orEmpty(), "couldn't create")
        assertNull(task.project, "the block rolled back")
        Gate.update(task, policy, alice, TaskDraft(task)) { project = alices }
        assertEquals(alices, task.project)
    }

    @Test
    fun `a user can rename themselves but can't make themselves an admin`(): Unit = withDb { db ->
        val bob = db.store(User("Bob"))
        val root = db.store(User("Root", admin = true))

        with(bob) {
            bob.update { name = "Robert" }
            assertFailsWith<AccessDenied> { bob.update { admin = true } }
        }
        assertEquals("Robert", bob.name)
        assertFalse(bob.admin)

        with(root) { bob.update { admin = true } }
        assertTrue(bob.admin, "an admin can grant it")
    }

    // ---- Transferring ----------------------------------------------------------------------------

    @Test
    fun `an offered record changes hands when its recipient takes it`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val doc = db.store(Doc(alice, "The plan"))

        with(alice) { doc.update { offeredTo = bob } }
        with(bob) {
            assertTrue(doc.canTransferTo(bob))
            doc.transferTo(bob)
        }

        assertEquals(bob, doc.owner)
        assertTrue(Doc.canWrite(doc, bob))
        assertFalse(Doc.canRead(doc, alice), "alice gave it away")
    }

    @Test
    fun `a record can't be pushed onto someone, or taken without an offer`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val mallory = db.store(User("Mallory"))
        val bob = db.store(User("Bob"))
        val doc = db.store(Doc(alice, "The plan"))
        val spam = db.store(Doc(mallory, "Spam"))

        // Mallory can't hand her record to alice, by transfer or by update.
        with(mallory) {
            assertFalse(spam.canTransferTo(alice))
            assertFailsWith<AccessDenied> { spam.transferTo(alice) }
            assertFailsWith<AccessDenied> { spam.update { owner = alice } }
        }
        // Nor can she take alice's record, which isn't offered to anyone.
        with(mallory) { assertFailsWith<AccessDenied> { doc.transferTo(mallory) } }
        // An offer to bob is for bob only.
        with(alice) { doc.update { offeredTo = bob } }
        with(mallory) { assertFailsWith<AccessDenied> { doc.transferTo(mallory) } }

        assertEquals(mallory, spam.owner)
        assertEquals(alice, doc.owner)
    }

    @Test
    fun `withdrawing an offer revokes the transfer it allowed`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val doc = db.store(Doc(alice, "The plan"))

        with(alice) { doc.update { offeredTo = bob } }
        with(alice) { doc.update { offeredTo = null } }

        with(bob) {
            assertFalse(doc.canTransferTo(bob))
            assertFailsWith<AccessDenied> { doc.transferTo(bob) }
        }
    }

    // ---- Asking before writing -------------------------------------------------------------------

    @Test
    fun `a page can ask what a write would do, and gets the same answer`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val root = db.store(User("Root", admin = true))
        val task = db.store(Task(alice, "Read the plan"))

        with(alice) {
            assertTrue(task.canUpdate())
            assertTrue(task.canUpdate(Tasks.title))
            assertFalse(task.canUpdate(Tasks.archived), "only an admin can archive, owner or not")
            assertTrue(task.canDelete())
        }
        with(bob) {
            assertFalse(task.canUpdate())
            assertFalse(task.canUpdate(Tasks.title))
            assertFalse(task.canDelete())
        }
        with(root) {
            assertTrue(task.canUpdate(Tasks.archived))
            assertTrue(task.canDelete())
        }
    }

    @Test
    fun `asking about a column takes the record-level check too, as update does`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val task = db.store(Task(alice, "Read the plan"))
        // A column rule broader than the record rule. It can't widen anything, so asking the column
        // rule alone would give a page the wrong answer.
        val policy = object : Policy<Task, User> {
            override fun canWrite(record: Task, principal: User): Boolean = record.owner == principal
            override fun canWrite(record: Task, column: Column<Task>, principal: User): Boolean = true
        }

        assertTrue(policy.canWrite(task, Tasks.title, bob))
        assertFalse(Gate.canUpdate(task, Tasks.title, policy, bob))
        assertFailsWith<AccessDenied> { Gate.update(task, policy, bob, TaskDraft(task)) {} }
    }

    // ---- Reactive authorization ------------------------------------------------------------------

    @Test
    fun `unsharing a project removes its records from another principal's open page`(): Unit = runTest {
        withLiveDb { db ->
            val alice = db.store(User("Alice"))
            val bob = db.store(User("Bob"))
            val project = db.store(Project(alice, "Inbox", shared = true))
            with(alice) { db.store(Task(alice, "shared").also { it.project = project }) }

            // Bob's session, reading a collection filtered by what Bob can see.
            harness {
                Div {
                    with(bob) {
                        db.tasks.forEach { task -> key(task.id) { Span { Text(task.title) } } }
                    }
                }
            }.use { h ->
                assertEquals("shared", h.textOfFirstSpan())

                with(alice) { project.update { shared = false } }
                h.settle()

                // There's no invalidation code. The policy read `project.shared`, so writing it
                // invalidated the compositions whose filter had read it, and no others.
                assertEquals(listOf(Op.Remove(parent = 1, index = 0, count = 1)), h.drain())
            }
        }
    }

    @Test
    fun `losing admin revokes the column it granted, with no re-checking anywhere`(): Unit = withDb { db ->
        val root = db.store(User("Root", admin = true))
        val task = db.store(Task(root, "Read the plan"))

        with(root) { task.update { archived = true } }

        // An admin removes their own admin role. The policy reads `principal.admin`, which is a cell,
        // so the next write is refused without any explicit notification.
        with(root) { root.update { admin = false } }

        assertFailsWith<AccessDenied> { with(root) { task.update { archived = false } } }
    }

    // ---- Guardrails ------------------------------------------------------------------------------

    @Test
    fun `the leak detector catches a record read under a principal that never obtained it`(): Unit =
        withDb { db ->
            val alice = db.store(User("Alice"))
            val bob = db.store(User("Bob"))
            val task = with(alice) { db.tasks.add(Task(alice, "Read the plan")) }

            // Obtained for Alice through the gate.
            with(alice) { assertEquals(task, db.tasks.single()) }

            // Read as Bob, who never obtained it. This simulates a reference leaking out of Alice's session.
            val failure = assertFailsWith<LeakDetected> { CurrentPrincipal.with(bob) { task.title } }

            assertContains(failure.message.orEmpty(), "never obtained it")
            assertNotNull(failure.cause, "the failure carries the stack where the record was acquired")
            assertContains(failure.cause?.message.orEmpty(), "acquired here")
        }

    @Test
    fun `a record read under the principal that obtained it is fine`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val task = with(alice) { db.tasks.add(Task(alice, "Read the plan")) }
        with(alice) { db.tasks.single() }

        assertEquals("Read the plan", CurrentPrincipal.with(alice) { task.title })
    }

    @Test
    fun `a shared record read by either of its principals is fine`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val project = db.store(Project(alice, "Inbox", shared = true))
        val task = with(alice) { db.tasks.add(Task(alice, "shared").also { it.project = project }) }

        with(alice) { db.tasks.single() }
        with(bob) { db.tasks.single() }

        assertEquals("shared", CurrentPrincipal.with(alice) { task.title })
        assertEquals("shared", CurrentPrincipal.with(bob) { task.title })
    }

    @Test
    fun `unsafe bypasses the gate`(): Unit = withDb { db ->
        val alice = db.store(User("Alice"))
        val bob = db.store(User("Bob"))
        val task = db.store(Task(alice, "Read the plan"))

        assertEquals(emptyList(), with(bob) { db.tasks.toList() })

        // For the rare work an application does on its own behalf, such as a backfill or an admin console.
        val seen = unsafe("test fixture reads every record") { with(bob) { db.tasks.map { it.title } } }
        assertEquals(listOf("Read the plan"), seen)

        unsafe("test fixture archives without being an admin") {
            with(bob) { task.update { archived = true } }
        }
        assertTrue(task.archived)

        // Policy checks apply again as soon as the block ends.
        assertFailsWith<AccessDenied> { with(bob) { task.update { archived = false } } }
    }

    @Test
    fun `every generated mutation requires a principal to call`(): Unit {
        // `update` can't be called without a principal, because the principal is a context
        // parameter. That guarantee exists only at compile time, so this test checks the bytecode.
        val update = Class.forName("jetlin.db.TaskTableKt").methods.single { it.name.startsWith("update") }

        // In the JVM signature, a context parameter comes before the extension receiver.
        assertEquals(
            listOf(User::class.java, Task::class.java),
            update.parameterTypes.take(2).toList(),
            "the principal is the context parameter, and it is not optional",
        )
    }
}

/** Returns the text of the first `<span>`, for checking what the session shows. */
private suspend fun Harness.textOfFirstSpan(): String = html().substringAfter("<span").substringAfter('>')
    .substringBefore("</span>")

/**
 * A database in a temporary file, with a helper for storing fixtures.
 *
 * [store] always runs inside `unsafe`. Creating a fixture such as "a task owned by someone else" is
 * exactly what the policies refuse, and a test that had to satisfy the policy to set up its fixtures
 * couldn't test the policy.
 */
@OptIn(ExperimentalPathApi::class)
private fun withDb(block: (Db) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        Db.open(directory.resolve("test.db"), schema()).use(block)
    } finally {
        directory.deleteRecursively()
    }
}

/** Like the function above, for tests that run a live session. */
@OptIn(ExperimentalPathApi::class)
private suspend fun withLiveDb(block: suspend (Db) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        Db.open(directory.resolve("test.db"), schema()).use { db -> block(db) }
    } finally {
        directory.deleteRecursively()
    }
}

private fun <T : Record> Db.store(record: T): T = transact { insert(record) }
