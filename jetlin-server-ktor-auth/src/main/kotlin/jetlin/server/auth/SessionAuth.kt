package jetlin.server.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.OptionalPrincipalContext
import io.ktor.server.auth.SessionAuthenticationScheme
import io.ktor.server.auth.UnauthorizedHandler
import io.ktor.server.auth.authenticateWithOptional
import io.ktor.server.auth.clearSession
import io.ktor.server.auth.principalOrNull
import io.ktor.server.auth.setSession
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.uri
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.sessions.sessionId
import io.ktor.server.sessions.sessions
import io.ktor.util.AttributeKey as KtorAttributeKey
import io.ktor.utils.io.ExperimentalKtorApi
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import jetlin.html.AttributeKey
import jetlin.html.LocalNavigator
import jetlin.server.JetlinConfig
import jetlin.server.jetlinSessions
import jetlin.server.originAllowed
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Signs the browser in and out from an event handler.
 *
 * A handler runs over the session's WebSocket, which can't set a cookie. So each call hands the
 * browser to an HTTP endpoint with a real page load. The endpoint sets or clears the cookie and
 * redirects to `next`. The page the user is on goes away, and the destination starts a new session
 * as whoever the browser is now.
 *
 * Get one with [SessionAuth.rememberControls]. It's an interface so that tests of a sign-in page can
 * record the calls instead of performing them.
 *
 * @param S the application's session type, which the cookie refers to by ID.
 */
public interface SessionControls<S : Any> {
    /**
     * Signs the browser in as [session], and then goes to [next].
     *
     * The browser gets a new session ID, even if it already had one. Keeping an ID across sign-in
     * would let anyone who planted it before sign-in, for example on a shared computer, use it
     * afterward.
     *
     * @param next a path on this site, such as the `next` query parameter of the sign-in page.
     *   Anything else, such as another site's URL, is replaced with `/`.
     */
    public fun signIn(session: S, next: String = "/")

    /**
     * Signs the browser out, and then goes to [next].
     *
     * Every live session of this browser ends, not only the one that called this, so another tab
     * can't go on acting as the user who signed out. Those tabs reload as whoever the browser is now.
     */
    public fun signOut(next: String = "/")
}

/**
 * Jetlin's side of a Ktor typed session scheme whose cookie holds only a session ID.
 *
 * [authentication] creates it. It issues the one-time tickets that [SessionControls] hands to the
 * browser, and it's what the sign-in endpoint redeems them against. Tickets live in this process,
 * like live sessions do, so a ticket issued on one node can't be redeemed on another.
 *
 * @param S the application's session type.
 */
