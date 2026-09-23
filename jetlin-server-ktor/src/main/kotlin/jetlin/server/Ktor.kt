package jetlin.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.path
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jetlin.html.Access
import jetlin.html.AttributeKey
import jetlin.html.Div
import jetlin.html.Guard
import jetlin.html.Guarded
import jetlin.html.H1
import jetlin.html.LocalRouteGuards
import jetlin.html.P
import jetlin.html.RequestContext
import jetlin.html.RouteGuards
import jetlin.html.RouteHost
import jetlin.html.RoutePattern
import jetlin.html.rootAttributes
import jetlin.html.Router
import jetlin.html.Subject
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

/** The configuration that [jetlin] builds. Set its properties and register views inside `jetlin { }`. */
public class JetlinConfig {
    /** How often each session may recompose. See [FramePolicy]. */
    public var framePolicy: FramePolicy = FramePolicy.Immediate

    /**
     * The origins allowed to open a session socket, written as full origins, such as
     * `https://app.example`.
     *
     * When the set is empty, only the same origin is allowed, checked against the request's `Host`
     * header. Set this when the browser loads the page from a different host than the one it opens
     * the socket to.
     */
    public var allowedOrigins: Set<String> = emptySet()

    /**
     * Markup added to every page's `<head>`.
     *
     * It applies to the whole application instead of to each view, because navigation replaces the
     * view inside a live page and never renders the document head again.
     */
    public var head: String = ""

    /** Where hibernated sessions are kept. For sessions to survive a restart, use a shared store. */
    public var sessionStore: SessionStore = InMemorySessionStore()

    /**
     * Whether to also write every `testTag` into the markup as a `data-test` attribute.
     *
     * It's off by default, because test tags are for tests, and writing them costs bytes on every
     * element for every user. Turn it on outside production so that browser tests, which can select
     * only what's in the DOM, have something to select.
     */
    public var exposeTestTags: Boolean = false

    /**
     * Markup placed after the runtime loads and before the session connects.
     *
     * Register client components here, because the order matters. The first thing a connection does
     * is adopt the markup it was served, and a component whose implementation isn't registered by
     * then renders nothing.
     *
     * ```kotlin
     * clientSetup = """<script src="/app.js"></script>"""
     * ```
     */
    public var clientSetup: String = ""

    /**
     * Called when a handler or a composition throws, before the client is told anything.
     *
     * Use it to send the exception to your error reporting. Jetlin logs it too, but a log line that
     * nobody reads isn't error handling.
     *
     * The browser receives only a fixed, generic message. An exception's text often contains parts
     * of queries, file paths, and identifiers, and none of that belongs in the client.
     */
    public var onError: (Throwable) -> Unit = { }

    /**
     * The maximum number of live sessions to hold at once.
     *
     * Every page render creates a session, about 136 kB in the demo sample, whether or not a socket
     * ever arrives to claim it. An unclaimed session is released only when the handoff timeout runs
     * out. Without a limit, a stream of unauthenticated requests would grow memory until the process
     * died.
     *
     * Set it from the measured cost of a session and the available heap. The default is chosen to
     * stay out of the way of real traffic, not to suit any particular deployment. At the limit, page
     * renders get a `503` response. Reconnects are still accepted, because someone reconnecting
     * already has a session.
     */
    public var maxSessions: Int = 10_000

    /**
     * The sustained number of messages per second that one connection may send. A value of `0` or
     * less turns the limit off.
     *
     * A person typing into a debounced field sends a few messages a second, and clicking sends
     * fewer, so the default is far above anything a person does. It stops a hostile or broken page
     * from driving recomposition in a loop and taking a share of the machine with it.
     */
    public var eventsPerSecond: Double = 50.0

    /**
     * How many messages one connection may send in a burst beyond [eventsPerSecond].
     *
     * Real use comes in bursts: filling in a form sends a flurry of events, then nothing. A limit
     * that can't absorb the flurry would have to be set so high that it stopped protecting anything.
     */
    public var eventBurst: Int = 100

    /**
     * How long a composition keeps running after its socket closes.
     *
     * The default is long enough that a browser coming out of a tunnel or a sleeping laptop
     * reconnects to a running composition. After the grace period, the session hibernates into
     * [sessionStore].
     */
    public var disconnectGrace: Duration = 30.seconds

    /** The registered views, in the order they were registered. */
    internal val views: MutableList<ViewRegistration> = mutableListOf()
    internal var attributeFactory: (suspend (ApplicationCall) -> Map<AttributeKey<*>, Any?>)? = null
    internal var appContainer: (@Composable (route: @Composable () -> Unit) -> Unit)? = null

