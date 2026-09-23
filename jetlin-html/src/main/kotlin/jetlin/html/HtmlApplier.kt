package jetlin.html

import androidx.compose.runtime.AbstractApplier
import jetlin.protocol.Op

/**
 * Turns Compose's changes to the tree into ops for the browser.
 *
 * The Compose runtime edits a composition's tree through an [AbstractApplier]. As it recomposes, it
 * calls insert, remove, and move on the applier. Each call updates the server-side tree and records
 * the matching op for the browser, so the two trees stay in step without either side comparing them.
 *
 * Nodes are inserted bottom-up. Compose finishes building a subtree before it adds the subtree to
 * its parent, so the subtree is complete when the client first sees it, and it goes out as one op.
 *
 * @param owner the session's tree. The applier records its ops there.
 */
public class HtmlApplier(private val owner: HtmlOwner) : AbstractApplier<HtmlNode>(owner.root) {

    private val currentElement: ElementNode
        get() = current as? ElementNode
            ?: error("Cannot add children to a text node (current node is ${current::class.simpleName})")

    override fun insertTopDown(index: Int, instance: HtmlNode) {
        // Deliberately empty. See insertBottomUp.
    }

    override fun insertBottomUp(index: Int, instance: HtmlNode) {
        val parent = currentElement
        check(!parent.hasUnsafeInnerHtml) {
            "<${parent.tag}> uses unsafeInnerHtml and cannot also have composable children: " +
                "the raw HTML and the child nodes would overwrite each other."
        }
        parent.children.add(index, instance)
        instance.parent = parent
        if (parent.attached) {
            instance.attach()
            owner.record(Op.Insert(parent.id, index, instance.toSpec()))
        }
    }

    override fun remove(index: Int, count: Int) {
        val parent = currentElement
        val removed = parent.children.subList(index, index + count)
        if (parent.attached) {
            removed.forEach { it.detach() }
            owner.record(Op.Remove(parent.id, index, count))
        }
        removed.clear()
    }

    override fun move(from: Int, to: Int, count: Int) {
        val parent = currentElement
        parent.children.move(from, to, count)
        if (parent.attached) {
            owner.record(Op.Move(parent.id, from, to, count))
        }
    }

    /** Removes every node from the root. */
    override fun onClear() {
        owner.root.children.forEach { it.detach() }
        owner.root.children.clear()
    }
}
