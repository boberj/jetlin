package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import jetlin.protocol.JetlinJson
import jetlin.protocol.Namespace
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * That a drawing arrives as a drawing, on both paths into the browser.
 *
 * The failure this guards against makes no noise: `createElement("circle")` is not an error, it is
 * an `HTMLUnknownElement` of zero size, so a chart that ends up in the wrong language is a blank
 * space and a page that looks fine. Nothing downstream would catch it, which is why the language is
 * asserted here at each of the three points it has to survive — the tree, the markup, and the op.
 */
class SvgTest {

    @Test
    fun `everything inside a drawing is composed in the SVG language`(): Unit = runBlocking {
        val view = LiveView { _ ->
            Svg({ attr("viewBox", "0 0 40 40") }) {
                G { Circle({ attr("r", "4") }) }
            }
        }
        view.use {
            view.start()
            assertEquals(
                listOf(
                    "svg" to Namespace.SVG,
                    "g" to Namespace.SVG,
                    "circle" to Namespace.SVG,
                ),
                view.languages(),
            )
        }
    }

    @Test
    fun `a foreign object hands the browser back to HTML`(): Unit = runBlocking {
        val view = LiveView { _ ->
            Svg {
                ForeignObject({ attr("width", "80"); attr("height", "20") }) {
                    P { Text("wrapped") }
                }
            }
        }
        view.use {
            view.start()
            assertEquals(
                listOf(
                    "svg" to Namespace.SVG,
                    // The element itself is SVG; only what it contains is not.
                    "foreignObject" to Namespace.SVG,
                    "p" to Namespace.HTML,
                ),
                view.languages(),
            )
        }
    }

    @Test
    fun `a drawing serializes with every shape closed and its attribute case intact`(): Unit = assertRenders(
        """<svg data-jl="1" viewBox="0 0 100 40" class="spark">""" +
            """<title data-jl="2" data-jl-t="0:3">Speed</title>""" +
            """<path data-jl="4" d="M0 20 L100 20" fill="none"></path>""" +
            "</svg>",
    ) {
        Svg({ attr("viewBox", "0 0 100 40"); classes("spark") }) {
            SvgTitle { Text("Speed") }
            Path({ attr("d", "M0 20 L100 20"); attr("fill", "none") })
        }
    }

    // Nothing is void in foreign content, but everything inside a foreign object is HTML again, so
    // the <br> has to go back to being written without a closing tag.
    @Test
    fun `void elements are an HTML rule and apply again inside a foreign object`(): Unit = assertRenders(
        """<svg data-jl="1"><foreignObject data-jl="2" width="80">""" +
            """<br data-jl="3"></foreignObject></svg>""",
    ) {
        Svg {
            ForeignObject({ attr("width", "80") }) { Element("br") }
        }
    }

    @Test
    fun `a shape added later carries its language in the op that inserts it`(): Unit = runBlocking {
        val peaked = mutableStateOf(false)
        val view = LiveView { _ ->
            Svg({ attr("viewBox", "0 0 40 40") }) {
                if (peaked.value) Circle({ attr("cx", "20"); attr("r", "3") })
            }
        }
        view.use {
            view.start()
            view.owner.drainOps()

            peaked.value = true
            view.awaitIdle()
            assertEquals(
                listOf(
                    Op.Insert(
                        parent = 1,
                        index = 0,
                        node = NodeSpec.Element(
                            id = 2,
                            tag = "circle",
                            namespace = Namespace.SVG,
                            attrs = mapOf("cx" to "20", "r" to "3"),
                        ),
                    ),
                ),
                view.owner.drainOps(),
            )
        }
    }

    @Test
    fun `redrawing a shape is one attribute write like anything else`(): Unit = runBlocking {
        val radius = mutableStateOf(4)
        val view = LiveView { _ ->
            Svg({ attr("viewBox", "0 0 40 40") }) { Circle({ attr("r", "${radius.value}") }) }
        }
        view.use {
            view.start()
            view.owner.drainOps()

            radius.value = 6
            view.awaitIdle()
            assertEquals(listOf(Op.SetAttr(2, "r", "6")), view.owner.drainOps())
        }
    }

    @Test
    fun `the language is on the wire only where it is not HTML`() {
        assertEquals(
            """{"t":"e","id":1,"tag":"circle","ns":"svg"}""",
            JetlinJson.encodeToString(NodeSpec.serializer(), NodeSpec.Element(id = 1, tag = "circle", namespace = Namespace.SVG)),
        )
        // The default is not encoded, so an application with no charts in it pays nothing at all.
        assertEquals(
            """{"t":"e","id":1,"tag":"div"}""",
            JetlinJson.encodeToString(NodeSpec.serializer(), NodeSpec.Element(id = 1, tag = "div")),
        )
    }

    @Test
    fun `a shape composed outside a drawing is refused`(): Unit = runBlocking {
        val failure = runCatching {
            val view = LiveView { _ -> Div { Circle({ attr("r", "4") }) } }
            view.use { view.start() }
        }.exceptionOrNull()

        val reported = generateSequence(failure) { it.cause }.mapNotNull { it.message }.joinToString("\n")
        assertTrue(
            "<circle> is an SVG element and has to be composed inside Svg { }" in reported,
            "expected the misplaced shape to be named, got: $reported",
        )
    }
}

/** Every element in the tree, in document order, as tag and the language it belongs to. */
private suspend fun LiveView.languages(): List<Pair<String, Namespace>> = inspect { owner ->
    buildList {
        fun walk(node: ElementNode) {
            for (child in node.childNodes) {
                if (child is ElementNode) {
                    add(child.tag to child.namespace)
                    walk(child)
                }
            }
        }
        walk(owner.root)
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
