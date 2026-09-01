package jetlin.html

import java.util.Collections
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

/** DOM property carrying unescaped markup; see [jetlin.html.AttrsScope.unsafeInnerHtml]. */
internal const val INNER_HTML: String = "innerHTML"

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
     * Never serialized. When an event arrives from the client it carries only this node's id and
     * the event name, and the matching lambda is found here, so handlers can be ordinary closures
     * over whatever they need.
     */
    internal var handlers: Map<String, EventHandler> = emptyMap()

    internal val hasUnsafeInnerHtml: Boolean get() = properties.containsKey(INNER_HTML)

    /**
     * The name this element was given for tests; see [AttrsScope.testTag].
     *
     * Held here rather than among the attributes so that it never reaches the browser, and read by
     * whatever is inspecting the tree rather than by the page.
     */
    public var testTag: String? = null
        internal set

    /**
     * Read-only views of this node, for code that inspects the tree rather than building it.
     *
     * A server-side virtual DOM is worth being able to look at: a test asserting on what a view
     * rendered, a debug endpoint, an alternative serializer. Reading is all that is offered, and
     * these are unmodifiable views rather than the collections themselves: the tree is owned by the
     * composition, and the only thing allowed to change it is the applier.
     */
    public val childNodes: List<HtmlNode> get() = Collections.unmodifiableList(children)

    /** Value of an HTML attribute, or null when the element does not carry it. */
    public fun attribute(name: String): String? = attributes[name]

    public val attributeNames: Set<String> get() = Collections.unmodifiableSet(attributes.keys)

    /**
     * Value of a DOM property, or null when unset.
     *
     * Distinct from [attribute] and not interchangeable with it: `value` and `checked` are written
     * as properties, because setting the attribute only changes a control's default and stops
     * having any effect once the user has touched it.
     */
    public fun property(name: String): PropValue? = properties[name]

    /** Events this element is listening for. Handlers themselves stay private to the composition. */
    public val eventNames: Set<String> get() = Collections.unmodifiableSet(listeners.keys)

    /**
     * What the element declared for [event]: what to extract, how to debounce, what the browser does
     * for itself, and whether the server hears about it at all.
     */
    public fun listenerSpec(event: String): ListenerSpec? = listeners[event]

    /**
     * Reconciles declared state against current state, emitting one op per actual difference.
     *
     * Only called when the [ElementData] value differs structurally from the previous composition,
     * so an unchanged element costs a single equality check and no traversal.
     */
    internal fun applyData(data: ElementData) {
        // No op is recorded: the client has no use for a test tag, which is the whole point of
        // keeping it off the attributes. When tags are exposed it is in data.attributes as well,
        // and gets patched from there like anything else.
        testTag = data.testTag

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
 * Compared against the previous pass to decide whether anything needs to be sent. Handlers are
 * deliberately excluded: lambdas get fresh identities on every recomposition, so including them
 * would make every element unequal to its previous self and cause needless work. Listener *specs*
 * are included, because a change in debounce or extraction really does need to reach the client.
 */
internal data class ElementData(
    val attributes: Map<String, String>,
    val properties: Map<String, PropValue>,
    val listeners: Map<String, ListenerSpec>,
    val testTag: String? = null,
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

    /**
     * Whether edits are worth writing down.
     *
     * With no client attached they are not: the composition keeps running — a timer keeps ticking,
     * a shared store keeps changing — but whoever connects next is sent the whole tree anyway, so
     * recording those edits would grow memory to describe a page nobody will ever be shown.
     */
    private var recording = true

    private var overflowed = false

    /**
     * Ceiling on buffered edits before falling back to resending the tree.
     *
     * A client that stops reading, or reads far slower than the session produces updates, would
     * otherwise pin unbounded memory. Past this point the buffer is dropped and the next message is
     * a full snapshot: the session degrades to coarser updates rather than to an outage.
     */
    public var maxBufferedOps: Int = 10_000

    internal fun record(op: Op) {
        if (!recording || overflowed) return
        if (ops.size >= maxBufferedOps) {
            ops.clear()
            overflowed = true
            dirty.trySend(Unit)
            return
        }
        ops += op
        dirty.trySend(Unit)
    }

    /** True once the buffer was dropped; the next message must be a full tree, not a patch. */
    internal val hasOverflowed: Boolean get() = overflowed

    /** Starts recording edits again from a known-good baseline. */
    internal fun startRecording() {
        recording = true
        overflowed = false
        ops.clear()
    }

    /** Stops recording and discards what is buffered. Called when the last client goes away. */
    internal fun stopRecording() {
        recording = false
        overflowed = false
        ops.clear()
    }

    /**
     * Wakes the sender when something needs transmitting that is not an op.
     *
     * A navigation to a route that happens to render identically produces no tree edits, and would
     * otherwise leave the browser's address bar stale because nothing signalled the sender.
     */
    internal fun signalDirty() {
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
