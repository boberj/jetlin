package jetlin.db

import androidx.compose.runtime.key
import jetlin.html.Div
import jetlin.html.Span
import jetlin.html.Text
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Tests which ops a write to a record produces.
 *
 * Everything else in the module depends on this: if reading a field doesn't subscribe the composable
 * that read it, persistence doesn't help. The tests compare exact op lists instead of using "contains",
 * so a write that updates more of the page than the changed field fails here instead of wasting
 * bandwidth in every session.
 *
 * Every write in this file comes from the test thread, outside any composition or snapshot. That is how
 * a background job, a load at startup or another user's session writes. Such writes reach the
 * recomposer through `GlobalSnapshotManager`, without any subscription or broadcast.
 */
class CellReactivityTest {

    @Test
    fun `writing a field updates only the text that read it`(): Unit = runTest {
        val alice = User("Alice")
        val task = Task(alice, "Read the plan")
        harness {
            Div {
                Span { Text(task.title) }
                Span { Text("static") }
            }
        }.use { h ->
            h.write { task.title = "Read it twice" }

            assertEquals(listOf(Op.SetText(3, "Read it twice")), h.drain())
        }
    }

    @Test
    fun `a field nothing read produces no ops at all`(): Unit = runTest {
        val alice = User("Alice")
        val task = Task(alice, "Read the plan")
        harness { Div { Span { Text(task.title) } } }.use { h ->
            h.write { task.done = true }

            assertEquals(emptyList<Op>(), h.drain())
        }
    }

    @Test
    fun `two writes in one snapshot produce one op each and a single pass`(): Unit = runTest {
        val alice = User("Alice")
        val task = Task(alice, "one")
        harness {
            Div {
                Span { Text(task.title) }
                Span { Text(task.done.toString()) }
            }
        }.use { h ->
            val passes = h.changeCount
            h.write {
                task.title = "two"
                task.done = true
            }

            assertEquals(listOf(Op.SetText(3, "two"), Op.SetText(5, "true")), h.drain())
            assertEquals(1, h.changeCount - passes, "expected a single recomposition pass")
        }
    }

    @Test
    fun `adding a record to the identity map inserts one node`(): Unit = runTest {
        val alice = User("Alice")
        val db = IdentityMap()
        db.add(Task(alice, "first"))
        harness {
            Div {
                // Keyed by record, as views are normally iterated. Without the key, the runtime reuses
                // nodes by position, so removing a record would rewrite every node after it instead of
                // removing one.
                db.all(Task::class).forEach { task -> key(task.id) { Span { Text(task.title) } } }
            }
        }.use { h ->
            h.write { db.add(Task(alice, "second")) }

            val ops = h.drain()
            assertEquals(1, ops.size, "expected a single Insert, got $ops")
            val inserted = assertIs<NodeSpec.Element>(assertIs<Op.Insert>(ops.single()).node)
            assertEquals("second", assertIs<NodeSpec.Text>(inserted.children.single()).text)
        }
    }

    @Test
    fun `removing a record removes its node and leaves its siblings alone`(): Unit = runTest {
        val alice = User("Alice")
        val db = IdentityMap()
        val first = db.add(Task(alice, "first"))
        db.add(Task(alice, "second"))
        harness {
            Div { db.all(Task::class).forEach { task -> key(task.id) { Span { Text(task.title) } } } }
        }.use { h ->
            h.write { db.remove(first) }

            assertEquals(listOf(Op.Remove(parent = 1, index = 0, count = 1)), h.drain())
        }
    }

    @Test
    fun `traversing a reference subscribes to the target's fields`(): Unit = runTest {
        val alice = User("Alice")
        val project = Project(alice, "Inbox")
        val task = Task(alice, "Read the plan").also { it.project = project }
        harness { Div { Span { Text(task.project?.name ?: "none") } } }.use { h ->
            h.write { project.name = "Renamed" }
            assertEquals(listOf(Op.SetText(3, "Renamed")), h.drain())

            // Changing which record the reference points to is as reactive as writing through it.
            h.write { task.project = null }
            assertEquals(listOf(Op.SetText(3, "none")), h.drain())
        }
    }

    @Test
    fun `a view reflects the filter it was created with`(): Unit = runTest {
        val alice = User("Alice")
        val db = IdentityMap()
        val keep = db.add(Task(alice, "keep"))
        val hide = db.add(Task(alice, "hide", done = true))
        val open = View(db.records(Task::class), { !it.done })

        assertEquals(listOf(keep), open.toList())
        assertEquals(1, open.size)
        assertTrue(keep in open)
        assertFalse(hide in open)

        // The view doesn't cache: it returns a different result once the state its filter read changes.
        // This is what makes policy-filtered collections update when access is revoked.
        keep.done = true
        hide.done = false
        assertEquals(listOf(hide), open.toList())
    }

    @Test
    fun `records are identified by reference and carry unique ids`(): Unit = runTest {
        val alice = User("Alice")
        val one = Task(alice, "same")
        val two = Task(alice, "same")

        assertNotEquals(one, two)
        assertNotEquals(one.id, two.id)
        assertSame(one, setOf(one, two).first { it.id == one.id })
    }

    @Test
    fun `a record names its cells in declaration order`(): Unit = runTest {
        assertEquals(
            listOf("title", "done", "archived", "project"),
            Task(User("Alice"), "x").cells.keys.toList(),
        )
    }
}
