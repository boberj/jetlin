package jetlin.html

import java.util.Collections
import jetlin.protocol.EventPayload
import jetlin.protocol.ListenerSpec
import jetlin.protocol.Namespace
import jetlin.protocol.NodeId
import jetlin.protocol.NodeSpec
import jetlin.protocol.Op
import jetlin.protocol.PropValue
import jetlin.protocol.ROOT_ID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/** A server-side handler for a DOM event. It receives what the client read from the event. */
public typealias EventHandler = (EventPayload) -> Unit

/** The DOM property that holds unescaped markup. See [AttrsScope.unsafeInnerHtml]. */
internal const val INNER_HTML: String = "innerHTML"

/**
 * A node in the server-side virtual DOM.
 *
 * A node records protocol ops only once it's attached, meaning reachable from the root. Compose
 * inserts subtrees bottom-up, so a node's children exist before the node joins the tree. Holding
 * back ops until then lets a whole new subtree go out as one [Op.Insert], instead of dozens of ops
 * that create, configure, and parent each node.
 */
public sealed class HtmlNode {
    /** The node's ID, unique within its session. */
    public abstract val id: NodeId
    internal var parent: ElementNode? = null

    /** Whether the node is reachable from the root, and so records ops when it changes. */
    internal var attached: Boolean = false

    /** Returns the wire form of this node and its subtree. */
    internal abstract fun toSpec(): NodeSpec

    /** Marks this node and its subtree as reachable from the root. */
    internal abstract fun attach()

    /** Marks this node and its subtree as no longer reachable from the root. */
    internal abstract fun detach()
}

