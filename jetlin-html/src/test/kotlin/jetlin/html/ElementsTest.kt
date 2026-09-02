package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import jetlin.protocol.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * That the convenience layer emits the tag it claims to.
 *
 * Cheap assertions, and worth having anyway: a helper naming the wrong tag produces markup that
 * looks plausible and behaves nothing like it — a `<dailog>` is a `<div>` with a strange name as
 * far as the browser is concerned, and nothing else in the stack would notice.
 */
class ElementsTest {

    @Test
    fun `the heading levels below h3 render as themselves`(): Unit = assertRenders(
        """<h4 data-jl="1" data-jl-t="0:2">four</h4>""" +
            """<h5 data-jl="3" data-jl-t="0:4">five</h5>""" +
            """<h6 data-jl="5" data-jl-t="0:6">six</h6>""",
    ) {
        H4 { Text("four") }
        H5 { Text("five") }
        H6 { Text("six") }
    }

    @Test
    fun `an ordered list renders as ol`(): Unit = assertRenders(
        """<ol data-jl="1"><li data-jl="2" data-jl-t="0:3">first</li></ol>""",
    ) {
        Ol { Li { Text("first") } }
    }

    @Test
    fun `a table can carry a caption and a foot`(): Unit = assertRenders(
        """<table data-jl="1">""" +
            """<caption data-jl="2" data-jl-t="0:3">Fleet</caption>""" +
            """<tbody data-jl="4"><tr data-jl="5"><td data-jl="6" data-jl-t="0:7">Aurora</td></tr></tbody>""" +
            """<tfoot data-jl="8"><tr data-jl="9"><td data-jl="10" data-jl-t="0:11">1 vessel</td></tr></tfoot>""" +
            "</table>",
    ) {
        Table {
            Caption { Text("Fleet") }
            Tbody { Tr { Td { Text("Aurora") } } }
            Tfoot { Tr { Td { Text("1 vessel") } } }
        }
    }

    @Test
    fun `a disclosure renders closed unless it is opened`(): Unit = assertRenders(
        """<details data-jl="1"><summary data-jl="2" data-jl-t="0:3">More</summary></details>""" +
            """<details data-jl="4" open><summary data-jl="5" data-jl-t="0:6">Already open</summary></details>""",
    ) {
        Details { Summary { Text("More") } }
        Details({ open(true) }) { Summary { Text("Already open") } }
    }

    @Test
    fun `a dialog renders as itself and stays closed`(): Unit = assertRenders(
        """<dialog data-jl="1" data-jl-t="0:2">Are you sure?</dialog>""",
    ) {
        Dialog { Text("Are you sure?") }
    }

    @Test
    fun `opening a dialog is one attribute write`(): Unit = runBlocking {
        val showing = mutableStateOf(false)
        val view = LiveView { _ ->
            Dialog({ open(showing.value) }) { Text("Are you sure?") }
        }
        view.use {
            view.start()
            view.owner.drainOps()

            showing.value = true
            view.awaitIdle()
            assertEquals(listOf(Op.SetAttr(1, "open", "")), view.owner.drainOps())

            showing.value = false
            view.awaitIdle()
            assertEquals(listOf(Op.SetAttr(1, "open", null)), view.owner.drainOps())
        }
    }
}

/** Renders [content] once and compares the markup against [expected]. */
private fun assertRenders(expected: String, content: @Composable () -> Unit): Unit = runBlocking {
    val view = LiveView { _ -> content() }
    view.use {
        view.start()
        assertEquals(expected, view.renderHtml())
    }
}
