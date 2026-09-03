package jetlin.samples.keyed

import jetlin.html.ElementNode
import jetlin.html.HtmlNode
import jetlin.html.HtmlOwner
import jetlin.html.LiveView
import jetlin.html.TextNode
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.JetlinJson
import jetlin.protocol.NodeId
import jetlin.protocol.Op
import jetlin.protocol.ServerMessage

/**
 * One benchmark session, driven the way the browser drives it.
 *
 * The harness clicks `#run` and reads `tbody>tr:nth-of-type(1000)`; so does this, against the
 * server's own node tree. Nothing here reaches into [RowStore] to make something happen — every
 * operation goes in as a client event naming a node, through the same dispatch path a socket
 * frame would take, so what is measured is the work a real click causes and not a method call.
 *
 * There is no browser on the other end, which is the point: this measures the half of the round
 * trip Jetlin is responsible for, and says how much has to cross the wire for the other half.
 */
class Driver private constructor(private val view: LiveView, val store: RowStore) : AutoCloseable {

    private var seq = 0L
    private var rev = 0L

    companion object {
        private const val ID_CELL = 0
        private const val LABEL_CELL = 1
        private const val REMOVE_CELL = 2

        /**
         * What a cold HTTP request for this page costs, split into its two halves.
         *
         * Deliberately not [open] followed by [html]: that would compose an empty page, build the
         * rows through a click, and then time only the serializer — which is the measurement that
         * makes first paint look ten times cheaper than creating the same rows. A real first request
         * composes the view it is going to serve and then writes it out, so [rows] are in the store
         * before the composition starts and both halves are on the clock.
         */
        suspend fun firstPaint(rows: Int, chunk: Int = FLAT): FirstPaint {
            val store = RowStore()
            if (rows > 0) store.run(rows)
            val view = LiveView { BenchmarkPage(store, chunk) }
            view.owner.maxBufferedOps = Int.MAX_VALUE
            try {
                val start = System.nanoTime()
                view.start()
                val composed = System.nanoTime()
                val html = view.renderHtml()
                val written = System.nanoTime()
                return FirstPaint(
                    rows = rows,
                    composeNanos = composed - start,
                    renderNanos = written - composed,
                    bytes = html.toByteArray(Charsets.UTF_8).size,
                )
            } finally {
                view.close()
            }
        }

        suspend fun open(seed: Int = 0, chunk: Int = FLAT): Driver {
            val store = RowStore(seed)
            val view = LiveView { BenchmarkPage(store, chunk) }
            // Creating ten thousand rows is ten thousand inserts, and a table replaced wholesale is
            // twice that. The default ceiling exists to stop a session that nobody is reading from
            // pinning memory; here every patch is drained the moment it is produced, and letting the
            // buffer overflow would silently swap the patch being measured for a full-tree resend.
            view.owner.maxBufferedOps = Int.MAX_VALUE
            view.start()
            return Driver(view, store)
        }
    }

    /** Clicks one of the jumbotron buttons, by the id the benchmark names it with. */
    suspend fun click(buttonId: String): Patch = clickNode(nodeId { owner ->
        owner.root.first { it.attribute("id") == buttonId }
            ?: error("No element with id '$buttonId' on the page")
    })

    /** Clicks the label in row [index], which is what selects a row. */
    suspend fun selectRow(index: Int): Patch = clickNode(nodeId { owner ->
        owner.cell(index, LABEL_CELL).first { it.tag == "a" } ?: error("Row $index has no label link")
    })

    /** Clicks the remove glyph in row [index]. */
    suspend fun removeRow(index: Int): Patch = clickNode(nodeId { owner ->
        owner.cell(index, REMOVE_CELL).first { it.tag == "a" } ?: error("Row $index has no remove link")
    })

    suspend fun rowCount(): Int = view.inspect { it.tbody().children().size }

    suspend fun rowId(index: Int): String = view.inspect { it.cell(index, ID_CELL).text() }

    suspend fun rowLabel(index: Int): String =
        view.inspect { it.cell(index, LABEL_CELL).first { node -> node.tag == "a" }!!.text() }

    /** Whether row [index] carries the `danger` class the benchmark checks for. */
    suspend fun rowSelected(index: Int): Boolean =
        view.inspect { it.tbody().children()[index].attribute("class") == "danger" }

    suspend fun selectedCount(): Int =
        view.inspect { owner -> owner.tbody().children().count { it.attribute("class") == "danger" } }

    /** The whole page as the browser would first receive it. */
    suspend fun html(): String = view.renderHtml()

