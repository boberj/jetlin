package jetlin.testing

import jetlin.html.ElementNode
import jetlin.html.HtmlNode
import jetlin.html.HtmlOwner
import jetlin.html.TextNode
import jetlin.protocol.COMPONENT_EVENT
import jetlin.protocol.COMPONENT_EVENT_NAME
import jetlin.protocol.COMPONENT_EVENT_PAYLOAD
import jetlin.protocol.ClientCommand
import jetlin.protocol.EventPayload
import jetlin.protocol.JetlinJson
import jetlin.protocol.PropValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A selection of the one node that matches a query.
 *
 * The query runs again on every operation, not once when the selection is created, so a selection
 * made before an interaction still refers to whatever matches now. That matters because a
 * recomposition can replace the node entirely, and asserting on the node found earlier would mean
 * asserting on something the composition has already discarded.
 *
 * Every assertion throws [AssertionError] when it fails, and returns this selection when it passes,
 * so you can chain them.
 */
public class NodeSelection internal constructor(
    internal val test: ViewTest,
    private val matcher: NodeMatcher,
    private val index: Int? = null,
    /** The subtree that this query is confined to, taken from the enclosing [ViewTest.within] block. */
    private val scope: NodeSelection? = null,
) {
    private val description: String =
        if (index == null) matcher.description else "${matcher.description} at index $index"

    /**
     * Returns the matching element.
     *
     * @throws AssertionError if the query doesn't match exactly one element.
     */
    public suspend fun fetch(): ElementNode = test.inspect { owner -> resolveIn(owner) }

    /** Asserts that exactly one node matches. */
    public suspend fun assertExists(): NodeSelection = apply { fetch() }

    /** Asserts that no node matches. */
    public suspend fun assertDoesNotExist(): NodeSelection = apply {
        test.inspect { owner ->
            val found = owner.find(matcher, from = scope?.resolveIn(owner) ?: owner.root)
            if (found.isNotEmpty()) {
                fail("Expected no node matching $description, found ${found.size}.", owner)
            }
        }
    }

    /** Returns all the text this node renders, including the text of everything inside it. */
    public suspend fun text(): String = fetch().textContent()

    /** Returns the input's current value, from the DOM property that `value` and `bind` set. */
    public suspend fun value(): String? = (fetch().property("value") as? PropValue.Str)?.v

    /** Asserts that the node's [text] is exactly [expected]. */
    public suspend fun assertText(expected: String): NodeSelection = apply {
        assertSame("Text of $description", expected, text())
    }

    /** Asserts that the node's [text] contains [expected]. */
    public suspend fun assertTextContains(expected: String): NodeSelection = apply {
        val actual = text()
        if (!actual.contains(expected)) {
            throw AssertionError("Expected text of $description to contain '$expected', but it was '$actual'.")
        }
    }

    /** Asserts that the input's [value] is exactly [expected]. */
    public suspend fun assertValue(expected: String): NodeSelection = apply {
        assertSame("Value of $description", expected, value())
    }

    /** Asserts that the checkbox or radio button is checked, or cleared if [expected] is `false`. */
    public suspend fun assertChecked(expected: Boolean = true): NodeSelection = assertMatches(isChecked(expected))

    /** Asserts that the node has the `disabled` attribute. */
    public suspend fun assertDisabled(): NodeSelection = assertMatches(isDisabled())

    /** Asserts that the node doesn't have the `disabled` attribute. */
    public suspend fun assertEnabled(): NodeSelection = assertMatches(isEnabled())

    /**
     * Asserts exactly which commands the browser runs for [event], and in what order.
     *
     * That's what a headless test can check about client-only behavior: that it was declared, and
     * declared correctly. Whether the class really toggles is a question for a browser test.
     */
    public suspend fun assertClientCommands(
        event: String = "click",
        vararg expected: ClientCommand,
    ): NodeSelection = apply {
        val actual = fetch().listenerSpec(event)?.commands.orEmpty()
        assertSame("Client commands for '$event' on $description", expected.toList(), actual)
    }

    /**
     * Returns the props the server last sent to this client component.
     *
     * @throws AssertionError if the node isn't a client component.
     */
    public suspend fun props(): JsonObject {
        val raw = fetch().attribute("data-jl-props")
            ?: throw AssertionError("The node matching $description is not a client component.")
        return JetlinJson.parseToJsonElement(raw).jsonObject
    }

    /** Asserts that the client component's [props] are exactly [expected]. */
    public suspend fun assertProps(expected: JsonObject): NodeSelection = apply {
        assertSame("Props of $description", expected, props())
    }

    /**
     * Sends an event from a client component to the server, as its browser implementation would.
     *
     * This is the half of a component that a headless test can drive. It can't check what the
     * component renders, but it can check that the server does the right thing with what the
     * component reports.
     *
     * @throws AssertionError if the node isn't a client component.
     */
    public suspend fun pushFromClient(
        event: String,
        payload: JsonObject = JsonObject(emptyMap()),
    ): NodeSelection = apply {
        val node = fetch()
        if (node.attribute("data-jl-component") == null) {
            throw AssertionError(
                "The node matching $description is not a client component, so nothing can be " +
                    "pushed from it.\n\nThe node was:\n" + node.describe(),
            )
        }
        test.dispatchEvent(
            node.id,
            COMPONENT_EVENT,
            EventPayload(
                data = buildJsonObject {
                    put(COMPONENT_EVENT_NAME, event)
                    put(COMPONENT_EVENT_PAYLOAD, payload)
                },
            ),
        )
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

    /** Like [withNode], but passes the chain of elements from the root down to the node. */
    internal suspend fun <T> withPath(block: (List<ElementNode>) -> T): T =
        test.inspect { owner -> block(owner.pathTo(resolveIn(owner))) }

    /** Finds the node in [owner]'s tree, or fails with the tree printed. */
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

    /** Describes the query, for failure messages. */
    internal val describedBy: String get() = description
}

/**
 * A selection of every node that matches a query, in document order.
 *
 * Counting is the common case, such as how many rows a list has, so [assertCount] says what it
 * expected when it fails, instead of leaving a bare comparison of numbers.
 */
public class NodeCollection internal constructor(
    private val test: ViewTest,
    private val matcher: NodeMatcher,
    private val scope: NodeSelection? = null,
) {
    /** Returns the matching elements. */
    public suspend fun fetch(): List<ElementNode> = test.inspect { owner -> owner.matches() }

    /** Finds the matching elements in this tree. */
    private fun HtmlOwner.matches(): List<ElementNode> =
        find(matcher, from = scope?.resolveIn(this) ?: root)

    /** Returns the number of matching elements. */
    public suspend fun size(): Int = fetch().size

    /** Returns the text of each match, which is usually what a list assertion is about. */
    public suspend fun texts(): List<String> = fetch().map { it.textContent() }

    /** Asserts that exactly [expected] nodes match, and prints the tree if not. */
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

    /** Asserts that the matches' [texts] are exactly [expected], in order. */
    public suspend fun assertTexts(vararg expected: String): NodeCollection = apply {
        assertSame("Texts of ${matcher.description}", expected.toList(), texts())
    }

    /**
     * Returns the match at [index], as a selection you can interact with.
     *
     * The selection is positional, so it resolves to whatever is at that position now. That's right
     * for "the first row," and wrong for following one particular row across a reorder. To follow a
     * row, match on something stable.
     */
    public operator fun get(index: Int): NodeSelection = NodeSelection(test, matcher, index, scope)

    /** Returns the first match. See [get]. */
    public fun first(): NodeSelection = get(0)
}

/**
 * Finds every element under [from] that matches [matcher], keeping only the innermost when
 * matches are nested.
 *
 * Dropping a match that contains another match is what makes text queries usable. In
 * `Li { Link { Text("Buy milk") } }`, both the `<li>` and the `<a>` render exactly that text, and
 * the test means the `<a>`. Interactions bubble up from there to whichever ancestor is listening, so
 * selecting the inner node doesn't stop it from being clickable.
 */
internal fun HtmlOwner.find(matcher: NodeMatcher, from: ElementNode = root): List<ElementNode> =
    from.find(matcher)

/** Finds every descendant of this element that matches [matcher], keeping only the innermost. */
internal fun ElementNode.find(matcher: NodeMatcher): List<ElementNode> {
    val matches = mutableListOf<ElementNode>()
    collectMatches(this, matcher, matches, includeSelf = false)
    return matches.filter { candidate -> matches.none { it !== candidate && candidate.contains(it) } }
}

/** Adds every element in [node]'s subtree that matches [matcher] to [into], in document order. */
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
 * Returns the chain of elements from the root down to [node], inclusive.
 *
 * Interactions need it to bubble, because the node a matcher selects isn't necessarily the one with
 * the handler. It's found by walking down from the root instead of following parent pointers, which
 * keeps upward navigation out of `jetlin-html`'s public API.
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

/** Whether [other] is a descendant of this element. */
private fun ElementNode.contains(other: ElementNode): Boolean {
    for (child in childNodes) {
        if (child === other) return true
        if (child is ElementNode && child.contains(other)) return true
    }
    return false
}

/**
 * Throws an [AssertionError] if [expected] and [actual] differ, like a test framework's assertion.
 *
 * It's written out instead of calling a test framework, so this module needs no test framework of
 * its own and works with whichever one the project already uses.
 */
internal fun assertSame(what: String, expected: Any?, actual: Any?) {
    if (expected != actual) {
        throw AssertionError("$what\n  expected: <$expected>\n  but was:  <$actual>")
    }
}

/** Throws an [AssertionError] with [message] and the whole tree. */
private fun fail(message: String, owner: HtmlOwner): Nothing =
    throw AssertionError("$message\n\nThe tree was:\n${owner.root.describe()}")

/** Returns this node and everything under it as indented text, for failure messages. */
internal fun HtmlNode.describe(indent: String = ""): String = when (this) {
    is TextNode -> "$indent\"$text\"  #$id\n"
    is ElementNode -> buildString {
        append(indent).append('<').append(tag)
        // The test tag isn't an attribute, so print it explicitly. Otherwise the tree dump would
        // leave out the thing a failing query was most likely looking for.
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
