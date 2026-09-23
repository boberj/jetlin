package jetlin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Identifies one DOM node within one session. The server assigns it. */
public typealias NodeId = Int

/** The ID of the root node, which is the element the page's content is mounted in. */
public const val ROOT_ID: NodeId = 0

/**
 * One change to apply to the browser DOM.
 *
 * Ops are recorded, not computed. As a composition recomposes, the Compose runtime tells the
 * applier which nodes to insert, remove, move, or update. Each of those calls becomes one op, in the
 * order it happened. Nothing compares an old tree with a new one, so the browser receives a list of
 * instructions instead of a document to reconcile.
 */
@Serializable
public sealed interface Op {

    /**
     * Inserts [node], a complete subtree, as the child of [parent] at [index].
     *
     * The applier builds trees bottom-up, so a new subtree arrives as one op instead of one per node.
     */
    @Serializable
    @SerialName("ins")
    public data class Insert(val parent: NodeId, val index: Int, val node: NodeSpec) : Op

    /** Removes [count] children of [parent], starting at [index]. */
    @Serializable
    @SerialName("rm")
    public data class Remove(val parent: NodeId, val index: Int, val count: Int) : Op

    /**
     * Moves [count] children of [parent] from index [from] to index [to].
     *
     * The indexes follow Compose's `Applier.move` convention: [to] is the position before the
     * children are removed.
     */
    @Serializable
    @SerialName("mv")
    public data class Move(val parent: NodeId, val from: Int, val to: Int, val count: Int) : Op

    /** Sets the attribute [name] on node [id], or removes it if [value] is `null`. */
    @Serializable
    @SerialName("attr")
    public data class SetAttr(val id: NodeId, val name: String, val value: String?) : Op

    /**
     * Sets the DOM property [name] on node [id], instead of an attribute.
     *
     * `value`, `checked`, and `selected` must be set as properties. Setting the attribute changes
     * only the element's default, and the browser ignores it once the user has interacted with the
     * control.
     */
    @Serializable
    @SerialName("prop")
    public data class SetProp(val id: NodeId, val name: String, val value: PropValue) : Op

    /** Replaces the content of text node [id] with [text]. */
    @Serializable
    @SerialName("text")
    public data class SetText(val id: NodeId, val text: String) : Op

    /** Starts listening for [event] on node [id], as described by [spec]. */
    @Serializable
    @SerialName("on")
    public data class Listen(val id: NodeId, val event: String, val spec: ListenerSpec) : Op

    /** Stops listening for [event] on node [id]. */
    @Serializable
    @SerialName("off")
    public data class Unlisten(val id: NodeId, val event: String) : Op
}

/** The value of a DOM property. Properties are typed, unlike attributes, which are always strings. */
@Serializable
public sealed interface PropValue {
    /** A string property, such as `value`. */
    @Serializable
    @SerialName("s")
    public data class Str(val v: String) : PropValue

    /** A Boolean property, such as `checked`. */
    @Serializable
    @SerialName("b")
    public data class Bool(val v: Boolean) : PropValue
}

/**
 * The document language an element belongs to.
 *
 * The browser needs to know this before it creates the node. `document.createElement("circle")`
 * makes an `HTMLUnknownElement` that renders as nothing and reports no error, which is the worst way
 * for a chart to fail. Only `createElementNS` with the SVG namespace makes an element that draws.
 *
 * The server sends the namespace because the client can't work it out from the tag. `a`, `title`,
 * `style`, and `script` exist in both languages, so any list of SVG tags on the client would be
 * wrong for those four. The composition already knows which one it meant, because that's the
 * difference between `Svg { A { } }` and a plain `A { }`.
 *
 * The wire carries a short name instead of the namespace URI. The URI is over thirty bytes and
 * would repeat on every node of a chart, while the client can map two names to URIs with a small
 * table.
 */
@Serializable
public enum class Namespace {
    @SerialName("html")
    HTML,

    @SerialName("svg")
    SVG,
}

/** A node and its whole subtree. [Op.Insert] and [ServerMessage.Reset] carry these. */
@Serializable
public sealed interface NodeSpec {
    /** The node's ID. */
    public val id: NodeId

    /**
     * An element.
     *
     * @property tag the element's tag name.
     * @property attrs the element's attributes.
     * @property props the element's DOM properties. See [Op.SetProp].
     * @property listeners the events the element listens for, by event name.
     * @property children the element's children, in order.
     */
    @Serializable
    @SerialName("e")
    public data class Element(
        override val id: NodeId,
        val tag: String,
        /**
         * The element's namespace, stated on every element and not only where it changes.
         *
         * Inheriting it from the parent would drop the marker from all but the elements that switch
         * language. But then a node's meaning would depend on where you read it: a subtree in a
         * trace, or in a test's expected op list, would no longer say what it is. The encoder omits
         * the default, so an HTML page pays nothing for it either way.
         */
        @SerialName("ns")
        val namespace: Namespace = Namespace.HTML,
        val attrs: Map<String, String> = emptyMap(),
        val props: Map<String, PropValue> = emptyMap(),
        val listeners: Map<String, ListenerSpec> = emptyMap(),
        val children: List<NodeSpec> = emptyList(),
    ) : NodeSpec

