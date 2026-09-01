package jetlin.testing

import jetlin.html.ElementNode
import jetlin.html.HtmlNode
import jetlin.html.HtmlOwner
import jetlin.html.TextNode
import jetlin.protocol.ClientCommand
import jetlin.protocol.PropValue

/**
 * A handle on one node matching a query.
 *
 * Resolved freshly on every operation rather than once when it was created, so a handle taken
 * before an interaction still refers to whatever matches now. That matters because a recomposition
 * can replace the node entirely: asserting against the object found earlier would be asserting
 * against something the composition has already discarded.
 */
public class NodeSelection internal constructor(
    internal val test: ViewTest,
    private val matcher: NodeMatcher,
    private val index: Int? = null,
    /** The subtree this query is confined to, captured from the enclosing [within] block. */
    private val scope: NodeSelection? = null,
) {
    private val description: String =
        if (index == null) matcher.description else "${matcher.description} at index $index"

    /** The matching element, failing if the query does not pick out exactly one. */
    public suspend fun fetch(): ElementNode = test.inspect { owner -> resolveIn(owner) }

    public suspend fun assertExists(): NodeSelection = apply { fetch() }

    public suspend fun assertDoesNotExist(): NodeSelection = apply {
        test.inspect { owner ->
            val found = owner.find(matcher, from = scope?.resolveIn(owner) ?: owner.root)
            if (found.isNotEmpty()) {
                fail("Expected no node matching $description, found ${found.size}.", owner)
            }
        }
    }

    /** All the text this node renders, including the text of everything inside it. */
    public suspend fun text(): String = fetch().textContent()

    /** The input's current value, read from the DOM property that `value` and `bind` write. */
    public suspend fun value(): String? = (fetch().property("value") as? PropValue.Str)?.v

    public suspend fun assertText(expected: String): NodeSelection = apply {
        assertSame("Text of $description", expected, text())
    }

    public suspend fun assertTextContains(expected: String): NodeSelection = apply {
        val actual = text()
        if (!actual.contains(expected)) {
            throw AssertionError("Expected text of $description to contain '$expected', but it was '$actual'.")
        }
    }

    public suspend fun assertValue(expected: String): NodeSelection = apply {
        assertSame("Value of $description", expected, value())
    }

    public suspend fun assertChecked(expected: Boolean = true): NodeSelection = assertMatches(isChecked(expected))

    public suspend fun assertDisabled(): NodeSelection = assertMatches(isDisabled())

    public suspend fun assertEnabled(): NodeSelection = assertMatches(isEnabled())

    /**
     * Asserts exactly which commands the browser will run for [event], and in what order.
     *
     * What a headless test can say about client-only behaviour: that it was declared, and declared
     * correctly. Whether the class actually toggles is a question for a browser.
     */
    public suspend fun assertClientCommands(
        event: String = "click",
        vararg expected: ClientCommand,
    ): NodeSelection = apply {
        val actual = fetch().listenerSpec(event)?.commands.orEmpty()
        assertSame("Client commands for '$event' on $description", expected.toList(), actual)
    }

    /** Asserts that the selected node also satisfies [other]. */
    public suspend fun assertMatches(other: NodeMatcher): NodeSelection = apply {
        val node = fetch()
        if (!other.matches(node)) {
            throw AssertionError(
                "Expected the node matching $description to be ${other.description}, but it was not:\n" +
                    node.describe(),
            )
        }
    }

    /** Runs [block] against the resolved node on the thread that owns the tree. */
    internal suspend fun <T> withNode(block: (ElementNode) -> T): T =
        test.inspect { owner -> block(resolveIn(owner)) }

    /** As [withNode], but also passing the chain of elements from the root down to the node. */
    internal suspend fun <T> withPath(block: (List<ElementNode>) -> T): T =
        test.inspect { owner -> block(owner.pathTo(resolveIn(owner))) }

    internal fun resolveIn(owner: HtmlOwner): ElementNode {
        val found = owner.find(matcher, from = scope?.resolveIn(owner) ?: owner.root)
        if (index != null) {
            return found.getOrNull(index)
                ?: fail(
                    "Expected at least ${index + 1} nodes matching ${matcher.description}, " +
                        "found ${found.size}.",
                    owner,
                )
        }
        return when (found.size) {
            1 -> found.single()
            0 -> fail("Found no node matching $description.", owner)
            else -> fail(
                "Expected one node matching $description, found ${found.size}: " +
                    found.joinToString { "#${it.id} <${it.tag}>" } +
                    ". Narrow the matcher, or use onAll(...) if several are expected.",
                owner,
            )
        }
    }

    internal val describedBy: String get() = description
}

/**
 * Every node matching a query, in document order.
 *
 * Counting is the common case — how many rows the list has — so [assertCount] says what it expected
 * when it fails rather than leaving a bare number comparison behind.
 */
