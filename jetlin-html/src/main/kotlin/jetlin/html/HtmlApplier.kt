package jetlin.html

import androidx.compose.runtime.AbstractApplier
import jetlin.protocol.Op

/**
 * Turns Compose's tree mutations into wire ops.
 *
 * An [AbstractApplier] is how the Compose runtime edits whatever tree a composition describes: as
 * it recomposes, it calls insert / remove / move on the applier. Each of those calls updates the
 * server-side tree and records the equivalent op for the browser, so the two stay in step without
 * either side comparing trees.
 *
 * Insertion is handled bottom-up: Compose finishes building a subtree before parenting it, so an
 * inserted subtree is complete at the moment it becomes visible to the client and ships as one op.
 */
public class HtmlApplier(private val owner: HtmlOwner) : AbstractApplier<HtmlNode>(owner.root) {

    private val currentElement: ElementNode
        get() = current as? ElementNode
            ?: error("Cannot add children to a text node (current node is ${current::class.simpleName})")

    override fun insertTopDown(index: Int, instance: HtmlNode) {
        // Intentionally empty; see insertBottomUp.
    }

    override fun insertBottomUp(index: Int, instance: HtmlNode) {
        val parent = currentElement
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

    override fun onClear() {
        owner.root.children.forEach { it.detach() }
        owner.root.children.clear()
    }
}
