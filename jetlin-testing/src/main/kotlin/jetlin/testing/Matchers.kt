package jetlin.testing

import jetlin.html.ElementNode
import jetlin.html.HtmlNode
import jetlin.html.TextNode
import jetlin.protocol.PropValue

/**
 * A named predicate over a node in the rendered tree.
 *
 * The name is not decoration: when a query matches nothing, or matches more than one node, it is
 * the only thing that tells you what was being looked for. Every combinator below composes the
 * descriptions too, so `hasTag("button") and hasText("Save")` fails with that phrase rather than
 * with a lambda's address.
 */
public class NodeMatcher(
    public val description: String,
    private val predicate: (HtmlNode) -> Boolean,
) {
    public fun matches(node: HtmlNode): Boolean = predicate(node)

    public infix fun and(other: NodeMatcher): NodeMatcher =
        NodeMatcher("(${description} and ${other.description})") { matches(it) && other.matches(it) }

    public infix fun or(other: NodeMatcher): NodeMatcher =
        NodeMatcher("(${description} or ${other.description})") { matches(it) || other.matches(it) }

    public operator fun not(): NodeMatcher =
        NodeMatcher("not ${description}") { !matches(it) }

    override fun toString(): String = description
}

/** Matches an element by tag name, e.g. `hasTag("button")`. */
public fun hasTag(tag: String): NodeMatcher =
    NodeMatcher("tag '$tag'") { it is ElementNode && it.tag.equals(tag, ignoreCase = true) }

/** Matches any element carrying [name], whatever its value. */
public fun hasAttr(name: String): NodeMatcher =
    NodeMatcher("attribute '$name'") { it is ElementNode && it.attribute(name) != null }

/**
 * Matches an element whose [name] attribute equals [value].
 *
 * For attributes the page genuinely has. To address a node from a test, prefer [hasTestTag], which
 * matches a name the view declared for that purpose and which never reaches the browser.
 */
public fun hasAttr(name: String, value: String): NodeMatcher =
    NodeMatcher("attribute '$name'='$value'") { it is ElementNode && it.attribute(name) == value }

/**
 * Matches an element named [value] by `testTag`.
 *
 * The usual way to address a node from a test. The tag lives on the node rather than in the markup,
 * so this matches whether or not the server is configured to write tags out as `data-test`, and the
 * page a user is served carries nothing that exists only for tests.
 */
public fun hasTestTag(value: String): NodeMatcher =
    NodeMatcher("testTag '$value'") { it is ElementNode && it.testTag == value }

/**
 * Matches an element rendered by the client component registered as [name].
 *
 * A headless test can see that the component was asked for, and with what props, but not what it
 * drew — that happens in a browser, and belongs in a browser test.
 */
public fun hasClientComponent(name: String): NodeMatcher =
    NodeMatcher("client component '$name'") {
        it is ElementNode && it.attribute("data-jl-component") == name
    }

/** Matches an element whose `id` attribute equals [value]. */
public fun hasId(value: String): NodeMatcher = hasAttr("id", value)

/**
 * Matches an element carrying [name] among its classes.
 *
 * Aware that `class` holds a list, so this matches `class="todo-text done"` where a plain attribute
 * comparison would not.
 */
public fun hasClass(name: String): NodeMatcher =
    NodeMatcher("class '$name'") { node ->
        node is ElementNode && node.attribute("class")
            ?.split(' ', '\t', '\n')
            ?.contains(name) == true
    }

/**
 * Matches a node by the text it renders.
 *
 * On an element this is the text of the whole subtree, so `hasText("Save")` finds the `<button>`
 * rather than the text node inside it — which is what a test wants, because the button is the
 * thing you click. On a text node it is that node's own text.
 */
public fun hasText(text: String, substring: Boolean = false): NodeMatcher =
    NodeMatcher(if (substring) "text containing '$text'" else "text '$text'") { node ->
        val actual = node.textContent()
        if (substring) actual.contains(text) else actual == text
    }

/**
 * Matches an input whose current value is [value].
 *
 * Reads the DOM *property*, which is where `value(...)` and `bind(...)` write. Comparing the
 * attribute instead would never match, and is the mistake this matcher exists to make impossible.
 */
public fun hasValue(value: String): NodeMatcher =
    NodeMatcher("value '$value'") { it is ElementNode && it.property("value") == PropValue.Str(value) }

/** Matches a checkbox in the given state. Reads the `checked` property, as `checked(...)` writes. */
public fun isChecked(checked: Boolean = true): NodeMatcher =
    NodeMatcher(if (checked) "checked" else "unchecked") {
        it is ElementNode && it.property("checked") == PropValue.Bool(checked)
    }

/**
 * Matches a disabled control.
 *
 * `disabled` is an attribute rather than a property — the asymmetry with [isChecked] is HTML's,
 * and having a matcher for each is how a test author is kept from needing to know about it.
 */
public fun isDisabled(): NodeMatcher =
    NodeMatcher("disabled") { it is ElementNode && it.attribute("disabled") != null }

public fun isEnabled(): NodeMatcher =
    NodeMatcher("enabled") { it is ElementNode && it.attribute("disabled") == null }

/**
 * Matches an element listening for [event].
 *
 * Useful for asserting that something is interactive at all, and used internally so that clicking
 * a node nobody wired up fails loudly instead of doing nothing.
 */
public fun hasListener(event: String): NodeMatcher =
    NodeMatcher("listener for '$event'") { it is ElementNode && event in it.eventNames }

/** Matches every node. Occasionally useful as a starting point for `onAll`. */
public fun anyNode(): NodeMatcher = NodeMatcher("any node") { true }

/** All the text this node renders, concatenated in document order. */
internal fun HtmlNode.textContent(): String = when (this) {
    is TextNode -> text
    is ElementNode -> buildString { collectText(this@textContent, this) }
}

private fun collectText(node: HtmlNode, into: StringBuilder) {
    when (node) {
        is TextNode -> into.append(node.text)
        is ElementNode -> node.childNodes.forEach { collectText(it, into) }
    }
}
