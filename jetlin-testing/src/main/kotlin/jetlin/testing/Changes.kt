package jetlin.testing

import jetlin.html.ElementNode
import jetlin.html.HtmlOwner
import jetlin.html.TextNode
import jetlin.protocol.NodeId
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op

/**
 * Which nodes an interaction actually changed.
 *
 * The question this answers is not "did the page end up right" — the ordinary assertions cover that
 * — but "did it get there by touching what it needed to". Break `key(todo.id)` in a list and the
 * page still renders identically and still passes every assertion about its contents; it just
 * rebuilds every row on every keystroke. Nothing visible catches that, and on a server-driven
 * framework it is the difference between a small patch and the whole list crossing the wire.
 */
public class Update internal constructor(
    private val test: ViewTest,
    public val changedNodes: Set<NodeId>,
) {
    /**
     * Asserts that exactly the nodes matched by [matchers] changed, and nothing else did.
     *
     * Deliberately exact rather than "contains": an update that touches more than it should is the
     * thing being looked for, so allowing extras would defeat the purpose.
     *
     * Nodes the interaction *removed* cannot be named here — a removal is recorded against the
     * parent, and the node itself is gone by the time a matcher could resolve it. Assert on the
     * parent in that case.
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

    /** Asserts that none of the nodes matched by [matchers] were touched. */
    public suspend fun assertUntouched(vararg matchers: NodeMatcher) {
        val touched = idsOf(matchers.toList()) intersect changedNodes
        if (touched.isNotEmpty()) {
            throw AssertionError(
                "Expected these to be left alone, but the update changed them: " +
                    describeIds(touched) + "\n\nThe tree was:\n" + test.debugTree(),
            )
        }
    }

    /** Asserts the interaction produced no DOM changes at all. */
    public suspend fun assertNothingChanged() {
        if (changedNodes.isNotEmpty()) {
            throw AssertionError(
                "Expected no changes, but these nodes changed: " + describeIds(changedNodes) +
                    "\n\nThe tree was:\n" + test.debugTree(),
            )
        }
    }

    private suspend fun idsOf(matchers: List<NodeMatcher>): Set<NodeId> =
        test.inspect { owner -> matchers.flatMapTo(mutableSetOf()) { m -> owner.find(m).map { it.id } } }

    private suspend fun describeIds(ids: Set<NodeId>): String {
        if (ids.isEmpty()) return "none"
        val byId = test.inspect { owner ->
            owner.find(anyNode()).filter { it.id in ids }.associate { it.id to "<${it.tag}>" }
        }
        return ids.sorted().joinToString { id -> "#$id ${byId[id] ?: "(no longer in the tree)"}" }
    }
}

/**
 * Runs [block] and reports which nodes it changed.
 *
 * ```kotlin
 * val update = recordUpdate { onAll(hasAttr("data-test", "todo"))[0].check() }
 * update.assertOnly(hasClass("todo-text") and hasText("Buy milk"), hasTag("input") and isChecked())
 * update.assertUntouched(hasTag("ul"))
 * ```
 */
public suspend fun ViewTest.recordUpdate(block: suspend () -> Unit): Update {
    val (_, ops) = recordingOps(block)
    val raw = ops.flatMapTo(mutableSetOf()) { it.touchedNodes() }
    // Text nodes are addressed by the protocol but never by a matcher, which only ever resolves to
    // elements. Reporting one would make the assertion unsatisfiable, so a changed text node counts
    // as a change to the element holding it — which is what "this part of the page moved" means to
    // whoever is reading the test.
    val enclosing = inspect { owner -> owner.enclosingElements() }
    return Update(this, raw.mapTo(mutableSetOf()) { enclosing[it] ?: it })
}

/** Maps every node id in the tree to the element that holds it; an element maps to itself. */
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
 * The nodes an op is evidence of having changed.
 *
 * Structural ops name the parent rather than the node moved or removed, which is exactly right for
 * this purpose: a row appearing, vanishing or shifting *is* a change to the list holding it. An
 * insert additionally names what arrived, so that a new row can be asserted on directly.
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

private fun NodeSpec.ids(): List<NodeId> = when (this) {
    is NodeSpec.Text -> listOf(id)
    is NodeSpec.Element -> listOf(id) + children.flatMap { it.ids() }
}
