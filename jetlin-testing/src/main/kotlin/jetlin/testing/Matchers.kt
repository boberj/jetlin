package jetlin.testing

import jetlin.html.ElementNode
import jetlin.html.HtmlNode
import jetlin.html.TextNode
import jetlin.protocol.PropValue

/**
 * A named predicate over a node in the rendered tree.
 *
 * The name matters. When a query matches nothing, or more than one node, the name is the only thing
 * that tells you what the test was looking for. Every combinator below combines the descriptions
 * too, so `hasTag("button") and hasText("Save")` fails with that phrase instead of a lambda's
 * address.
 *
 * @property description what the matcher looks for, in words, for failure messages.
 * @param predicate returns whether a node matches.
 */
public class NodeMatcher(
    public val description: String,
    private val predicate: (HtmlNode) -> Boolean,
) {
    /** Returns whether [node] matches. */
    public fun matches(node: HtmlNode): Boolean = predicate(node)

    /** Returns a matcher for nodes that match both this matcher and [other]. */
    public infix fun and(other: NodeMatcher): NodeMatcher =
        NodeMatcher("(${description} and ${other.description})") { matches(it) && other.matches(it) }

    /** Returns a matcher for nodes that match this matcher, [other], or both. */
    public infix fun or(other: NodeMatcher): NodeMatcher =
        NodeMatcher("(${description} or ${other.description})") { matches(it) || other.matches(it) }

    /** Returns a matcher for nodes that don't match this matcher. */
    public operator fun not(): NodeMatcher =
        NodeMatcher("not ${description}") { !matches(it) }

    override fun toString(): String = description
}

/** Matches an element by tag name, ignoring case, such as `hasTag("button")`. */
public fun hasTag(tag: String): NodeMatcher =
    NodeMatcher("tag '$tag'") { it is ElementNode && it.tag.equals(tag, ignoreCase = true) }

/** Matches an element that has the attribute [name], whatever its value. */
public fun hasAttr(name: String): NodeMatcher =
    NodeMatcher("attribute '$name'") { it is ElementNode && it.attribute(name) != null }

/**
 * Matches an element whose [name] attribute equals [value].
 *
 * Use it for attributes that the page really has. To find a node from a test, prefer [hasTestTag],
 * which matches a name the view declared for that purpose and which never reaches the browser.
 */
public fun hasAttr(name: String, value: String): NodeMatcher =
    NodeMatcher("attribute '$name'='$value'") { it is ElementNode && it.attribute(name) == value }

/**
 * Matches an element whose `testTag` is [value].
 *
 * This is the usual way to find a node from a test. The tag is stored on the node instead of in the
 * markup, so this matcher works whether or not the server writes tags as `data-test` attributes,
 * and the page a user receives has nothing in it that exists only for tests.
 */
public fun hasTestTag(value: String): NodeMatcher =
    NodeMatcher("testTag '$value'") { it is ElementNode && it.testTag == value }

/**
 * Matches an element rendered by the client component registered as [name].
 *
 * A headless test can see that the component was requested, and with which props, but not what it
 * drew. Drawing happens in a browser, so check it in a browser test.
 */
public fun hasClientComponent(name: String): NodeMatcher =
    NodeMatcher("client component '$name'") {
        it is ElementNode && it.attribute("data-jl-component") == name
    }

/** Matches an element whose `id` attribute equals [value]. */
public fun hasId(value: String): NodeMatcher = hasAttr("id", value)

/**
 * Matches an element that has the CSS class [name].
 *
 * `class` holds a list, so this matches `class="todo-text done"`, where a plain attribute
 * comparison wouldn't.
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
 * On an element, the text is that of the whole subtree, so `hasText("Save")` finds the `<button>`
 * instead of the text node inside it. That's what a test wants, because the button is what you
 * click. On a text node, the text is the node's own text.
 *
 * @param substring whether to match text that contains [text], instead of text that equals it.
 */
public fun hasText(text: String, substring: Boolean = false): NodeMatcher =
    NodeMatcher(if (substring) "text containing '$text'" else "text '$text'") { node ->
        val actual = node.textContent()
        if (substring) actual.contains(text) else actual == text
    }

/**
 * Matches an input whose current value is [value].
 *
 * It reads the DOM property, which is what `value(...)` and `bind(...)` set. Comparing the
 * attribute instead would never match, and this matcher exists to prevent that mistake.
 */
public fun hasValue(value: String): NodeMatcher =
    NodeMatcher("value '$value'") { it is ElementNode && it.property("value") == PropValue.Str(value) }

/**
 * Matches a checkbox or radio button that's checked, or cleared if [checked] is `false`.
 *
 * It reads the `checked` property, which is what `checked(...)` sets.
 */
public fun isChecked(checked: Boolean = true): NodeMatcher =
    NodeMatcher(if (checked) "checked" else "unchecked") {
        it is ElementNode && it.property("checked") == PropValue.Bool(checked)
    }

/**
 * Matches a disabled control.
 *
 * `disabled` is an attribute, not a property. That difference from [isChecked] comes from HTML,
 * and having a matcher for each means test authors don't need to know about it.
 */
public fun isDisabled(): NodeMatcher =
    NodeMatcher("disabled") { it is ElementNode && it.attribute("disabled") != null }

/** Matches an element that doesn't have the `disabled` attribute. */
public fun isEnabled(): NodeMatcher =
    NodeMatcher("enabled") { it is ElementNode && it.attribute("disabled") == null }

/**
 * Matches an element listening for [event].
 *
 * Use it to assert that something is interactive at all. Interactions use it too, so clicking a
 * node that has no handler fails with an error instead of doing nothing.
 */
public fun hasListener(event: String): NodeMatcher =
    NodeMatcher("listener for '$event'") { it is ElementNode && event in it.eventNames }

/** Matches every node. It's occasionally useful as a starting point for `onAll`. */
public fun anyNode(): NodeMatcher = NodeMatcher("any node") { true }

/** Returns all the text this node renders, concatenated in document order. */
internal fun HtmlNode.textContent(): String = when (this) {
    is TextNode -> text
    is ElementNode -> buildString { collectText(this@textContent, this) }
}

/** Appends the text of [node]'s subtree to [into]. */
private fun collectText(node: HtmlNode, into: StringBuilder) {
    when (node) {
        is TextNode -> into.append(node.text)
        is ElementNode -> node.childNodes.forEach { collectText(it, into) }
    }
}