    /**
     * Registers a view.
     *
     * @param path the route pattern. It can contain parameters, such as `/todo/{id}`, which the view
     *   reads with `pathParam("id")`.
     * @param title the document title, unless the view sets one with `DocumentTitle`.
     * @param requires the route's guard, such as requiring a signed-in user or a role. It's part of
     *   the route table instead of the view body, because by the time a check inside the body runs,
     *   the body has already started rendering. The guard also runs inside the composition, so if a
     *   role is revoked, the user is moved off the page they're on.
     * @param content the view.
     */
    public fun view(
        path: String,
        title: String = "Jetlin",
        requires: Guard? = null,
        content: @Composable () -> Unit,
    ) {
        views += ViewRegistration(RoutePattern(path), title, requires, content)
    }

    /**
     * Registers a view for a single record, which the route looks up itself.
     *
     * ```kotlin
     * view(
     *     "/todo/{id}",
     *     requires = Principals.signedIn,
     *     subject = { request -> with(Principals.of(request)!!) { Todos.find(db, Id(request.pathParam("id"))) } },
     *     title = { todo -> todo.title },
     * ) { todo -> TodoDetailPage(todo) }
     * ```
     *
     * This overload exists for security, not convenience. A `null` subject renders the not-found
     * page before the body composes, and the title is computed from the subject only after that, so
     * `<head>` can't reveal a record that the body refused to show. Because the view receives the
     * record instead of the path parameter, it can't look up an arbitrary ID by mistake.
     *
     * @param path the route pattern, such as `/todo/{id}`.
     * @param subject looks up the record from the request. Use the policy-checked lookup, which
     *   returns `null` for a record that this principal can't read.
     * @param title computes the document title from the record.
     * @param requires the route's guard. See the other `view` overload.
     * @param missingTitle the document title when [subject] returns `null`.
     * @param content the view. It receives the record.
     */
    public fun <T : Any> view(
        path: String,
        subject: (RequestContext) -> T?,
        title: (T) -> String,
        requires: Guard? = null,
        missingTitle: String = "Not found",
        content: @Composable (T) -> Unit,
    ) {
        views += ViewRegistration(RoutePattern(path), missingTitle, requires) {
            Subject(resolve = subject, title = title, content = content)
        }
    }

    /**
     * Computes the session's values from the HTTP call that created it.
     *
     * This is where an authenticated principal, a tenant, or a locale enters the composition. Views
     * read the values back through [RequestContext.get], with the keys the application declared. The
     * factory runs on the HTTP call, because that's the only point where Ktor's call context still
     * exists.
     *
     * The factory runs once when the page is rendered. It runs again only if a socket wakes a
     * hibernated session, because the principal then has to be recomputed from the new connection
     * instead of trusted from a snapshot that might be minutes old. A socket that reconnects to a
     * running composition doesn't call it, because that session already has its context. So the
     * factory can do real work, but it should be idempotent.
     */
    public fun attributes(factory: suspend (ApplicationCall) -> Map<AttributeKey<*>, Any?>) {
        attributeFactory = factory
    }

    /**
     * Wraps every view in a container that's composed once for the session. [content] decides where
     * the view goes.
     *
     * Navigation replaces the view inside a composition that stays alive, so this container is the
     * one place where `remember` outlives a page change:
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
     * Shared page chrome belongs here too, and the patches show why. Navigation rebuilds the view,
     * so a navigation bar composed inside each view is removed and inserted again on every
     * navigation. One composed here recomposes to the same markup and produces no changes.
     *
     * For state that must also survive hibernation, use `rememberSaved` instead of `remember`. That
     * works inside the container, because the container is never disposed. A view doesn't need the
     * container for this: what a view saves comes back when the user returns to it.
     *
     * Call `route` exactly once. If you don't call it, the application has no pages.
     */
    public fun app(content: @Composable (route: @Composable () -> Unit) -> Unit) {
        appContainer = content
    }
}

/** A view registered with [JetlinConfig.view]. */
internal class ViewRegistration(
    val pattern: RoutePattern,
    val title: String,
    val guard: Guard?,
    val content: @Composable () -> Unit,
)

/**
 * Installs Jetlin's HTTP and WebSocket endpoints.
 *
 * A `GET` request returns server-rendered HTML and a session token. A single WebSocket endpoint
 * takes over the session that the token names, then exchanges events for updates. A session is
 * bound to the whole route table instead of one view, which is what lets navigation replace views
 * inside the live composition instead of loading a new page.
 *
 * @param configure sets up the configuration and registers the views.
 */