public class NodeCollection internal constructor(
    private val test: ViewTest,
    private val matcher: NodeMatcher,
    private val scope: NodeSelection? = null,
) {
    public suspend fun fetch(): List<ElementNode> = test.inspect { owner -> owner.matches() }

    private fun HtmlOwner.matches(): List<ElementNode> =
        find(matcher, from = scope?.resolveIn(this) ?: root)

    public suspend fun size(): Int = fetch().size

    /** The text of each match, which is usually what a list assertion is really about. */
    public suspend fun texts(): List<String> = fetch().map { it.textContent() }

    public suspend fun assertCount(expected: Int): NodeCollection = apply {
        test.inspect { owner ->
            val nodes = owner.matches()
            if (nodes.size != expected) {
                fail(
                    "Expected $expected nodes matching ${matcher.description}, found ${nodes.size}.",
                    owner,
                )
            }
        }
    }

    public suspend fun assertTexts(vararg expected: String): NodeCollection = apply {
        assertSame("Texts of ${matcher.description}", expected.toList(), texts())
    }

    /**
     * The match at [index], as a handle that can be interacted with.
     *
     * Positional, so it re-resolves against whatever is at that position now. That is right for
     * "the first row" and wrong for following one particular row across a reorder — match on
     * something stable when you mean the latter.
     */
    public operator fun get(index: Int): NodeSelection = NodeSelection(test, matcher, index, scope)

    public fun first(): NodeSelection = get(0)
}

/**
 * Finds every element matching [matcher], keeping the innermost when matches are nested.
 *
 * Dropping a match that contains another is what makes text queries usable: in
 * `Li { Link { Text("Buy milk") } }` both the `<li>` and the `<a>` render exactly that text, and
 * the `<a>` is the one a test means. Interactions bubble from there up to whichever ancestor is
 * actually listening, so selecting the inner node does not stop it being clickable.
 */
internal fun HtmlOwner.find(matcher: NodeMatcher, from: ElementNode = root): List<ElementNode> =
    from.find(matcher)

internal fun ElementNode.find(matcher: NodeMatcher): List<ElementNode> {
    val matches = mutableListOf<ElementNode>()
    collectMatches(this, matcher, matches, includeSelf = false)
    return matches.filter { candidate -> matches.none { it !== candidate && candidate.contains(it) } }
}

private fun collectMatches(
    node: ElementNode,
    matcher: NodeMatcher,
    into: MutableList<ElementNode>,
    includeSelf: Boolean,
) {
    if (includeSelf && matcher.matches(node)) into += node
    for (child in node.childNodes) {
        if (child is ElementNode) collectMatches(child, matcher, into, includeSelf = true)
    }
}

/**
 * The chain of elements from the root down to [node], inclusive.
 *
 * Interactions need it to bubble: the node a matcher picks out is not necessarily the one carrying
 * the handler. Reconstructed by walking down rather than by following parent pointers, which keeps
 * upward navigation out of `jetlin-html`'s public surface.
 */
internal fun HtmlOwner.pathTo(node: ElementNode): List<ElementNode> {
    val path = mutableListOf<ElementNode>()
    fun descend(current: ElementNode): Boolean {
        path += current
        if (current === node) return true
        for (child in current.childNodes) {
            if (child is ElementNode && descend(child)) return true
        }
        path.removeAt(path.size - 1)
        return false
    }
    descend(root)
    return path
}

private fun ElementNode.contains(other: ElementNode): Boolean {
    for (child in childNodes) {
        if (child === other) return true
        if (child is ElementNode && child.contains(other)) return true
    }
    return false
}

/**
 * Reports a mismatch the way a test framework's own assertion would.
 *
 * Written out rather than delegated so that this module needs no test framework of its own, and
 * works unchanged under whichever one the consuming project already runs.
 */
internal fun assertSame(what: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        throw AssertionError("$what\n  expected: <$expected>\n  but was:  <$actual>")
    }
}

private fun fail(message: String, owner: HtmlOwner): Nothing =
    throw AssertionError("$message\n\nThe tree was:\n${owner.root.describe()}")

/** An indented rendering of a node and everything under it, for failure messages. */
internal fun HtmlNode.describe(indent: String = ""): String = when (this) {
    is TextNode -> "$indent\"$text\"  #$id\n"
    is ElementNode -> buildString {
        append(indent).append('<').append(tag)
        // Not an attribute, so it has to be printed explicitly — otherwise the tree dump omits the
        // one thing a failing query was most likely looking for.
        testTag?.let { append(" testTag=").append(it) }
        for (name in attributeNames) append(' ').append(name).append("=\"").append(attribute(name)).append('"')
        for (name in listOf("value", "checked")) {
            when (val prop = property(name)) {
                is PropValue.Str -> append(' ').append(name).append("=[").append(prop.v).append(']')
                is PropValue.Bool -> append(' ').append(name).append("=[").append(prop.v).append(']')
                null -> Unit
            }
        }
        if (eventNames.isNotEmpty()) append(" on=").append(eventNames.joinToString(",", "{", "}"))
        append('>').append("  #").append(id).append('\n')
        for (child in childNodes) append(child.describe("$indent  "))
    }
}
