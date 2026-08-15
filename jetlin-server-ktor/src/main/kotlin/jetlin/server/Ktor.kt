package jetlin.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jetlin.html.AttributeKey
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.LocalRequest
import jetlin.html.P
import jetlin.html.RequestContext
import jetlin.html.RoutePattern
import jetlin.html.Router
import jetlin.html.Text
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

    /**
     * Markup injected into every page's `<head>`.
     *
     * Application-wide rather than per view, because navigation swaps the view inside a live page
     * and never re-runs the document head.
     */
    public var head: String = ""

    internal val views: MutableList<ViewRegistration> = mutableListOf()
    internal var attributeFactory: (suspend (ApplicationCall) -> Map<AttributeKey<*>, Any?>)? = null

    /**
     * Registers a view. [path] may contain parameters, e.g. `/todo/{id}`, readable with
     * `pathParam("id")`.
     */
    public fun view(path: String, title: String = "Jetlin", content: @Composable () -> Unit) {
        views += ViewRegistration(RoutePattern(path), title, content)
    }

    /**
     * Computes session-scoped values from the originating HTTP call, once per session.
     *
     * This is where an authenticated principal, tenant or locale enters the composition; the values
     * are read back through [RequestContext.get] with the keys the application declared. It runs on
     * the HTTP call because that is the only point at which Ktor's call context still exists.
     */
    public fun attributes(factory: suspend (ApplicationCall) -> Map<AttributeKey<*>, Any?>) {
        attributeFactory = factory
    }
}

internal class ViewRegistration(
    val pattern: RoutePattern,
    val title: String,
    val content: @Composable () -> Unit,
)

/**
 * Installs Jetlin's HTTP and WebSocket endpoints.
 *
 * A GET returns server-rendered HTML plus a session token; one shared WebSocket adopts the session
 * named by that token and then exchanges events for updates. A session is bound to the whole route
 * table rather than to one view, which is what allows navigation to swap views inside the live
 * composition instead of loading a new page.
 */
public fun Application.jetlin(configure: JetlinConfig.() -> Unit) {
    val config = JetlinConfig().apply(configure)
    val router = Router(config.views.map { it.pattern to it })
    install(WebSockets)
    val registry = SessionRegistry(this)

    routing {
        get("/jetlin/jetlin.js") {
            val script = checkNotNull(ViewRegistration::class.java.getResource("/jetlin/jetlin.js")) {
                "jetlin.js missing from resources; run `npm run build` in jetlin-client"
            }.readText()
            call.respondText(script, ContentType.Text.JavaScript)
        }

        for (registration in config.views) {
            get(registration.pattern.pattern) {
                val request = call.toRequestContext(config)
                val session = registry.create(config.framePolicy, request) { current ->
                    RouteHost(router, current)
                }
                call.respondText(
                    renderPage(config, registration.title, session),
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
                    session.view.messages.collect { sendMessage(it.withTitle(router)) }
                }

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val message = JetlinJson.decodeFromString(ClientMessage.serializer(), frame.readText())
                    session.view.dispatch(message)
                }
                sender.cancel()
            } finally {
                registry.detach(session)
            }
        }
    }
}

/**
 * Chooses the view for the current location and gives it its path parameters.
 *
 * `key` on the matched pattern is deliberate: moving between two different routes must rebuild
 * rather than reuse state, while moving between two instances of the same route — `/todo/1` to
 * `/todo/2` — keeps the view and just re-runs it with new parameters.
 */
@Composable
private fun RouteHost(router: Router<ViewRegistration>, request: RequestContext) {
    val match = router.resolve(request.path)
    if (match == null) {
        NotFound(request.path)
        return
    }
    CompositionLocalProvider(LocalRequest provides request.withPathParams(match.pathParams)) {
        key(match.pattern.pattern) {
            match.value.content()
        }
    }
}

@Composable
private fun NotFound(path: String) {
    Div({ classes("jl-not-found") }) {
        H1 { Text("Not found") }
        P { Text("No view is registered for $path") }
    }
}

/** Fills in the destination's document title, which only the route table knows. */
private fun ServerMessage.withTitle(router: Router<ViewRegistration>): ServerMessage =
    if (this is ServerMessage.Navigate && title == null) {
        copy(title = router.resolve(url.substringBefore('?'))?.value?.title)
    } else {
        this
    }

private suspend fun ApplicationCall.toRequestContext(config: JetlinConfig): RequestContext =
    RequestContext(
        path = request.path(),
        pathParams = parameters.names().associateWith { parameters[it].orEmpty() },
        queryParams = request.queryParameters.names().associateWith { request.queryParameters.getAll(it).orEmpty() },
        headers = request.headers.names().associateWith { request.headers.getAll(it).orEmpty() },
        attributes = config.attributeFactory?.invoke(this).orEmpty(),
    )

private suspend fun io.ktor.websocket.WebSocketSession.receiveMessage(): ClientMessage? {
    val frame = incoming.receiveCatching().getOrNull() as? Frame.Text ?: return null
    return JetlinJson.decodeFromString(ClientMessage.serializer(), frame.readText())
}

private suspend fun io.ktor.websocket.WebSocketSession.sendMessage(message: ServerMessage) {
    send(Frame.Text(JetlinJson.encodeToString(ServerMessage.serializer(), message)))
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

private suspend fun renderPage(config: JetlinConfig, title: String, session: JetlinSession): String {
    val body = session.view.renderHtml()
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>$title</title>
        ${config.head}
        </head>
        <body>
        <div id="jetlin-root">$body</div>
        <script src="/jetlin/jetlin.js"></script>
        <script>window.jetlin = Jetlin.connect({ token: "${session.token}" });</script>
        </body>
        </html>
    """.trimIndent()
}
