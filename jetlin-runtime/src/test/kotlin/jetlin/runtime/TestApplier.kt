package jetlin.runtime

import androidx.compose.runtime.AbstractApplier

class TestNode(var name: String) {
    val children: MutableList<TestNode> = mutableListOf()
}

/** Minimal applier used to prove the runtime host drives an arbitrary tree. */
class TestApplier(root: TestNode) : AbstractApplier<TestNode>(root) {
    override fun insertTopDown(index: Int, instance: TestNode) {
        // Built bottom-up instead; see HtmlApplier for why that matters on the wire.
    }

    override fun insertBottomUp(index: Int, instance: TestNode) {
        current.children.add(index, instance)
    }

    override fun remove(index: Int, count: Int) {
        current.children.subList(index, index + count).clear()
    }

    override fun move(from: Int, to: Int, count: Int) {
        current.children.move(from, to, count)
    }

    override fun onClear() {
        root.children.clear()
    }
}
