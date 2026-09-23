package jetlin.testing

import jetlin.html.ElementNode
import jetlin.html.HtmlOwner
import jetlin.html.TextNode
import jetlin.protocol.NodeId
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op

/**
 * The nodes that an interaction changed.
 *
 * The ordinary assertions check whether the page ended up right. This class checks whether it got
 * there by changing only what it needed to. If you break `key(todo.id)` in a list, the page still
 * renders identically and passes every assertion about its contents, but it rebuilds every row on
 * every keystroke. Nothing visible catches that, and in a server-driven framework it's the
 * difference between a small patch and sending the whole list.
 *
 * Every assertion throws [AssertionError] and prints the tree when it fails.
 *
 * @property changedNodes the IDs of the elements that changed. A changed text node counts as a
 *   change to the element that contains it.
 */
public class Update internal constructor(
    private val test: ViewTest,
    public val changedNodes: Set<NodeId>,
) {
    /**
     * Asserts that exactly the nodes that [matchers] match changed, and nothing else did.
     *
     * The check is deliberately exact instead of "contains." An update that changes more than it
     * should is what you're looking for, so allowing extras would defeat the purpose.
     *
     * You can't name nodes that the interaction removed. A removal is recorded against the parent,
     * and the node is gone by the time a matcher could find it. Assert on the parent instead.
     */
    public suspend fun assertOnly(vararg matchers: NodeMatcher) {
        val expected = idsOf(matchers.toList())
        if (changedNodes != expected) {
            throw AssertionError(
                buildString {
                    append("The update did not change exactly the expected nodes.\n\n")
                    append("Unexpectedly changed: ").append(describeIds(changedNodes - expected)).append('\n')
                    append("Expected but unchanged: ").append(describeIds(expected - changedNodes)).append('\n')
                    append("\nThe tree was:\n").append(test.debugTree())
                },
            )
        }
    }

    /**
     * Asserts that everything the update changed is inside one of the subtrees that [matchers] match.
     *
     * This is usually what "only that row changed" means, and it's sturdier than [assertOnly]. The
     * test doesn't need to know that checking a checkbox sets both the row's class and the input's
     * `checked` property, only that neither change was outside the row.
     */
    public suspend fun assertOnlyWithin(vararg matchers: NodeMatcher) {
        val allowed = test.inspect { owner ->
            matchers.flatMap { owner.find(it) }.flatMapTo(mutableSetOf()) { subtree ->
                subtree.find(anyNode()).map { it.id } + subtree.id
            }
        }
        val outside = changedNodes - allowed
        if (outside.isNotEmpty()) {
            throw AssertionError(
                "Expected every change to be inside " +
                    matchers.joinToString { it.description } +
                    ", but these were not: " + describeIds(outside) +
                    "\n\nThe tree was:\n" + test.debugTree(),
            )
        }
    }

    /** Asserts that none of the nodes that [matchers] match changed. */
    public suspend fun assertUntouched(vararg matchers: NodeMatcher) {
        val touched = idsOf(matchers.toList()) intersect changedNodes
        if (touched.isNotEmpty()) {
            throw AssertionError(
                "Expected these to be left alone, but the update changed them: " +
                    describeIds(touched) + "\n\nThe tree was:\n" + test.debugTree(),
            )
        }
    }

    /** Asserts that the interaction produced no DOM changes at all. */
    public suspend fun assertNothingChanged() {
        if (changedNodes.isNotEmpty()) {
            throw AssertionError(
                "Expected no changes, but these nodes changed: " + describeIds(changedNodes) +
                    "\n\nThe tree was:\n" + test.debugTree(),
            )
        }
    }

    /** Returns the IDs of every node that any of [matchers] match. */
    private suspend fun idsOf(matchers: List<NodeMatcher>): Set<NodeId> =
        test.inspect { owner -> matchers.flatMapTo(mutableSetOf()) { m -> owner.find(m).map { it.id } } }

    /** Describes each node in [ids] by ID and tag, for failure messages. */
    private suspend fun describeIds(ids: Set<NodeId>): String {
        if (ids.isEmpty()) return "none"
        val byId = test.inspect { owner ->
            owner.find(anyNode()).filter { it.id in ids }.associate { it.id to "<${it.tag}>" }
        }
        return ids.sorted().joinToString { id -> "#$id ${byId[id] ?: "(no longer in the tree)"}" }
    }
}