public fun Application.jetlin(configure: JetlinConfig.() -> Unit) {
    val config = JetlinConfig().apply(configure)
    val router = Router(config.views.map { it.pattern to it })
    // Collect every route's guard, so links can be checked against the same rule as their route.
    val guards = RouteGuards(config.views.map { it.pattern to it.guard })
    install(WebSockets)
    // Log at most one line a minute. This warning fires at request rate, and a warning repeated ten
    // thousand times buries the one line someone needed to read.
    val capacityLog = LogThrottle(60_000_000_000L)

    val registry = SessionRegistry(
        scope = this,
        store = config.sessionStore,
        framePolicy = config.framePolicy,
        disconnectGrace = config.disconnectGrace,
        exposeTestTags = config.exposeTestTags,
        maxSessions = config.maxSessions,
    ) { current ->
        CompositionLocalProvider(LocalRouteGuards provides guards) {
            RouteHost(router, current, config.appContainer, { NotFound(it) }) { registration ->
                Guarded(registration.guard) { registration.content() }
            }
        }
    }

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
                // Check the guard before creating a session. A redirect costs only a response header,
                // while rendering a page for someone about to be redirected costs a whole composition.
                val access = registration.guard?.check(request) ?: Access.Allow
                if (access is Access.Redirect) {
                    call.respondRedirect(access.to)
                    return@get
                }

                val session = try {
                    registry.create(request)
                } catch (e: SessionLimitReachedException) {
                    // Refuse the request. Otherwise memory grows past what the heap can hold, which
                    // takes down everyone's session, not only this one. Reaching the limit isn't
                    // normal, so log it, but at most once a minute, with a count of the refusals.
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
                    // Prefer the title that the composition set. Only the composition knows what the
                    // route loaded and whether this principal may see it.
                    renderPage(config, session.view.title ?: registration.title, session),
                    ContentType.Text.Html,
                    // A route that refused this principal responds as if the page doesn't exist.
                    if (access == Access.NotFound) HttpStatusCode.NotFound else HttpStatusCode.OK,
                )
            }
        }

        webSocket("/jetlin") {
            // The browser's same-origin policy doesn't cover WebSockets, so a page on any site can
            // open one to this endpoint. Without this check, a hostile page that obtained a token
            // could drive a victim's session.
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
            // The socket's own request supplies the headers and the attributes derived from them, so
            // a session woken from storage recomputes its principal instead of trusting a stale one.
            val session = registry.attach(hello.token, { call.toRequestContext(config) }, hello.url)
            if (session == null) {
                sendMessage(ServerMessage.Error("Unknown or already-attached session", fatal = true))
                return@webSocket
            }

            // Declared outside the try, so the finally block can report the total.
            var dropped = 0L

            try {
                // The page render already built the composition. If the page that composition
                // rendered opened this socket, the browser holds that composition's markup and can
                // keep it. Anything that changed since then follows as a normal patch. Claim the
                // adoption whatever the client asked, so the right to adopt is used up either way.
                val mayAdopt = session.claimAdoption()
                sendMessage(if (mayAdopt && hello.adopt) session.view.adopt() else session.view.reset())

                val sender = launch {
                    session.view.messages.collect { sendMessage(it.withTitle(router)) }
                }

                // Check the budget before parsing anything, so a flood costs a clock read instead of
                // a JSON decode.
                val budget = TokenBucket(config.eventsPerSecond, config.eventBurst)
                var throttled = false

                for (frame in incoming) {
                    if (frame !is Frame.Text) continue

                    if (!budget.tryConsume()) {
                        // Drop the frame instead of queuing it. Queuing would only move the flood into
                        // memory, and it's better to tell the client it's going too fast. Tell it
                        // once per episode, because a page that ignores the first warning will ignore
                        // the next thousand.
                        dropped++
                        if (!throttled) {
                            throttled = true
                            // Identify the session well enough to find the page. A page sending faster
                            // than a person can click almost always has a loop in application
                            // JavaScript, and knowing which page is most of the work of fixing it. The
                            // token is truncated on purpose: it's a bearer credential, and a leaked log
                            // shouldn't allow a session takeover. Eight characters are enough to
                            // correlate log lines, but not enough to use.
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
                        // Frames come from a browser, which might not behave. Drop a frame that can't
                        // be read. Ending the session over it would let anyone end their own session
                        // with a malformed message, and would turn a protocol version mismatch into
                        // an outage instead of a warning.
                        logger.warn("Ignoring a frame that could not be read", t)
                        continue
                    }

                    try {
                        session.view.dispatch(message)
                    } catch (t: Throwable) {
                        config.onError(t)
                        if (session.view.isAlive) {
                            // A handler threw. The composition is intact and the page is still
                            // correct. Only this one interaction didn't happen, and only the client
                            // needs to know that.
                            logger.error("A handler failed while processing a client event", t)
                            sendMessage(ServerMessage.Error(HANDLER_FAILED, fatal = false))
                        } else {
                            // A composable threw, which stops the recomposer for good. Nothing this
                            // session does from now on can succeed, so say so, instead of leaving a
                            // page that looks live.
                            logger.error("The composition failed; the session cannot continue", t)
                            sendMessage(ServerMessage.Error(SESSION_FAILED, fatal = true))
                            break
                        }
                    }
                }
                sender.cancel()
            } finally {
                // Log the total once, on the way out. A connection can be throttled and recover many
                // times, so the per-episode lines say that it's happening, and this line says how
                // much it came to.
                if (dropped > 0) {
                    logger.warn(
                        "Dropped {} events for exceeding {}/s: session {} on {}",
                        dropped,
                        config.eventsPerSecond,
                        session.token.take(8),
                        session.view.currentUrl,
                    )
                }
                // Stop recording before releasing the session. The composition keeps running during
                // the grace period, and whoever reconnects receives the whole tree anyway.
                session.view.clientDetached()
                registry.detach(session)
            }
        }
    }
}

