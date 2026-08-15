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

    /** Replaces the entire tree. Sent after rehydrating a session whose composition was dropped. */
    @Serializable
    @SerialName("reset")
    public data class Reset(val rev: Long, val children: List<NodeSpec>) : ServerMessage

    @Serializable
    @SerialName("error")
    public data class Error(val message: String, val fatal: Boolean = false) : ServerMessage
}

@Serializable
public sealed interface ClientMessage {

    /**
     * Sent once on connect. [token] identifies the session created by the initial page render, so
     * the server can adopt the composition it already has warm instead of rendering a second time.
     */
    @Serializable
    @SerialName("hello")
    public data class Hello(val token: String) : ClientMessage

    @Serializable
    @SerialName("event")
    public data class Event(
        val node: NodeId,
        val event: String,
        val seq: Long,
        val payload: EventPayload = EventPayload(),
    ) : ClientMessage
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