/**
 * Runs [block] and returns the nodes it changed.
 *
 * Changes that settle after [block] returns, such as from a shared store the test wrote directly,
 * are included.
 *
 * ```kotlin
 * val update = recordUpdate {
 *     within(onAll(hasTestTag("todo"))[0]) { onNode(hasTag("input")).check() }
 * }
 * update.assertOnlyWithin(hasTestTag("todo"), hasTestTag("remaining"))
 * ```
 *
 * @throws IllegalStateException if called inside another `recordUpdate` block.
 */
public suspend fun ViewTest.recordUpdate(block: suspend () -> Unit): Update {
    val (_, ops) = recordingOps(block)
    val raw = ops.flatMapTo(mutableSetOf()) { it.touchedNodes() }
    // The protocol addresses text nodes, but matchers only find elements. Reporting a text node
    // would make the assertion impossible to satisfy, so a changed text node counts as a change to
    // the element that contains it. That's what "this part of the page changed" means to whoever
    // reads the test.
    val enclosing = inspect { owner -> owner.enclosingElements() }
    return Update(this, raw.mapTo(mutableSetOf()) { enclosing[it] ?: it })
}

/** Maps every node ID in the tree to the element that contains it. An element maps to itself. */
private fun HtmlOwner.enclosingElements(): Map<NodeId, NodeId> {
    val map = mutableMapOf<NodeId, NodeId>()
    fun walk(element: ElementNode) {
        map[element.id] = element.id
        for (child in element.childNodes) {
            when (child) {
                is TextNode -> map[child.id] = element.id
                is ElementNode -> walk(child)
            }
        }
    }
    walk(root)
    return map
}

/**
 * Returns the nodes that this op changed.
 *
 * Structural ops name the parent instead of the node that moved or was removed, which is right
 * here: a row appearing, disappearing, or moving is a change to the list that holds it. An insert
 * also names the nodes that arrived, so a test can assert on a new row directly.
 */
private fun Op.touchedNodes(): List<NodeId> = when (this) {
    is Op.SetText -> listOf(id)
    is Op.SetAttr -> listOf(id)
    is Op.SetProp -> listOf(id)
    is Op.Listen -> listOf(id)
    is Op.Unlisten -> listOf(id)
    is Op.Insert -> listOf(parent) + node.ids()
    is Op.Remove -> listOf(parent)
    is Op.Move -> listOf(parent)
}

/** Returns the IDs of this node and every node in its subtree. */
private fun NodeSpec.ids(): List<NodeId> = when (this) {
    is NodeSpec.Text -> listOf(id)
    is NodeSpec.Element -> listOf(id) + children.flatMap { it.ids() }
}

/**
 * Asserts that none of [values] appear anywhere in the rendered page.
 *
 * Use this in multi-user applications to check that a page shows nothing that belongs to another
 * principal. Pass values from the other principal's records, such as titles, names, or anything they
 * wrote. The assertion fails if any of them appear in the markup.
 *
 * The check runs against the rendered HTML instead of the node tree. Data can leak through an
 * attribute or a property as well as through text, and the HTML is what reaches the browser.
 *
 * The rendered HTML doesn't include `<head>`, so check the document title separately with
 * [ViewTest.title].
 *
 * ```kotlin
 * setAttribute(PrincipalKey, bob)
 * setRoutes(appRoutes)
 *
 * assertNotDisclosed("Alice's private note", "Carol's private note")
 * ```
 */
public suspend fun ViewTest.assertNotDisclosed(vararg values: String) {
    val markup = renderHtml()
    val found = values.filter { it.isNotEmpty() && it in markup }
    if (found.isNotEmpty()) {
        throw AssertionError(
            "The page discloses data this principal should not see: " +
                found.joinToString { "\"$it\"" } +
                "\n\nThe tree was:\n" + debugTree(),
        )
    }
}