/** A text node in the server-side virtual DOM. */
public class TextNode internal constructor(
    override val id: NodeId,
    text: String,
    private val owner: HtmlOwner,
) : HtmlNode() {

    /** The node's text. The `Text` composable sets it. */
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

/** An element in the server-side virtual DOM. */
public class ElementNode internal constructor(
    override val id: NodeId,
    /** The element's tag name. */
    public val tag: String,
    /**
     * The element's namespace, which never changes.
     *
     * An element can't change language without being recreated, and that's what happens: where the
     * composable sits in the tree decides the namespace.
     */
    public val namespace: Namespace,
    private val owner: HtmlOwner,
) : HtmlNode() {

    internal val children: MutableList<HtmlNode> = mutableListOf()
    internal val attributes: LinkedHashMap<String, String> = LinkedHashMap()
    internal val properties: LinkedHashMap<String, PropValue> = LinkedHashMap()
    internal val listeners: LinkedHashMap<String, ListenerSpec> = LinkedHashMap()

    /**
     * The element's event handlers, by event name. They're never serialized.
     *
     * An event from the client carries only this node's ID and the event name, and the server finds
     * the matching lambda here. That lets handlers be ordinary closures over whatever they need.
     */
    internal var handlers: Map<String, EventHandler> = emptyMap()

    /** Whether the element's content is raw markup set with [AttrsScope.unsafeInnerHtml]. */
    internal val hasUnsafeInnerHtml: Boolean get() = properties.containsKey(INNER_HTML)

    /**
     * The name this element was given for tests. See [AttrsScope.testTag].
     *
     * It's stored here instead of with the attributes so that it never reaches the browser. Code
     * that inspects the tree reads it. The page doesn't.
     */
    public var testTag: String? = null
        internal set

    /**
     * The element's children, as a read-only view.
     *
     * This property and the others below let code inspect the tree without building it, for
     * example a test that asserts on what a view rendered, a debug endpoint, or another serializer.
     * They return unmodifiable views instead of the collections themselves. The composition owns the
     * tree, and only the applier may change it.
     */
    public val childNodes: List<HtmlNode> get() = Collections.unmodifiableList(children)

    /** Returns the value of the HTML attribute [name], or `null` if the element doesn't have it. */
    public fun attribute(name: String): String? = attributes[name]

    /** The names of the element's HTML attributes. */
    public val attributeNames: Set<String> get() = Collections.unmodifiableSet(attributes.keys)

    /**
     * Returns the value of the DOM property [name], or `null` if it isn't set.
     *
     * Properties are separate from attributes, and you can't use one in place of the other. Jetlin
     * writes `value` and `checked` as properties, because setting the attribute changes only a
     * control's default, which stops having any effect once the user has touched the control.
     */
    public fun property(name: String): PropValue? = properties[name]

    /** The events this element listens for. The handlers themselves stay private to the composition. */
    public val eventNames: Set<String> get() = Collections.unmodifiableSet(listeners.keys)

    /**
     * Returns what the element declared for [event], or `null` if it doesn't listen for it.
     *
     * The spec says what to extract, how to debounce, what the browser does by itself, and whether
     * the server hears about the event at all.
     */
    public fun listenerSpec(event: String): ListenerSpec? = listeners[event]

    /**
     * Updates the element to match [data], recording one op for each real difference.
     *
     * The applier calls this only when [data] differs from the previous composition's, so an
     * unchanged element costs one equality check and no traversal.
     */
    internal fun applyData(data: ElementData) {
        // Record no op. The client has no use for a test tag, which is why it's kept out of the
        // attributes. When test tags are exposed, the tag is also in data.attributes and is
        // patched from there like any other attribute.
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

    /** Calls the handler for [event], and returns `false` if there isn't one. */
    internal fun handle(event: String, payload: EventPayload): Boolean {
        val handler = handlers[event] ?: return false
        handler(payload)
        return true
    }

    override fun toSpec(): NodeSpec = NodeSpec.Element(
        id = id,
        tag = tag,
        namespace = namespace,
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
 * What a composable declared for an element in one composition pass.
 *
 * The applier compares it with the previous pass to decide whether anything needs sending.
 * Handlers are deliberately left out. Lambdas get new identities on every recomposition, so
 * including them would make every element differ from its previous pass and cause needless work.
 * Listener specs are included, because a change to debouncing or extraction must reach the client.
 */
internal data class ElementData(
    val attributes: Map<String, String>,
    val properties: Map<String, PropValue>,
    val listeners: Map<String, ListenerSpec>,
    val testTag: String? = null,
)

/**
 * Owns one session's tree: it allocates node IDs, finds nodes to route events to, and buffers ops.
 *
 * IDs are local to the session and only increase. That keeps them small on the wire and makes
 * protocol traces readable.
 */
public class HtmlOwner {
    private var nextId: NodeId = ROOT_ID + 1
    private val byId: HashMap<NodeId, ElementNode> = HashMap()
    private val ops: MutableList<Op> = mutableListOf()

    /**
     * Signals that something is waiting to be sent.
     *
     * The channel is conflated, so a recomposition pass that records fifty ops still wakes the
     * sender once.
     */
    private val dirty = Channel<Unit>(Channel.CONFLATED)

    /** Receives a signal whenever something is waiting to be sent. */
    internal val dirtySignals: ReceiveChannel<Unit> get() = dirty

    /** The root of the tree. It's always attached, and the browser never sees it as an element. */
    public val root: ElementNode =
        ElementNode(ROOT_ID, "#root", Namespace.HTML, this).apply { attached = true }

    internal fun allocateId(): NodeId = nextId++

    internal fun createElement(tag: String, namespace: Namespace = Namespace.HTML): ElementNode =
        ElementNode(allocateId(), tag, namespace, this)
    internal fun createText(text: String): TextNode = TextNode(allocateId(), text, this)

    internal fun register(node: ElementNode) { byId[node.id] = node }
    internal fun unregister(node: ElementNode) { byId.remove(node.id) }

    /**
     * Whether ops are worth recording.
     *
     * They aren't when no client is connected. The composition keeps running, because a timer keeps
     * ticking and a shared store keeps changing, but the next client to connect receives the whole
     * tree anyway. Recording those ops would use memory to describe changes that nobody will see.
     */
    private var recording = true

    /** Whether the buffer passed [maxBufferedOps] and was dropped. */
    private var overflowed = false

    /**
     * The most ops to buffer before dropping them and resending the whole tree instead.
     *
     * Without a limit, a client that stops reading, or reads much slower than the session produces
     * updates, would hold on to unbounded memory. Past the limit, the buffer is dropped and the next
     * message is the full tree. The session falls back to coarser updates instead of failing.
     */
    public var maxBufferedOps: Int = 10_000

    /** Buffers [op] for the next patch, unless recording is off or the buffer has overflowed. */
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

    /** Whether the buffer was dropped. If so, the next message must be the full tree, not a patch. */
    internal val hasOverflowed: Boolean get() = overflowed

    /** Starts recording ops again, after the client has received a tree it can patch. */
    internal fun startRecording() {
        recording = true
        overflowed = false
        ops.clear()
    }

    /** Stops recording and discards the buffer. The server calls this when the last client leaves. */
    internal fun stopRecording() {
        recording = false
        overflowed = false
        ops.clear()
    }

    /**
     * Wakes the sender when something other than an op needs sending.
     *
     * For example, navigating to a route that renders identically produces no ops. Without this
     * signal, nothing would wake the sender, and the browser's address bar would show the old URL.
     */
    internal fun signalDirty() {
        dirty.trySend(Unit)
    }

    /** Whether any op is buffered. The sender uses it to skip empty frames. */
    public val hasPendingOps: Boolean get() = ops.isNotEmpty()

    /** Removes and returns the buffered ops. Each drain becomes one patch message. */
    public fun drainOps(): List<Op> {
        if (ops.isEmpty()) return emptyList()
        val drained = ops.toList()
        ops.clear()
        return drained
    }

    /**
     * Calls the handler for [event] on node [nodeId].
     *
     * @return `false` if the node or the handler no longer exists. That's normal and harmless: the
     *   user clicked something that the server had already removed.
     */
    public fun dispatch(nodeId: NodeId, event: String, payload: EventPayload): Boolean =
        byId[nodeId]?.handle(event, payload) ?: false

    /** Returns the whole current tree, for a message that replaces the browser's tree. */
    public fun snapshotChildren(): List<NodeSpec> = root.children.map { it.toSpec() }
}