    /** A text node containing [text]. */
    @Serializable
    @SerialName("t")
    public data class Text(override val id: NodeId, val text: String) : NodeSpec
}

/**
 * What the client should do when an event fires, and what it should send back.
 *
 * Handlers never cross the wire. The client learns only that a node listens for an event. When the
 * event fires, the client sends back the node ID and the event name, and the server looks up the
 * lambda it holds. So handlers can be ordinary, type-checked closures over whatever they need,
 * instead of functions named by a string that the client sends.
 */
@Serializable
public data class ListenerSpec(
    /** The fields the client reads from the DOM event and includes in the payload. */
    val extract: List<Extract> = emptyList(),
    /**
     * The work the browser does by itself when this event fires, before it sends anything.
     *
     * Showing a menu or opening a disclosure needs no server, and a round trip for it only adds
     * latency. The commands are a fixed set of verbs instead of a script, so they can never grow into
     * a second application living in the browser.
     */
    val commands: List<ClientCommand> = emptyList(),
    /**
     * Whether the server wants to hear about this event at all.
     *
     * It's `false` when the element declared [commands] but no handler. The browser then runs the
     * commands and sends nothing. The value comes from the composition instead of a declaration, so
     * it can't disagree with whether a handler exists.
     */
    val notify: Boolean = true,
    /**
     * How long, in milliseconds, the event must stop firing before the client sends it. `0` turns
     * debouncing off.
     */
    val debounceMs: Int = 0,
    /** The minimum time, in milliseconds, between two sends of this event. `0` turns throttling off. */
    val throttleMs: Int = 0,
    /** Whether the client calls `preventDefault()` on the event. */
    val preventDefault: Boolean = false,
    /** Whether the client calls `stopPropagation()` on the event. */
    val stopPropagation: Boolean = false,
)

/**
 * One thing the browser does without asking the server.
 *
 * The set is deliberately small and declarative. Anything that needs real logic belongs on the
 * server, with the rest of the application.
 */
@Serializable
public sealed interface ClientCommand {
    /** The element the command applies to. The default is the element the listener is on. */
    public val target: ClientTarget

    /** Adds the CSS class [name] to the target if it's missing, and removes it if it's present. */
    @Serializable
    @SerialName("toggle")
    public data class ToggleClass(
        val name: String,
        override val target: ClientTarget = ClientTarget.Self,
    ) : ClientCommand

    /** Adds the CSS class [name] to the target. */
    @Serializable
    @SerialName("add")
    public data class AddClass(
        val name: String,
        override val target: ClientTarget = ClientTarget.Self,
    ) : ClientCommand

    /** Removes the CSS class [name] from the target. */
    @Serializable
    @SerialName("remove")
    public data class RemoveClass(
        val name: String,
        override val target: ClientTarget = ClientTarget.Self,
    ) : ClientCommand

    /** Moves focus to the target. */
    @Serializable
    @SerialName("focus")
    public data class Focus(override val target: ClientTarget = ClientTarget.Self) : ClientCommand

    /** Removes focus from the target. */
    @Serializable
    @SerialName("blur")
    public data class Blur(override val target: ClientTarget = ClientTarget.Self) : ClientCommand
}

/**
 * Which element a [ClientCommand] applies to.
 *
 * The browser resolves the target, so it can name only things the browser can see. A CSS class is
 * the one such handle the markup already has. Node IDs would be precise, but they'd have to be
 * passed in from a composable that hasn't been composed yet.
 */
@Serializable
public sealed interface ClientTarget {
    /** The element the listener is declared on. */
    @Serializable
    @SerialName("self")
    public data object Self : ClientTarget

    /** The nearest ancestor that has the CSS class [className], starting with the element itself. */
    @Serializable
    @SerialName("closest")
    public data class Closest(val className: String) : ClientTarget
}

/** A field that the client reads from a DOM event and sends in the [EventPayload]. */
@Serializable
public enum class Extract {
    /** `event.target.value`, for text inputs, text areas, and selects. */
    @SerialName("value")
    VALUE,

    /** `event.target.checked`, for checkboxes and radio buttons. */
    @SerialName("checked")
    CHECKED,

    /** `event.key`, for keyboard events. */
    @SerialName("key")
    KEY,

    /** The form data of the closest enclosing `<form>`, serialized. */
    @SerialName("form")
    FORM,
}
