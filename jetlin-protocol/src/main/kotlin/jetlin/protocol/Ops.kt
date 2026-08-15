package jetlin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Server-assigned identity for one DOM node within one session. */
public typealias NodeId = Int

public const val ROOT_ID: NodeId = 0

/**
 * A single mutation to apply to the browser DOM.
 *
 * These are not diffs. Every op here is something the Compose runtime told the applier to do, in
 * the order it said to do it — the framework never compares two trees to work out what changed.
 * That is the whole reason this project uses the Compose runtime rather than a template engine:
 * LiveView's fingerprint/static-dynamic machinery and Livewire's client-side morph both exist to
 * recover information that positional memoization already has.
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
 * Handlers themselves never cross the wire — the server keeps the lambda and looks it up by
 * (node, event). That is why Jetlin has no equivalent of Livewire's `wire:click="increment"`
 * string method names: a closure just captures what it needs, and stays type-checked.
 */
@Serializable
public data class ListenerSpec(
    /** Fields the client should read off the DOM event and include in the payload. */
    val extract: List<Extract> = emptyList(),
    /** Fire at most once per quiet period, in milliseconds. 0 disables. */
    val debounceMs: Int = 0,
    /** Fire at most once per interval, in milliseconds. 0 disables. */
    val throttleMs: Int = 0,
    val preventDefault: Boolean = false,
    val stopPropagation: Boolean = false,
)

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
