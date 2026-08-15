package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jetlin.protocol.EventPayload
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import jetlin.protocol.PropValue
import jetlin.runtime.CompositionHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * These tests are the load-bearing claim of the whole project: that a state change produces the
 * *minimum* set of DOM mutations without any diffing step, because the Compose runtime already
 * knows what changed. Assertions are on exact op lists, not on "contains", so any extra chatter
 * fails the build.
 */
class HtmlApplierTest {

    @Test
    fun `renders server-side HTML with node ids`() = runTest {
        harness {
            Div({ classes("card") }) {
                H1 { Text("Hello") }
                Button({ onClick { } }) { Text("Go") }
            }
        }.use { h ->
            assertEquals(
                """<div data-jl="1" class="card">""" +
                    """<h1 data-jl="2">Hello</h1>""" +
                    """<button data-jl="4" data-jl-on="{&quot;click&quot;:{}}">Go</button>""" +
                    "</div>",
                h.html(),
            )
        }
    }

    @Test
    fun `text change produces exactly one SetText`() = runTest {
        var count by mutableStateOf(0)
        harness {
            Div {
                H1 { Text("Count: $count") }
                P { Text("static") }
            }
        }.use { h ->
            h.act { count = 1 }
            assertEquals(listOf(Op.SetText(3, "Count: 1")), h.drain())
        }
    }

    @Test
    fun `attribute change produces exactly one SetAttr and leaves siblings alone`() = runTest {
        var active by mutableStateOf(false)
        harness {
            Div {
                Span({ classes(if (active) "on" else "off") }) { Text("a") }
                Span({ classes("fixed") }) { Text("b") }
            }
        }.use { h ->
            h.act { active = true }
            assertEquals(listOf(Op.SetAttr(2, "class", "on")), h.drain())
        }
    }

    @Test
    fun `appending a list item ships one Insert carrying the whole subtree`() = runTest {
        val items = mutableStateListOf("one")
        harness {
            Ul {
                items.forEach { item ->
                    key(item) { Li { Text(item) } }
                }
            }
        }.use { h ->
            h.act { items.add("two") }

            val ops = h.drain()
            assertEquals(1, ops.size, "expected a single Insert, got $ops")
            val insert = assertIs<Op.Insert>(ops.single())
            assertEquals(1, insert.index)
            val element = assertIs<NodeSpec.Element>(insert.node)
            assertEquals("li", element.tag)
            assertEquals("two", assertIs<NodeSpec.Text>(element.children.single()).text)
        }
    }

    @Test
    fun `removing a list item ships one Remove`() = runTest {
        val items = mutableStateListOf("one", "two", "three")
        harness {
            Ul { items.forEach { item -> key(item) { Li { Text(item) } } } }
        }.use { h ->
            h.act { items.remove("two") }
            assertEquals(listOf(Op.Remove(1, 1, 1)), h.drain())
        }
    }

    @Test
    fun `reordering keyed items moves nodes instead of rebuilding them`() = runTest {
        val items = mutableStateListOf("a", "b", "c")
        harness {
            Ul { items.forEach { item -> key(item) { Li { Text(item) } } } }
        }.use { h ->
            h.act {
                items.clear()
                items.addAll(listOf("c", "a", "b"))
            }

            val ops = h.drain()
            assertTrue(
                ops.all { it is Op.Move },
                "keyed reorder must not recreate nodes, got $ops",
            )
        }
    }

    @Test
    fun `unchanged content produces no ops at all`() = runTest {
        var unrelated by mutableStateOf(0)
        harness {
            Div { Text("constant") }
        }.use { h ->
            h.act { unrelated = 1 }
            assertEquals(emptyList(), h.drain())
        }
    }

    @Test
    fun `events route to the handler that is current, not the one that was captured first`() = runTest {
        var count by mutableStateOf(0)
        val seen = mutableListOf<Int>()
        harness {
            // The lambda captures `count` by value each pass; a stale handler would record 0 twice.
            Button({ onClick { seen += count; count++ } }) { Text("$count") }
        }.use { h ->
            h.event(1, "click")
            h.event(1, "click")
            assertEquals(listOf(0, 1), seen)
        }
    }

    @Test
    fun `value is set as a DOM property rather than an attribute`() = runTest {
        var text by mutableStateOf("a")
        harness {
            Input({ value(text); onInput { } })
        }.use { h ->
            h.act { text = "b" }
            assertEquals(listOf(Op.SetProp(1, "value", PropValue.Str("b"))), h.drain())
        }
    }

    @Test
    fun `text content is escaped rather than interpolated`() = runTest {
        harness {
            Div { Text("<script>alert('xss')</script>") }
        }.use { h ->
            assertEquals(
                """<div data-jl="1">&lt;script&gt;alert('xss')&lt;/script&gt;</div>""",
                h.html(),
            )
        }
    }
}

private suspend fun harness(content: @Composable () -> Unit): Harness =
    Harness(content).also { it.start() }

private class Harness(private val content: @Composable () -> Unit) : AutoCloseable {
    val owner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner))

    suspend fun start() {
        host.setContent {
            CompositionLocalProvider(LocalHtmlOwner provides owner) { content() }
        }
        drain()
    }

    suspend fun drain(): List<Op> = host.confined { owner.drainOps() }

    suspend fun act(block: () -> Unit) {
        host.transact(block)
    }

    suspend fun event(node: Int, name: String, payload: EventPayload = EventPayload()) {
        host.transact { owner.dispatch(node, name, payload) }
    }

    suspend fun html(): String = host.confined { renderToHtml(owner) }

    override fun close(): Unit = host.close()
}
