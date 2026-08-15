package jetlin.server

import androidx.compose.runtime.Composable
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jetlin.protocol.ClientMessage
import jetlin.protocol.JetlinJson
import jetlin.protocol.ServerMessage
import jetlin.runtime.FramePolicy
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

public class JetlinConfig {
    public var framePolicy: FramePolicy = FramePolicy.Immediate

    /**
     * Origins permitted to open a session socket, as full origins (`https://app.example`).
     *
     * Empty means same-origin only, checked against the request's `Host`. Set this when the browser
     * loads the page from a different host than it opens the socket to.
     */
    public var allowedOrigins: Set<String> = emptySet()

    internal val views: MutableMap<String, ViewRegistration> = LinkedHashMap()

    /** Registers a live view at [path]. The composable is re-instantiated per visitor. */
    public fun view(
        path: String,
        title: String = "Jetlin",
        head: String = "",
        content: @Composable () -> Unit,
    ) {
        views[path] = ViewRegistration(title, head, content)
    }
}

internal class ViewRegistration(
    val title: String,
    val head: String,
    val content: @Composable () -> Unit,
)

/**
 * Installs Jetlin's HTTP and WebSocket endpoints.
 *
 * Two routes carry everything: a GET per view that returns server-rendered HTML plus a session
 * token, and one shared WebSocket that adopts the session named by that token and then exchanges
 * events for patches.
 */
public fun Application.jetlin(configure: JetlinConfig.() -> Unit) {
    val config = JetlinConfig().apply(configure)
    install(WebSockets)
    val registry = SessionRegistry(this)

    routing {
        get("/jetlin/jetlin.js") {
            val script = checkNotNull(ViewRegistration::class.java.getResource("/jetlin/jetlin.js")) {
                "jetlin.js missing from resources; run `npm run build` in jetlin-client"
            }.readText()
            call.respondText(script, ContentType.Text.JavaScript)
        }

        for ((path, registration) in config.views) {
            get(path) {
                val session = registry.create(config.framePolicy, registration.content)
                call.respondText(
                    renderPage(registration, session),
                    ContentType.Text.Html,
                )
            }
        }

        webSocket("/jetlin") {
            // The browser's same-origin policy does not cover WebSockets: any page on any site can
            // open one to this endpoint. Without this check, a hostile page that got hold of a
            // token could drive a victim's session.
            if (!originAllowed(
                    origin = call.request.headers["Origin"],
                    host = call.request.headers["Host"],
                    allowed = config.allowedOrigins,
                )
            ) {
                sendMessage(ServerMessage.Error("Origin not allowed", fatal = true))
                return@webSocket
            }

            val hello = receiveMessage() as? ClientMessage.Hello ?: return@webSocket
            val session = registry.attach(hello.token)
            if (session == null) {
                sendMessage(ServerMessage.Error("Unknown or already-attached session", fatal = true))
                return@webSocket
            }

            try {
                // The composition is already built and warm from the page render; the client just
                // needs the tree it describes.
                sendMessage(session.view.reset())

                val sender = launch {
                    session.view.patches.collect { sendMessage(it) }
                }

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = JetlinJson.decodeFromString(ClientMessage.serializer(), frame.readText())
                    if (message is ClientMessage.Event) {
                        session.view.dispatch(message)
                    }
                }
                sender.cancel()
            } finally {
                registry.detach(session)
            }
        }
    }
}

/**
 * Decides whether a socket may be opened from [origin].
 *
 * A missing `Origin` header means the caller is not a browser — a test, a CLI, a service — and is
 * allowed through, because the header exists to identify the *page* that initiated the request and
 * only browsers can be trusted to set it honestly. When [allowed] is empty the origin must match the
 * request's own `Host`, which is the same-origin case.
 */
internal fun originAllowed(origin: String?, host: String?, allowed: Set<String>): Boolean {
    if (origin == null) return true
    if (allowed.isNotEmpty()) return origin in allowed
    if (host == null) return false
    val originAuthority = origin.substringAfter("://", missingDelimiterValue = "")
    return originAuthority.isNotEmpty() && originAuthority == host
}

private suspend fun io.ktor.websocket.WebSocketSession.receiveMessage(): ClientMessage? {
    val frame = incoming.receiveCatching().getOrNull() as? Frame.Text ?: return null
    return JetlinJson.decodeFromString(ClientMessage.serializer(), frame.readText())
}

private suspend fun io.ktor.websocket.WebSocketSession.sendMessage(message: ServerMessage) {
    send(Frame.Text(JetlinJson.encodeToString(ServerMessage.serializer(), message)))
}

private suspend fun renderPage(registration: ViewRegistration, session: JetlinSession): String {
    val body = session.view.renderHtml()
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${registration.title}</title>
        ${registration.head}
        </head>
        <body>
        <div id="jetlin-root">$body</div>
        <script src="/jetlin/jetlin.js"></script>
        <script>window.jetlin = Jetlin.connect({ token: "${session.token}" });</script>
        </body>
        </html>
    """.trimIndent()
}
