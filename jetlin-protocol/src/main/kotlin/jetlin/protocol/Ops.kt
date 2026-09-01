package jetlin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server-assigned identity for one DOM node within one session. */
public typealias NodeId = Int

public const val ROOT_ID: NodeId = 0

/**
 * A single mutation to apply to the browser DOM.
 *
 * Ops are produced, not computed. As a composition recomposes, the Compose runtime tells the
 * applier which nodes to insert, remove, move or update; each of those calls is recorded as one op,
 * in the order it happened. Nothing compares an old tree against a new one, so the browser receives
 * a list of instructions rather than a document to reconcile.
 */
@Serializable
public sealed interface Op {

    /** Insert an already-complete subtree. Bottom-up application means one op per subtree. */
    @Serializable
    @SerialName("ins")
    public data class Insert(val parent: NodeId, val index: Int, val node: NodeSpec) : Op

    @Serializable
    @SerialName("rm")
    public data class Remove(val parent: NodeId, val index: Int, val count: Int) : Op

    @Serializable
    @SerialName("mv")
    public data class Move(val parent: NodeId, val from: Int, val to: Int, val count: Int) : Op

    /** `value == null` removes the attribute. */
    @Serializable
    @SerialName("attr")
    public data class SetAttr(val id: NodeId, val name: String, val value: String?) : Op

    /**
     * Sets a DOM *property* rather than an attribute.
     *
     * `value`, `checked` and `selected` must be assigned as properties: setting the corresponding
     * attribute only changes the element's default and is silently ignored once a user has
     * interacted with the control.
     */
    @Serializable
    @SerialName("prop")
    public data class SetProp(val id: NodeId, val name: String, val value: PropValue) : Op

    @Serializable
    @SerialName("text")
    public data class SetText(val id: NodeId, val text: String) : Op

    @Serializable
    @SerialName("on")
    public data class Listen(val id: NodeId, val event: String, val spec: ListenerSpec) : Op

    @Serializable
    @SerialName("off")
    public data class Unlisten(val id: NodeId, val event: String) : Op
}

@Serializable
public sealed interface PropValue {
    @Serializable
    @SerialName("s")
    public data class Str(val v: String) : PropValue

    @Serializable
    @SerialName("b")
    public data class Bool(val v: Boolean) : PropValue
}

/** A node and its whole subtree, used by [Op.Insert] and by full-tree resets after rehydration. */
@Serializable
public sealed interface NodeSpec {
    public val id: NodeId

    @Serializable
    @SerialName("e")
    public data class Element(
        override val id: NodeId,
        val tag: String,
        val attrs: Map<String, String> = emptyMap(),
        val props: Map<String, PropValue> = emptyMap(),
        val listeners: Map<String, ListenerSpec> = emptyMap(),
        val children: List<NodeSpec> = emptyList(),
    ) : NodeSpec

    @Serializable
    @SerialName("t")
    public data class Text(override val id: NodeId, val text: String) : NodeSpec
}

/**
 * What the client should do when an event fires, and what it should send back.
 *
 * Handlers themselves never cross the wire. The client only learns that a node listens for an
 * event; when one fires it sends back the node id and the event name, and the server looks up the
 * lambda it is holding. Handlers can therefore be ordinary closures over whatever they need, and
 * stay type-checked, instead of being named by a string the client would have to send.
 */
@Serializable
public data class ListenerSpec(
    /** Fields the client should read off the DOM event and include in the payload. */
    val extract: List<Extract> = emptyList(),
    /**
     * Work the browser does for itself when this event fires, before anything is sent.
     *
     * A closed vocabulary rather than a script: showing a menu or opening a disclosure needs no
     * server, and paying a round trip for it is latency spent on nothing. Keeping it to a fixed set
     * of verbs means these can never grow into a second application living in the browser.
     */
    val commands: List<ClientCommand> = emptyList(),
    /**
     * Whether the server wants to hear about this event at all.
     *
     * False when the element declared only [commands] and no handler, in which case the browser acts
     * and stays quiet. Computed from the composition rather than declared, so it cannot disagree
     * with whether a handler actually exists.
     */
    val notify: Boolean = true,
    /** Fire at most once per quiet period, in milliseconds. 0 disables. */
    val debounceMs: Int = 0,
    /** Fire at most once per interval, in milliseconds. 0 disables. */
    val throttleMs: Int = 0,
    val preventDefault: Boolean = false,
    val stopPropagation: Boolean = false,
)

/**
 * One thing the browser can be told to do without consulting the server.
 *
 * Deliberately small and declarative. Anything needing real logic belongs on the server, where the
 * rest of the application already is.
 */
@Serializable
public sealed interface ClientCommand {
    /** Where the command lands. Defaults to the element the listener is on. */
    public val target: ClientTarget

    @Serializable
    @SerialName("toggle")
    public data class ToggleClass(
        val name: String,
        override val target: ClientTarget = ClientTarget.Self,
    ) : ClientCommand

    @Serializable
    @SerialName("add")
    public data class AddClass(
        val name: String,
        override val target: ClientTarget = ClientTarget.Self,
    ) : ClientCommand

    @Serializable
    @SerialName("remove")
    public data class RemoveClass(
        val name: String,
        override val target: ClientTarget = ClientTarget.Self,
    ) : ClientCommand

    @Serializable
    @SerialName("focus")
    public data class Focus(override val target: ClientTarget = ClientTarget.Self) : ClientCommand

    @Serializable
    @SerialName("blur")
    public data class Blur(override val target: ClientTarget = ClientTarget.Self) : ClientCommand
}

/**
 * Which element a [ClientCommand] applies to.
 *
 * Resolved in the browser, so it can only name things the browser can see. A CSS class is the one
 * such handle the markup already carries — node ids would be precise but would have to be threaded
 * from a composable that has not been laid out yet.
 */
@Serializable
public sealed interface ClientTarget {
    /** The element the listener is declared on. */
    @Serializable
    @SerialName("self")
    public data object Self : ClientTarget

    /** The nearest ancestor carrying [className], starting with the element itself. */
    @Serializable
    @SerialName("closest")
    public data class Closest(val className: String) : ClientTarget
}

@Serializable
public enum class Extract {
    /** `event.target.value` — text inputs, textareas, selects. */
    @SerialName("value")
    VALUE,

    /** `event.target.checked` — checkboxes and radios. */
    @SerialName("checked")
    CHECKED,

    /** `event.key` — keyboard events. */
    @SerialName("key")
    KEY,

    /** Serialized form data from the closest enclosing `<form>`. */
    @SerialName("form")
    FORM,
}
