package jetlin.server

import androidx.compose.runtime.Composable
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import jetlin.html.P
import jetlin.html.RequestContext
import jetlin.html.RouteHost
import jetlin.html.RoutePattern
import jetlin.html.rootAttributes
import jetlin.html.Router
import jetlin.html.Text
import jetlin.protocol.ClientMessage
import jetlin.protocol.JetlinJson
import jetlin.protocol.ServerMessage
import jetlin.runtime.FramePolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

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

    /** Where hibernated sessions are kept. Swap for a shared store to survive restarts. */
    public var sessionStore: SessionStore = InMemorySessionStore()

    /**
     * Writes every `testTag` into the markup as `data-test` as well.
     *
     * Off by default, because those names are for tests and cost bytes on every element for every
     * user. Turn it on outside production so that browser tests, which can only select on what is
     * really in the DOM, have something to select on.
     */
    public var exposeTestTags: Boolean = false

    /**
     * Markup placed after the runtime loads and before the session connects.
     *
     * Where an application registers its client components, because the order matters: the first
     * thing a connection does is take up the markup it was served, and a component whose
     * implementation has not been registered by then renders nothing.
     *
     * ```kotlin
     * clientSetup = """<script src="/app.js"></script>"""
     * ```
     */
    public var clientSetup: String = ""

    /**
     * Called when a session fails, before the client is told anything.
     *
     * Where an application sends the exception to whatever it uses for error reporting. Jetlin logs
     * it too, but a log line nobody reads is not error handling.
     *
     * What reaches the browser is a fixed, generic message: an exception's text routinely carries
     * query fragments, file paths and identifiers, and none of that is the client's business.
     */
    public var onError: (Throwable) -> Unit = { }

    /**
     * Most live sessions to hold at once.
     *
     * Every page render allocates one — roughly 136 kB for the sample — whether or not a socket ever
     * arrives to claim it, and unclaimed ones are only released when the handoff timeout runs out.
     * Without a ceiling a stream of unauthenticated requests grows memory until the process dies.
     *
     * Set it from measured cost and available heap rather than from this default, which is chosen to
     * be out of the way for real traffic rather than to be right for any particular deployment. When
     * it is reached, page renders are refused with a 503; reconnects are not, because someone
     * reattaching already had a session.
     */
    public var maxSessions: Int = 10_000

    /**
     * Sustained ceiling on messages one connection may send, per second. Zero or less disables it.
     *
     * A person typing into a debounced field produces a handful a second and clicking produces
     * fewer, so this is far above anything a human does; what it stops is a page — hostile or
     * broken — driving recomposition in a loop and taking a share of the machine with it.
     */
    public var eventsPerSecond: Double = 50.0

    /**
     * How far one connection may run ahead of [eventsPerSecond] in a burst.
     *
     * Real use is bursty: a form gets filled in with a flurry of events and then nothing. A limit
     * that cannot absorb the flurry has to be set so high that it stops protecting anything.
     */
    public var eventBurst: Int = 100

    /**
     * How long a composition stays up after its socket goes away.
     *
     * Long enough that a tunnel or a sleeping laptop reattaches to a running composition; past it,
     * the session hibernates into [sessionStore].
     */
    public var disconnectGrace: Duration = 30.seconds

    internal val views: MutableList<ViewRegistration> = mutableListOf()
    internal var attributeFactory: (suspend (ApplicationCall) -> Map<AttributeKey<*>, Any?>)? = null
    internal var appContainer: (@Composable (route: @Composable () -> Unit) -> Unit)? = null

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

    /**
     * Wraps every view in a container composed once for the session, with [content] deciding where
     * the view goes.
     *
     * Navigation swaps the view inside a composition that stays alive, so this is the one place
     * whose `remember` outlives a page change:
     *
     * ```kotlin
     * jetlin {
     *     app { route ->
     *         val filters = remember { Filters() }
     *         CompositionLocalProvider(LocalFilters provides filters) {
     *             Shell { route() }
     *         }
     *     }
     *     view("/") { FleetPage() }
     *     view("/vessels/{id}") { VesselPage() }
     * }
     * ```
     *
     * Chrome belongs here too, for a reason that shows up in the patches: navigation rebuilds the
     * view, so a navigation bar composed inside each view is torn out and re-inserted on every move,
     * while one composed here recomposes to the same markup and emits nothing.
     *
     * State that must also survive hibernation goes in `rememberSaved` rather than `remember` —
     * inside the container that works, because the container is never disposed. What a view saves
     * comes back when the view is returned to, without needing this.
     *
     * Call [route] exactly once. Not calling it renders an application with no pages in it.
     */
    public fun app(content: @Composable (route: @Composable () -> Unit) -> Unit) {
        appContainer = content
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
    // One line a minute at most for each: both are reached at request rate, and a warning
    // repeated ten thousand times buries the one somebody needed to read.
    val capacityLog = LogThrottle(60_000_000_000L)

    val registry = SessionRegistry(
        scope = this,
        store = config.sessionStore,
        framePolicy = config.framePolicy,
        disconnectGrace = config.disconnectGrace,
        exposeTestTags = config.exposeTestTags,
        maxSessions = config.maxSessions,
    ) { current -> RouteHost(router, current, config.appContainer, { NotFound(it) }) { it.content() } }

    routing {
        get("/jetlin/jetlin.js") {
            val script = checkNotNull(ViewRegistration::class.java.getResource("/jetlin/jetlin.js")) {
                "jetlin.js missing from resources; run `npm run build` in jetlin-client"
            }.readText()
            call.respondText(script, ContentType.Text.JavaScript)
        }

        for (registration in config.views) {
            get(registration.pattern.pattern) {
                val session = try {
                    registry.create(call.toRequestContext(config))
                } catch (e: SessionLimitReachedException) {
                    // Refusing is the point: the alternative is memory settling somewhere the heap
                    // cannot hold, which takes everyone's session with it rather than only this one.
                    // Reaching the cap is not normal, so it is said out loud — but at request rate,
                    // so it is said at most once a minute with a count of what it stood for.
                    capacityLog.attempt()?.let { suppressed ->
                        logger.warn(
                            "At the session limit of {}: refusing page renders. " +
                                "{} live, {} refused since this message, {} refused in total.",
                            e.limit,
                            registry.liveCount,
                            suppressed,
                            registry.rejectedCount,
                        )
                    }
                    call.response.headers.append(HttpHeaders.RetryAfter, "5")
                    call.respondText(
                        AT_CAPACITY_PAGE,
                        ContentType.Text.Html,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
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
            // The socket's own request supplies headers and any attributes derived from them, so a
            // session woken from storage recomputes its principal instead of trusting a stale one.
            val session = registry.attach(hello.token, call.toRequestContext(config), hello.url)
            if (session == null) {
                sendMessage(ServerMessage.Error("Unknown or already-attached session", fatal = true))
                return@webSocket
            }

            // Counted out here so the tally survives into the finally that reports it.
            var dropped = 0L

            try {
                // The composition is already built and warm from the page render. If this is the
                // socket that page opened, the browser is holding markup this very composition
                // produced and can keep it; anything that changed since follows as a normal patch.
                // Claimed unconditionally so the right to adopt is spent either way.
                val mayAdopt = session.claimAdoption()
                sendMessage(if (mayAdopt && hello.adopt) session.view.adopt() else session.view.reset())

                val sender = launch {
                    session.view.messages.collect { sendMessage(it.withTitle(router)) }
                }

                // Consulted before anything is parsed, so a flood costs a clock read rather
                // than a JSON decode.
                val budget = TokenBucket(config.eventsPerSecond, config.eventBurst)
                var throttled = false

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    if (!budget.tryConsume()) {
                        // Dropped rather than queued: holding it would only move the flood into
                        // memory, and the client is better told it is going too fast. Said once per
                        // episode, because a page that ignores the first will ignore the next
                        // thousand.
                        dropped++
                        if (!throttled) {
                            throttled = true
                            // Named well enough to find: a page sending faster than a person can
                            // click is almost always a loop in application JavaScript, and knowing
                            // which page is most of the work of fixing it. The token is truncated
                            // on purpose — it is a bearer credential, and a leaked log should not
                            // be a session takeover. Eight characters correlate lines with each
                            // other without being enough to use.
                            logger.warn(
                                "Throttling session {} on {}: sending faster than {}/s",
                                session.token.take(8),
                                session.view.currentUrl,
                                config.eventsPerSecond,
                            )
                            sendMessage(ServerMessage.Error(TOO_FAST, fatal = false))
                        }
                        continue
                    }
                    throttled = false

                    val message = try {
                        JetlinJson.decodeFromString(ClientMessage.serializer(), frame.readText())
                    } catch (t: Throwable) {
                        // Frames come from a browser, which is not obliged to be well behaved. One
                        // that cannot be read is dropped; taking the session down over it would let
                        // anyone end their own session with a malformed message, and would turn a
                        // protocol version skew into an outage rather than a warning.
                        logger.warn("Ignoring a frame that could not be read", t)
                        continue
                    }

                    try {
                        session.view.dispatch(message)
                    } catch (t: Throwable) {
                        config.onError(t)
                        if (session.view.isAlive) {
                            // A handler threw. The composition is untouched and the page is still
                            // correct — this one interaction did not happen, which is the client's
                            // business to know and nobody else's.
                            logger.error("A handler failed while processing a client event", t)
                            sendMessage(ServerMessage.Error(HANDLER_FAILED, fatal = false))
                        } else {
                            // A composable threw, which stops the recomposer for good. Nothing this
                            // session does from here can succeed, so say so plainly instead of
                            // leaving a page that looks live.
                            logger.error("The composition failed; the session cannot continue", t)
                            sendMessage(ServerMessage.Error(SESSION_FAILED, fatal = true))
                            break
                        }
                    }
                }
                sender.cancel()
            } finally {
                // The total, once, on the way out. A connection can be throttled and recover many
                // times over its life, so the per-episode lines say it is happening and this says
                // how much it came to.
                if (dropped > 0) {
                    logger.warn(
                        "Dropped {} events for exceeding {}/s: session {} on {}",
                        dropped,
                        config.eventsPerSecond,
                        session.token.take(8),
                        session.view.currentUrl,
                    )
                }
                // Stop recording before releasing the session: the composition keeps running during
                // the grace period, and whoever reconnects is sent the whole tree anyway.
                session.view.clientDetached()
                registry.detach(session)
            }
        }
    }
}

/**
 * What a client is told when one of its events could not be handled.
 *
 * Deliberately says nothing about why. The exception is logged and handed to [JetlinConfig.onError];
 * its text is for whoever operates the server, not for whoever is looking at the page.
 */
private val logger = LoggerFactory.getLogger("jetlin.server")

private const val HANDLER_FAILED: String = "That action could not be completed."

/** What a client is told when it is sending faster than the server is willing to listen. */
private const val TOO_FAST: String = "Too many messages; some were ignored."

/** Served when the session limit is reached. Deliberately static: rendering a view needs a session. */
private val AT_CAPACITY_PAGE: String = """
    <!doctype html>
    <html lang="en">
    <head><meta charset="utf-8"><title>Busy</title></head>
    <body>
    <h1>Busy</h1>
    <p>This server is holding as many sessions as it is configured to. Please try again shortly.</p>
    </body>
    </html>
""".trimIndent()

/** What a client is told when its session has stopped for good and reloading is the only way on. */
private const val SESSION_FAILED: String = "This session ended unexpectedly."

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
        <div id="jetlin-root"${rootAttributes(session.view.owner)}>$body</div>
        <script src="/jetlin/jetlin.js"></script>
        ${config.clientSetup}
        <script>window.jetlin = Jetlin.connect({ token: "${session.token}" });</script>
        </body>
        </html>
    """.trimIndent()
}