/** The logger for the server's endpoints. */
private val logger = LoggerFactory.getLogger("jetlin.server")

/**
 * The message a client gets when one of its events couldn't be handled.
 *
 * It deliberately says nothing about why. The exception is logged and passed to
 * [JetlinConfig.onError]. Its text is for whoever runs the server, not whoever is looking at the page.
 */
private const val HANDLER_FAILED: String = "That action could not be completed."

/** The message a client gets when it sends faster than [JetlinConfig.eventsPerSecond] allows. */
private const val TOO_FAST: String = "Too many messages; some were ignored."

/** The page served at the session limit. It's static, because rendering a view needs a session. */
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

/** The message a client gets when its session has stopped for good, and reloading is the only option. */
private const val SESSION_FAILED: String = "This session ended unexpectedly."

/** The page for a path that no route matches. */
@Composable
private fun NotFound(path: String) {
    Div({ classes("jl-not-found") }) {
        H1 { Text("Not found") }
        P { Text("No view is registered for $path") }
    }
}

/**
 * Adds the destination's title from the route table to a navigation that doesn't have one.
 *
 * Returns any other message unchanged.
 */
private fun ServerMessage.withTitle(router: Router<ViewRegistration>): ServerMessage =
    if (this is ServerMessage.Navigate && title == null) {
        copy(title = router.resolve(url.substringBefore('?'))?.value?.title)
    } else {
        this
    }

/** Builds the request context for this call, including the application's attributes. */
private suspend fun ApplicationCall.toRequestContext(config: JetlinConfig): RequestContext =
    RequestContext(
        path = request.path(),
        pathParams = parameters.names().associateWith { parameters[it].orEmpty() },
        queryParams = request.queryParameters.names().associateWith { request.queryParameters.getAll(it).orEmpty() },
        headers = request.headers.names().associateWith { request.headers.getAll(it).orEmpty() },
        attributes = config.attributeFactory?.invoke(this).orEmpty(),
    )

/** Receives the next client message, or returns `null` if the socket closed or sent a non-text frame. */
private suspend fun io.ktor.websocket.WebSocketSession.receiveMessage(): ClientMessage? {
    val frame = incoming.receiveCatching().getOrNull() as? Frame.Text ?: return null
    return JetlinJson.decodeFromString(ClientMessage.serializer(), frame.readText())
}

/** Sends [message] as a text frame. */
private suspend fun io.ktor.websocket.WebSocketSession.sendMessage(message: ServerMessage) {
    send(Frame.Text(JetlinJson.encodeToString(ServerMessage.serializer(), message)))
}

/**
 * Returns whether a socket may be opened from [origin].
 *
 * A missing `Origin` header means the caller isn't a browser, for example a test, a CLI, or a
 * service, and it's allowed. The header identifies the page that started the request, and only
 * browsers can be trusted to set it honestly. When [allowed] is empty, the origin must match the
 * request's own `Host` header, which is the same-origin case.
 */
internal fun originAllowed(origin: String?, host: String?, allowed: Set<String>): Boolean {
    if (origin == null) return true
    if (allowed.isNotEmpty()) return origin in allowed
    if (host == null) return false
    val originAuthority = origin.substringAfter("://", missingDelimiterValue = "")
    return originAuthority.isNotEmpty() && originAuthority == host
}

/**
 * Escapes [title] for use as the content of `<title>`.
 *
 * The title can come from record data, such as a todo's title in a route for one record, so it's user
 * input. Unescaped, a title like `</title><script>…` would inject markup into every page render.
 */
private fun escapeTitle(title: String): String = buildString(title.length) {
    for (c in title) when (c) {
        '&' -> append("&amp;")
        '<' -> append("&lt;")
        '>' -> append("&gt;")
        else -> append(c)
    }
}

/** Renders the full HTML page for [session], with the runtime script and the session token. */
private suspend fun renderPage(config: JetlinConfig, title: String, session: JetlinSession): String {
    val body = session.view.renderHtml()
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${escapeTitle(title)}</title>
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
