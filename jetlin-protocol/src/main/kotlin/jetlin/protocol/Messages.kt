package jetlin.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
public sealed interface ServerMessage {

    /**
     * Incremental mutations produced by one recomposition pass.
     *
     * [ack] is the highest client [ClientMessage.Event.seq] whose effects are included. The client
     * uses it to protect in-flight typing: if it has sent an event from a node more recently than
     * [ack], a `value` property write for that node is stale and gets dropped rather than
     * overwriting what the user is currently typing.
     */
    @Serializable
    @SerialName("patch")
    public data class Patch(val rev: Long, val ack: Long, val ops: List<Op>) : ServerMessage

    /**
     * The server accepted the client's adoption of the server-rendered markup.
     *
     * Carries no tree: the client already has one, in the HTML it was served. Anything that changed
     * between rendering that HTML and the socket connecting follows as an ordinary [Patch].
     */
    @Serializable
    @SerialName("ready")
    public data class Ready(val rev: Long) : ServerMessage

    /** Replaces the entire tree. Sent after rehydrating a session whose composition was dropped. */
    @Serializable
    @SerialName("reset")
    public data class Reset(val rev: Long, val children: List<NodeSpec>) : ServerMessage

    /**
     * The session moved to another location; the browser should update its address bar.
     *
     * Carried on the same channel as patches so it cannot overtake the patch that rendered the
     * destination. [replace] chooses `replaceState` over `pushState`, which matters for redirects
     * that should not add a history entry the user can go back to.
     */
    @Serializable
    @SerialName("nav")
    public data class Navigate(
        val url: String,
        val replace: Boolean = false,
        val title: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("error")
    public data class Error(val message: String, val fatal: Boolean = false) : ServerMessage
}

@Serializable
public sealed interface ClientMessage {

    /**
     * Sent once on connect.
     *
     * [token] identifies the session created by the initial page render, so the server can adopt
     * the composition it already has warm instead of rendering a second time.
     *
     * [url] is where the browser currently is. It matters when the session had to be woken from
     * storage: the user may have navigated with the back button while disconnected, in which case
     * the address bar is right and the stored location is stale.
     */
    @Serializable
    @SerialName("hello")
    public data class Hello(
        val token: String,
        val url: String? = null,
        /**
         * The client has indexed the server-rendered markup and would rather keep it than be sent
         * the tree again. A request, not an assertion: the server refuses whenever its own tree may
         * have moved on from what the browser is holding.
         */
        val adopt: Boolean = false,
    ) : ClientMessage

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
     * The browser has already changed its address bar, so the server follows rather than leads: it
     * moves the session to [url] and does not echo a [ServerMessage.Navigate] back.
     */
    @Serializable
    @SerialName("nav")
    public data class Navigate(val url: String) : ClientMessage
}

@Serializable
public data class EventPayload(
    val value: String? = null,
    val checked: Boolean? = null,
    val key: String? = null,
    val form: Map<String, String>? = null,
)

/** Shared codec. Class discriminator is `t` to keep frames small. */
public val JetlinJson: Json = Json {
    classDiscriminator = "t"
    encodeDefaults = false
    ignoreUnknownKeys = true
}