@ExperimentalKtorApi
public class SessionAuth<S : Any> internal constructor(
    private val scheme: SessionAuthenticationScheme<S, *>,
    private val sessionType: KClass<S>,
    private val ticketLifetime: Duration,
) {
    private val tickets = ConcurrentHashMap<String, Pending<S>>()
    private val random = SecureRandom()

    /**
     * Returns the controls for the session that's composing this.
     *
     * Call it in a composable, and call the controls from a handler:
     *
     * ```kotlin
     * val controls = auth.rememberControls()
     * Button({ onClick { controls.signIn(AppSession(user.id), next = queryParam("next") ?: "/") } }) {
     *     Text("Sign in")
     * }
     * ```
     */
    @Composable
    public fun rememberControls(): SessionControls<S> {
        val navigator = LocalNavigator.current
        return remember(navigator) {
            object : SessionControls<S> {
                override fun signIn(session: S, next: String) =
                    navigator.load(AUTH_PATH, mapOf(TICKET_FIELD to issue(Pending.SignIn(session, next, deadline()))))

                override fun signOut(next: String) =
                    navigator.load(AUTH_PATH, mapOf(TICKET_FIELD to issue(Pending.SignOut(next, deadline()))))
            }
        }
    }

    /**
     * Ends the live sessions of the browser that made [call], and returns how many there were.
     *
     * Call it from a sign-out route of your own, before clearing the session. Signing out through
     * [SessionControls] does it for you.
     */
    public fun endSessions(call: ApplicationCall): Int {
        val binding = bindingOf(call) ?: return 0
        return call.application.jetlinSessions.endSessions(binding)
    }

    /**
     * Returns what binds a Jetlin session to the browser that made [call]: a hash of the cookie's
     * session ID, or `null` if the browser has no valid session.
     *
     * It's a hash because hibernated sessions keep it in the session store, and the ID itself is a
     * credential.
     */
    internal fun bindingOf(call: ApplicationCall): String? = call.sessionId(sessionType)?.let(::sha256)

    /** Redeems a ticket: sets or clears the session cookie, and redirects. */
    internal suspend fun RoutingContext.redeem() {
        // A form post from another site carries its own Origin. Refusing it stops login CSRF, where
        // an attacker signs a victim in to the attacker's account and watches what they enter.
        val fetchSite = call.request.headers["Sec-Fetch-Site"]
        val sameOrigin = (fetchSite == null || fetchSite == "same-origin") &&
            originAllowed(call.request.headers[HttpHeaders.Origin], call.request.headers[HttpHeaders.Host], emptySet())
        if (!sameOrigin) {
            call.respondText("Cross-origin sign-in refused", status = HttpStatusCode.Forbidden)
            return
        }

        val ticket = call.receiveParameters()[TICKET_FIELD]
        // Remove before checking, so a ticket is spent by its first use whatever the outcome.
        val pending = ticket?.let { tickets.remove(it) }
        if (pending == null || pending.expiresAt.hasPassedNow()) {
            // An old tab, a double submit, or a guess. None of them deserves more than a fresh start.
            call.seeOther("/")
            return
        }

        // End the old identity's sessions whichever way this goes. When Alice switches to Bob, a tab
        // still showing Alice's data must not go on as Alice with Bob's cookie.
        val oldId = call.sessionId(sessionType)
        if (oldId != null) {
            call.application.jetlinSessions.endSessions(sha256(oldId))
            call.sessions.clear(scheme.name, oldId)
        }

        when (pending) {
            is Pending.SignIn -> {
                // Ktor reuses the ID that the request arrived with, so forget it and let the store
                // generate a new one. Otherwise, whoever planted the old ID would be signed in too.
                call.attributes.remove(KtorSessionIdKey)
                scheme.setSession(pending.session)
            }
            is Pending.SignOut -> scheme.clearSession()
        }
        call.seeOther(localPath(pending.next))
    }

    /** Stores [pending] under a new ticket, and returns the ticket. */
    private fun issue(pending: Pending<S>): String {
        // Sweep on write, like the session store does. Only issuing grows the map.
        tickets.entries.removeAll { it.value.expiresAt.hasPassedNow() }
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        tickets[ticket] = pending
        return ticket
    }

    private fun deadline(): TimeMark = TimeSource.Monotonic.markNow() + ticketLifetime

    /** What a ticket does when it's redeemed. */
    private sealed interface Pending<out S> {
        val next: String
        val expiresAt: TimeMark

        class SignIn<S>(val session: S, override val next: String, override val expiresAt: TimeMark) : Pending<S>
        class SignOut(override val next: String, override val expiresAt: TimeMark) : Pending<Nothing>
    }

    internal companion object {
        /** The endpoint that redeems tickets. */
        const val AUTH_PATH: String = "/jetlin/auth"

        /** The form field that carries the ticket. */
        const val TICKET_FIELD: String = "ticket"

        /**
         * The call attribute where Ktor's `SessionTrackerById` keeps the session ID that the request
         * arrived with. Its own key is internal, but attribute keys are compared by name and type, so
         * this one is equal to it as long as both are `String`-typed. A test checks that sign-in still
         * produces a new ID, which would catch Ktor renaming or retyping it.
         */
        val KtorSessionIdKey: KtorAttributeKey<String> = KtorAttributeKey("SessionId")
    }
}