    /**
     * Applies [change] to the rows directly and returns the patch it produced.
     *
     * Not how any of the nine benchmarks are driven — those go through a click, because that is what
     * is being measured. This is for the scaling sweep, whose row counts have no button to press:
     * the six operations are defined against tables of 1,000 and 10,000 and nothing else. Everything
     * after the state write is identical either way.
     */
    suspend fun mutate(change: RowStore.() -> Unit): Patch {
        val start = System.nanoTime()
        store.change()
        view.awaitIdle()
        val composed = System.nanoTime()
        val ops = view.inspect { it.drainOps() }
        val encoded = JetlinJson.encodeToString(
            ServerMessage.serializer(),
            ServerMessage.Patch(++rev, seq, ops),
        )
        return Patch(
            composeNanos = composed - start,
            serializeNanos = System.nanoTime() - composed,
            ops = ops,
            bytes = encoded.toByteArray(Charsets.UTF_8).size,
        )
    }

    /**
     * Reads the settled node tree, for the assertions the click-and-look API does not cover.
     *
     * The tree is mutated by the applier on its own dispatcher, so this is the only safe way in.
     */
    suspend fun <T> read(block: (HtmlOwner) -> T): T = view.inspect(block)

    override fun close(): Unit = view.close()

    /**
     * Sends the click and returns everything the recomposition produced.
     *
     * [LiveView.dispatch] returns once the update has been applied, so the elapsed time around it
     * is the server's whole share of the interaction: the handler, the recomposition, and the
     * applier writing the edits down.
     */
    private suspend fun clickNode(node: NodeId): Patch {
        val start = System.nanoTime()
        view.dispatch(ClientMessage.Event(node = node, event = "click", seq = ++seq))
        val composed = System.nanoTime()
        val ops = view.inspect { it.drainOps() }
        val encoded = JetlinJson.encodeToString(
            ServerMessage.serializer(),
            ServerMessage.Patch(++rev, seq, ops),
        )
        val serialized = System.nanoTime()
        return Patch(
            composeNanos = composed - start,
            serializeNanos = serialized - composed,
            ops = ops,
            bytes = encoded.toByteArray(Charsets.UTF_8).size,
        )
    }

    private suspend fun nodeId(find: (HtmlOwner) -> ElementNode): NodeId = view.inspect { find(it).id }
}

/**
 * A cold request for the page: composing the view, then writing it out as HTML.
 *
 * The two halves are kept apart because they answer different questions. [composeNanos] is what the
 * page costs to exist at all — the same work a click that built the rows would do. [renderNanos] is
 * the serializer, and is the one number that has a direct counterpart on the socket side: writing a
 * patch as JSON is the same walk over the same nodes.
 */
data class FirstPaint(
    val rows: Int,
    val composeNanos: Long,
    val renderNanos: Long,
    val bytes: Int,
) {
    val totalNanos: Long get() = composeNanos + renderNanos
}

/**
 * One update, from the server's side of the socket.
 *
 * [ops] and [bytes] are the parts a client-side framework has no equivalent of, and they are the
 * ones that decide what the interaction actually costs a user on a slow link. An op count that
 * tracks the number of rows *changed* rather than the number of rows *present* is what "keyed"
 * buys, and it is visible here directly rather than inferred from a stopwatch.
 */
data class Patch(
    val composeNanos: Long,
    val serializeNanos: Long,
    val ops: List<Op>,
    val bytes: Int,
) {
    val totalNanos: Long get() = composeNanos + serializeNanos
}

/** The `<tbody>` the rows live in. */
internal fun HtmlOwner.tbody(): ElementNode =
    root.first { it.attribute("id") == "tbody" } ?: error("The page has no <tbody id=\"tbody\">")

/** Cell [cell] of row [index], counting rows and cells as `nth-of-type` does. */
internal fun HtmlOwner.cell(index: Int, cell: Int): ElementNode {
    val rows = tbody().children()
    require(index in rows.indices) { "Asked for row $index of ${rows.size}" }
    return rows[index].children()[cell]
}

/** Child elements, skipping text nodes. */
internal fun ElementNode.children(): List<ElementNode> = childNodes.filterIsInstance<ElementNode>()

/** Depth-first search of this subtree, the element itself included. */
internal fun ElementNode.first(predicate: (ElementNode) -> Boolean): ElementNode? {
    if (predicate(this)) return this
    for (child in childNodes) {
        if (child is ElementNode) child.first(predicate)?.let { return it }
    }
    return null
}

/** Every element in this subtree matching [predicate], in document order. */
internal fun ElementNode.all(predicate: (ElementNode) -> Boolean): List<ElementNode> =
    buildList { collect(this@all, predicate, this) }

private fun collect(node: ElementNode, predicate: (ElementNode) -> Boolean, into: MutableList<ElementNode>) {
    if (predicate(node)) into += node
    for (child in node.childNodes) if (child is ElementNode) collect(child, predicate, into)
}

/** Everything a browser would show for this element, with no markup in between. */
internal fun ElementNode.text(): String = buildString { appendText(this@text) }

private fun StringBuilder.appendText(node: HtmlNode) {
    when (node) {
        is TextNode -> append(node.text)
        is ElementNode -> node.childNodes.forEach { appendText(it) }
    }
}
