package jetlin.html

import jetlin.protocol.EventPayload
import jetlin.protocol.ListenerSpec
import jetlin.protocol.NodeId
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import jetlin.protocol.PropValue
import jetlin.protocol.ROOT_ID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

public typealias EventHandler = (EventPayload) -> Unit

/**
 * A node in the server-side virtual DOM.
 *
 * Nodes only emit protocol ops once they are *attached* — reachable from the root. Compose inserts
 * subtrees bottom-up, so a node's children are populated before the node itself joins the tree;
 * suppressing ops until attachment is what lets an entire new subtree ship as a single
 * [Op.Insert] instead of a create-then-configure-then-parent chatter of dozens of ops.
 */
public sealed class HtmlNode {
    public abstract val id: NodeId
    internal var parent: ElementNode? = null
    internal var attached: Boolean = false

    internal abstract fun toSpec(): NodeSpec
    internal abstract fun attach()
    internal abstract fun detach()
}

public class TextNode internal constructor(
    override val id: NodeId,
    text: String,
    private val owner: HtmlOwner,
) : HtmlNode() {

    public var text: String = text
        internal set(value) {
            if (field == value) return
            field = value
            if (attached) owner.record(Op.SetText(id, value))
        }

    override fun toSpec(): NodeSpec = NodeSpec.Text(id, text)
    override fun attach() { attached = true }
    override fun detach() { attached = false }
}

public class ElementNode internal constructor(
    override val id: NodeId,
    public val tag: String,
    private val owner: HtmlOwner,
) : HtmlNode() {

    internal val children: MutableList<HtmlNode> = mutableListOf()
    internal val attributes: LinkedHashMap<String, String> = LinkedHashMap()
    internal val properties: LinkedHashMap<String, PropValue> = LinkedHashMap()
    internal val listeners: LinkedHashMap<String, ListenerSpec> = LinkedHashMap()

    /**
     * Never serialized. Handlers stay server-side and are looked up by (node, event) when an event
     * arrives, so a handler closure can capture whatever it likes and stays type-checked — there is
     * no Jetlin equivalent of Livewire's `wire:click="methodName"` string indirection.
     */
    internal var handlers: Map<String, EventHandler> = emptyMap()

    /**
     * Reconciles declared state against current state, emitting one op per actual difference.
     *
     * Only called when the [ElementData] value differs structurally from the previous composition,
     * so an unchanged element costs a single equality check and no traversal.
     */
    internal fun applyData(data: ElementData) {
        for ((name, value) in data.attributes) {
            if (attributes.put(name, value) != value && attached) {
                owner.record(Op.SetAttr(id, name, value))
            }
        }
        val goneAttrs = attributes.keys.filter { it !in data.attributes }
        for (name in goneAttrs) {
            attributes.remove(name)
            if (attached) owner.record(Op.SetAttr(id, name, null))
        }

        for ((name, value) in data.properties) {
            if (properties.put(name, value) != value && attached) {
                owner.record(Op.SetProp(id, name, value))
            }
        }
        properties.keys.retainAll(data.properties.keys)

        for ((event, spec) in data.listeners) {
            if (listeners.put(event, spec) != spec && attached) {
                owner.record(Op.Listen(id, event, spec))
            }
        }
        val goneListeners = listeners.keys.filter { it !in data.listeners }
        for (event in goneListeners) {
            listeners.remove(event)
            if (attached) owner.record(Op.Unlisten(id, event))
        }
    }

    internal fun handle(event: String, payload: EventPayload): Boolean {
        val handler = handlers[event] ?: return false
        handler(payload)
        return true
    }

    override fun toSpec(): NodeSpec = NodeSpec.Element(
        id = id,
        tag = tag,
        attrs = LinkedHashMap(attributes),
        props = LinkedHashMap(properties),
        listeners = LinkedHashMap(listeners),
        children = children.map { it.toSpec() },
    )

    override fun attach() {
        attached = true
        owner.register(this)
        children.forEach { it.attach() }
    }

    override fun detach() {
        attached = false
        owner.unregister(this)
        children.forEach { it.detach() }
    }
}

/**
 * The declared shape of an element for one composition pass.
 *
 * Handlers are deliberately excluded: lambdas get fresh identities on every recomposition, so
 * including them would make every element unequal to its previous self and defeat Compose's
 * skipping. Listener *specs* are included, because a change in debounce or extraction really does
 * need to reach the client.
 */
internal data class ElementData(
    val attributes: Map<String, String>,
    val properties: Map<String, PropValue>,
    val listeners: Map<String, ListenerSpec>,
)

/**
 * Per-session ownership: node ids, the node registry used to route events, and the patch buffer.
 *
 * Ids are session-local and monotonic, which keeps them small on the wire and makes protocol traces
 * readable.
 */
public class HtmlOwner {
    private var nextId: NodeId = ROOT_ID + 1
    private val byId: HashMap<NodeId, ElementNode> = HashMap()
    private val ops: MutableList<Op> = mutableListOf()

    /**
     * Signals that ops are waiting. Conflated, because a recomposition pass that records fifty ops
     * should still wake the sender exactly once.
     */
    private val dirty = Channel<Unit>(Channel.CONFLATED)
    internal val dirtySignals: ReceiveChannel<Unit> get() = dirty

    public val root: ElementNode = ElementNode(ROOT_ID, "#root", this).apply { attached = true }

    internal fun allocateId(): NodeId = nextId++
    internal fun createElement(tag: String): ElementNode = ElementNode(allocateId(), tag, this)
    internal fun createText(text: String): TextNode = TextNode(allocateId(), text, this)

    internal fun register(node: ElementNode) { byId[node.id] = node }
    internal fun unregister(node: ElementNode) { byId.remove(node.id) }
    internal fun record(op: Op) {
        ops += op
        dirty.trySend(Unit)
    }

    /** True if at least one op is buffered; used to skip empty frames. */
    public val hasPendingOps: Boolean get() = ops.isNotEmpty()

    /** Takes and clears the buffered ops. One drain per frame becomes one patch message. */
    public fun drainOps(): List<Op> {
        if (ops.isEmpty()) return emptyList()
        val drained = ops.toList()
        ops.clear()
        return drained
    }

    /**
     * Routes an inbound event to its handler. Returns false if the node or handler is gone, which
     * is normal and benign: the user clicked something the server had already removed.
     */
    public fun dispatch(nodeId: NodeId, event: String, payload: EventPayload): Boolean =
        byId[nodeId]?.handle(event, payload) ?: false

    /** The whole current tree, for a full reset after rehydration. */
    public fun snapshotChildren(): List<NodeSpec> = root.children.map { it.toSpec() }
}