/**
 * Signs browsers in with [scheme], and supplies its principal to Jetlin's views under [principal].
 *
 * Install the scheme first, so that the `Sessions` plugin can read the cookie:
 *
 * ```kotlin
 * val sessionAuth = session<AppSession, User>("app") {
 *     transport = SessionTransportType.CookieId(storage) {
 *         cookie.path = "/"
 *         cookie.httpOnly = true
 *         cookie.extensions["SameSite"] = "Lax"
 *     }
 *     validate { session -> users.find(session.userId) }
 * }
 * install(sessionAuth)
 *
 * jetlin {
 *     val auth = authentication(sessionAuth, principal = PrincipalKey)
 *     view("/login") { SignInPage(auth.rememberControls()) }
 *     view("/", requires = Principals.signedIn) { HomePage() }
 * }
 * ```
 *
 * It does three things:
 *
 * - It puts Jetlin's page renders and socket under `authenticateWithOptional(scheme)`, and supplies
 *   `principalOrNull` as the session's principal. Authentication is optional because route guards
 *   decide who may see what. A browser whose session no longer validates, for example because its
 *   user was deleted, has the cookie cleared and is sent back to the same URL as nobody.
 * - It binds every Jetlin session token to the cookie's session ID, so a token that leaks from a page
 *   is no use without the browser's cookie.
 * - It registers `POST /jetlin/auth`, which redeems the tickets that [SessionControls] issues.
 *
 * @param scheme the typed session scheme. Its transport should be `CookieId`, so the cookie holds only
 *   an ID and the session stays on the server.
 * @param principal the attribute key that route guards and views read the principal from.
 * @param ticketLifetime how long a sign-in or sign-out ticket can be redeemed for. The browser redeems
 *   it right away, so this only needs to cover a slow page load.
 * @return the handle that views get [SessionControls] from.
 */
@ExperimentalKtorApi
public inline fun <reified S : Any, P : Any> JetlinConfig.authentication(
    scheme: SessionAuthenticationScheme<S, P>,
    principal: AttributeKey<P?>,
    ticketLifetime: Duration = 60.seconds,
): SessionAuth<S> = authentication(scheme, S::class, principal, ticketLifetime)

@PublishedApi
@ExperimentalKtorApi
internal fun <S : Any, P : Any> JetlinConfig.authentication(
    scheme: SessionAuthenticationScheme<S, P>,
    sessionType: KClass<S>,
    principal: AttributeKey<P?>,
    ticketLifetime: Duration,
): SessionAuth<S> {
    val auth = SessionAuth(scheme, sessionType, ticketLifetime)
    // Ktor exposes the principal only through a context that exists inside the route builder.
    // Capture it there. Routes are built before the first request, so it's always set by then.
    var principalContext: OptionalPrincipalContext<P>? = null

    routes { jetlinRoutes ->
        authenticateWithOptional(scheme, onUnauthorized = UnauthorizedHandler { forgetStaleSession(scheme) }) {
            principalContext = captured()
            jetlinRoutes()
        }
        // Outside the authenticated route: a stale cookie mustn't stop anyone from signing in.
        post(SessionAuth.AUTH_PATH) { with(auth) { redeem() } }
    }
    principal(principal) { call ->
        with(checkNotNull(principalContext) { "Jetlin's routes were not built" }) { call.principalOrNull }
    }
    bindSessions { call -> auth.bindingOf(call) }
    return auth
}

/** Returns the principal context that the route builder provides. */
context(context: OptionalPrincipalContext<P>)
private fun <P : Any> captured(): OptionalPrincipalContext<P> = context

/**
 * Clears a session that no longer validates, and sends the browser on as nobody.
 *
 * Ktor's default is `401` on every request until the cookie expires, which leaves a browser stuck
 * on an error for something only the server can fix. A socket can't follow a redirect, so it gets
 * the `401`, and the cleared cookie makes its next attempt anonymous.
 */
@ExperimentalKtorApi
private suspend fun <S : Any, P : Any> RoutingContext.forgetStaleSession(scheme: SessionAuthenticationScheme<S, P>) {
    scheme.clearSession()
    if (call.request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)) {
        call.respond(HttpStatusCode.Unauthorized)
    } else {
        call.respondRedirect(call.request.uri)
    }
}

/** Redirects with `303 See Other`, which tells the browser to follow a form post with a `GET`. */
private suspend fun ApplicationCall.seeOther(location: String) {
    response.headers.append(HttpHeaders.Location, location)
    respond(HttpStatusCode.SeeOther)
}

/**
 * Returns [next] if it's a path on this site, or `/` if it isn't.
 *
 * `next` comes from a query parameter, so anyone can write a link that sets it. Without this check,
 * the sign-in page would be an open redirect: a trusted domain that sends people wherever a link
 * says, just after they've entered their password.
 */
internal fun localPath(next: String): String {
    val local = next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") &&
        next.none { it < ' ' || it == '\u007f' }
    return if (local) next else "/"
}

/** Returns the SHA-256 of [value], Base64url-encoded. */
private fun sha256(value: String): String =
    Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray()))
