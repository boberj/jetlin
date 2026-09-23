package jetlin.html

import androidx.compose.runtime.Composable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Tests the markers that let the client index server-rendered markup instead of receiving the tree.
 *
 * All of them concern text. Elements identify themselves with `data-jl`, but a text node has no
 * attributes, an HTML parser merges two adjacent text nodes into one, and an empty one produces no
 * node at all. So the markup has to state what the parser can't.
 */
class AdoptionMarkersTest {

    @Test
    fun `a text child is named by its index on the parent`(): Unit = assertRenders(
        """<div data-jl="1" data-jl-t="0:2">hello</div>""",
    ) {
        Div { Text("hello") }
    }

    // Without a separator, the parser produces one "onetwo" node, which leaves the client a child
    // short and every later index wrong.
    @Test
    fun `adjacent text children are kept apart`(): Unit = assertRenders(
        """<div data-jl="1" data-jl-t="0:2,1:3">one<!--|-->two</div>""",
    ) {
        Div {
            Text("one")
            Text("two")
        }
    }

    @Test
    fun `an empty text child leaves a marker standing in for it`(): Unit = assertRenders(
        """<div data-jl="1" data-jl-t="0:2,1:3"><!--0-->after</div>""",
    ) {
        Div {
            Text("")
            Text("after")
        }
    }

    // The empty marker is itself a boundary, so no separator is needed next to it.
    @Test
    fun `an empty marker separates the text either side of it`(): Unit = assertRenders(
        """<div data-jl="1" data-jl-t="0:2,1:3,2:4">before<!--0-->after</div>""",
    ) {
        Div {
            Text("before")
            Text("")
            Text("after")
        }
    }

    // Indexes 0 and 2 are text. Index 1 is the span, which identifies itself.
    @Test
    fun `text interleaved with elements is indexed by logical position`(): Unit = assertRenders(
        """<div data-jl="1" data-jl-t="0:2,2:5">start""" +
            """<span data-jl="3" data-jl-t="0:4">middle</span>end</div>""",
    ) {
        Div {
            Text("start")
            Span { Text("middle") }
            Text("end")
        }
    }

    @Test
    fun `an element with no text children carries no marker`(): Unit = assertRenders(
        """<div data-jl="1"><span data-jl="2"></span></div>""",
    ) {
        Div { Span() }
    }

    // data-jl-raw tells the client to stop here. Those nodes belong to whoever wrote the markup, and
    // walking into them would claim nodes the server has never heard of.
    @Test
    fun `raw markup is flagged and its content is not indexed`(): Unit = assertRenders(
        """<div data-jl="1" data-jl-raw><b>not ours</b></div>""",
    ) {
        Div({ unsafeInnerHtml("<b>not ours</b>") })
    }

    @Test
    fun `the container gets its identity and markers separately`(): Unit = runBlocking {
        // renderToHtml writes the root's children, and the page shell writes the root element itself,
        // so what the client needs to know about the root has to travel another way.
        val view = LiveView { _ ->
            Text("loose text")
            Div()
        }
        view.use {
            view.start()
            assertEquals(""" data-jl="0" data-jl-t="0:1"""", view.rootAttributes())
        }
    }

    @Test
    fun `a container holding only elements needs no text markers`(): Unit = runBlocking {
        val view = LiveView { _ -> Div() }
        view.use {
            view.start()
            assertEquals(""" data-jl="0"""", view.rootAttributes())
        }
    }
}

/** Renders [content] once and compares the markup with [expected]. */
private fun assertRenders(expected: String, content: @Composable () -> Unit): Unit = runBlocking {
    val view = LiveView { _ -> content() }
    view.use {
        view.start()
        assertEquals(expected, view.renderHtml())
    }
}
