package jetlin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** A message the server sends to the browser over the WebSocket. */
@Serializable
public sealed interface ServerMessage {

    /**
     * The DOM changes produced by one recomposition pass.
     *
     * @property rev the revision of the tree after these changes.
     * @property ack the highest client [ClientMessage.Event.seq] whose effects this patch includes.
     *   The client uses it to protect typing in progress. If the client sent an event from a node
     *   more recently than [ack], a write to that node's `value` property is stale. The client
     *   drops it instead of overwriting what the user is typing.
     * @property ops the changes, in the order to apply them.
     */
    @Serializable
    @SerialName("patch")
    public data class Patch(val rev: Long, val ack: Long, val ops: List<Op>) : ServerMessage

    /**
     * The server accepted the client's request to adopt the server-rendered markup.
     *
     * This message carries no tree, because the client already has one in the HTML it was served.
     * Anything that changed between rendering that HTML and the socket connecting follows as an
     * ordinary [Patch].
     *
     * @property rev the revision of the tree the client adopted.
     */
    @Serializable
    @SerialName("ready")
    public data class Ready(val rev: Long) : ServerMessage

    /**
     * Replaces the entire tree.
     *
     * The server sends this when it can't patch what the browser has, for example after it wakes a
     * hibernated session and composes it from scratch.
     *
     * @property rev the revision of the new tree.
     * @property children the new contents of the root.
     */
    @Serializable
    @SerialName("reset")
    public data class Reset(val rev: Long, val children: List<NodeSpec>) : ServerMessage

    /**
     * The session moved to another location, and the browser should update its address bar.
     *
     * It travels on the same channel as patches, so it can't overtake the patch that rendered the
     * destination.
     *
     * @property url the new location.
     * @property replace whether to call `replaceState` instead of `pushState`. Redirects set it, so
     *   the user can't go back to a location that only redirects.
     * @property title the new document title, or `null` to keep the current one.
     */
    @Serializable
    @SerialName("nav")
    public data class Navigate(
        val url: String,
        val replace: Boolean = false,
        val title: String? = null,
    ) : ServerMessage

    /**
     * Something went wrong on the server.
     *
     * @property message a description for the browser console.
     * @property fatal whether the session is gone. The client reloads the page after a fatal error,
     *   unless the page cancels the error event.
     */
    @Serializable
    @SerialName("error")
    public data class Error(val message: String, val fatal: Boolean = false) : ServerMessage
}

/** A message the browser sends to the server over the WebSocket. */
@Serializable
public sealed interface ClientMessage {

    /**
     * The first message on every connection.
     *
     * @property token identifies the session that the initial page render created, so the server can
     *   reuse the composition it already has instead of rendering again.
     * @property url where the browser is now. This matters when the server wakes the session from
     *   storage. The user might have pressed the back button while disconnected, and then the
     *   address bar is right and the stored location is stale.
     */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val token: String,
        val url: String? = null,
        /**
         * Whether the client has indexed the server-rendered markup and wants to keep it instead of
         * receiving the tree again.
         *
         * This is a request, not a claim. The server refuses whenever its own tree might have
         * changed since it rendered what the browser holds.
         */
        val adopt: Boolean = false,
    ) : ClientMessage

    /**
     * A DOM event on a node that has a server-side handler.
     *
     * @property node the node the event happened on.
     * @property event the event type, such as `click` or `input`.
     * @property seq the client's sequence number for this event. It increases with each event, and
     *   the server echoes it back as [ServerMessage.Patch.ack].
     * @property payload what the client read from the DOM event.
     */
    @Serializable
    @SerialName("event")
    public data class Event(
        val node: NodeId,
        val event: String,
        val seq: Long,
        val payload: EventPayload = EventPayload(),
    ) : ClientMessage

    /**
     * The user pressed back or forward.
     *
     * The browser has already changed its address bar, so the server follows instead of leading. It
     * moves the session to [url] and doesn't send a [ServerMessage.Navigate] back.
     */
    @Serializable
    @SerialName("nav")
    public data class Navigate(val url: String) : ClientMessage
}

/**
 * The data a client event carries. Which fields are set depends on the event.
 *
 * @property value the target element's `value`, for input and change events.
 * @property checked the target element's `checked` state, for checkboxes and radio buttons.
 * @property key the key that was pressed, for keyboard events.
 * @property form the form's fields by name, for submit events.
 */
@Serializable
public data class EventPayload(
    val value: String? = null,
    val checked: Boolean? = null,
    val key: String? = null,
    val form: Map<String, String>? = null,
    /**
     * A payload from a client component, which the framework doesn't interpret.
     *
     * Jetlin knows how to read the other fields from a DOM event. This one holds whatever a
     * component chose to send, so it stays opaque JSON until the application decodes it.
     */
    val data: JsonObject? = null,
)

/** The event name that a client component's events arrive under. See `ClientComponent`. */
public const val COMPONENT_EVENT: String = "jl:component"

/** The key in [EventPayload.data] that holds the component's own name for the event. */
public const val COMPONENT_EVENT_NAME: String = "event"

/** The key in [EventPayload.data] that holds the component's payload. */
public const val COMPONENT_EVENT_PAYLOAD: String = "payload"

/**
 * The codec for every protocol message.
 *
 * The class discriminator is `t` and defaults aren't encoded, to keep frames small.
 */
public val JetlinJson: Json = Json {
    classDiscriminator = "t"
    encodeDefaults = false
    ignoreUnknownKeys = true
}
