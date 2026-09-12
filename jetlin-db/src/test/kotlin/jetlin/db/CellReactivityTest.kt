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
 * What a write to a record actually costs on the wire.
 *
 * This is the substrate everything else is built on: if reading a field does not subscribe the
 * composable that read it, nothing about persistence matters. Assertions are exact op lists rather
 * than "contains", so a write that redraws more of the page than the field it changed fails here
 * rather than quietly costing bandwidth in every session.
 *
 * Every write in this file is made from the test thread, outside any composition and outside any
 * snapshot — which is how a background job, a boot-time load or another user's session writes. Those
 * reach the recomposer through `GlobalSnapshotManager`, with nothing subscribing or broadcasting.
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
    fun `adding a row to the identity map inserts one node`(): Unit = runTest {
        val alice = User("Alice")
        val db = IdentityMap()
        db.add(Task(alice, "first"))
        harness {
            Div {
                // Keyed by record identity, which is what a view is iterated with: without it the
                // runtime reuses node slots positionally, so removing a row rewrites the rows after it
                // instead of removing one node.
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
    fun `removing a row removes its node and leaves its siblings alone`(): Unit = runTest {
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

            // Repointing the reference itself is just as reactive as writing through it.
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
        val open = View(db.rows(Task::class), { !it.done })

        assertEquals(listOf(keep), open.toList())
        assertEquals(1, open.size)
        assertTrue(keep in open)
        assertFalse(hide in open)

        // Not cached: the same view answers differently once the state the filter read changes. This
        // is what makes policy-filtered collections revoke reactively rather than going stale.
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
