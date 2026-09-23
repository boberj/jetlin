package jetlin.server

import androidx.compose.runtime.Composable
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import jetlin.html.LiveView
import jetlin.html.RequestContext
import jetlin.runtime.FramePolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Thrown when a page render would take the number of live sessions past the configured limit.
 *
 * It's public because an application that uses [SessionRegistry] directly has to decide how to
 * handle it. The Ktor integration turns it into a `503` response with a `Retry-After` header.
 *
 * @property limit the configured maximum number of live sessions.
 */
public class SessionLimitReachedException internal constructor(
    public val limit: Int,
) : RuntimeException("Refusing to create a session: already at the limit of $limit")

/**
 * One user's live view, and the bookkeeping the transport needs around it.
 *
 * @property token the secret that identifies the session. Anyone who has it can drive the session.
 * @property view the session's view.
 * @param adoptable whether the next socket may keep the markup the browser already has. See
 *   [claimAdoption].
 */
public class JetlinSession internal constructor(
    public val token: String,
    public val view: LiveView,
    adoptable: Boolean,
) : AutoCloseable {

    /** Whether a socket is attached. It keeps two sockets from driving one composition. */
    internal var attached: Boolean = false

    private var adoptable: Boolean = adoptable

    /**
     * Returns whether this socket may keep the markup the browser already has. Only the first call
     * can return `true`.
     *
     * It's `true` only for the first socket to reach a composition that rendered a page, because only
     * that browser holds markup this composition produced. A reconnecting socket gets `false`: the
     * server stopped recording changes when the previous socket went away, so the tree has changed
     * in ways the browser never saw. Deciding here, instead of trusting the client's request, means a
     * confused or malicious client can't talk the server into leaving it with a stale page.
     */
    internal fun claimAdoption(): Boolean {
        val allowed = adoptable
        adoptable = false
        return allowed
    }

    /** Shuts down the session's composition without saving anything. */
    override fun close(): Unit = view.close()
}

/**
 * Manages every session through its life: live, briefly orphaned, hibernated, and gone.
 *
 * A session passes through three states:
 *
 * - Live: the composition is in memory, and a socket is attached or about to be. The page render
 *   creates the session, and the WebSocket that follows adopts it, so a view is composed once per
 *   session instead of once per request.
 * - Orphaned: the socket has closed. The composition keeps running for [disconnectGrace], because
 *   most disconnections are a tunnel, a sleeping laptop, or a flaky network, and reconnecting to a
 *   running composition is instant and loses nothing.
 * - Hibernated: the grace period ran out. Whatever was declared with `rememberSaved` is written to
 *   the [SessionStore], and the composition is destroyed, which releases its slot table, node tree,
 *   and coroutines. What's left costs a few hundred bytes instead of a live session.
 *
 * The number of live sessions is limited. Every page render creates one, whether or not a socket
 * ever arrives for it, so without a limit a stream of unauthenticated requests would grow memory
 * until the process died. The limit turns that into refusals, which is worse for whoever is refused
 * and much better for everybody else. Reconnects are deliberately not limited: someone reconnecting
 * already had a session, and turning them away to make room for new visitors is the wrong trade.
 *
 * All of this assumes a single node. Only hibernated state is ever written where another process
 * could read it. A second node couldn't pick up a session that's live, waiting for its socket, or
 * in its grace period, and a shared [SessionStore] alone wouldn't change that.
 *
 * @param scope the scope that timers for handoff and grace periods run in.
 * @param store where hibernated sessions are kept.
 * @param framePolicy how often each session may recompose.
 * @param handoffTimeout how long a new session waits for its first socket before it hibernates.
 * @param disconnectGrace how long a session keeps running after its socket closes.
 * @param exposeTestTags whether to also write test tags as `data-test` attributes.
 * @param maxSessions the maximum number of live sessions. See [create].
 * @param content the content of every session. It receives the current request.
 */
public class SessionRegistry(
    private val scope: CoroutineScope,
    private val store: SessionStore = InMemorySessionStore(),
    private val framePolicy: FramePolicy = FramePolicy.Immediate,
    private val handoffTimeout: Duration = 30.seconds,
    private val disconnectGrace: Duration = 30.seconds,
    private val exposeTestTags: Boolean = false,
    private val maxSessions: Int = 10_000,
    private val content: @Composable (RequestContext) -> Unit,
) {
    private val sessions = ConcurrentHashMap<String, JetlinSession>()
    private val reapers = ConcurrentHashMap<String, Job>()
    private val random = SecureRandom()
    private val rejected = AtomicLong()

    /** The number of live sessions, including orphaned ones in their grace period. */
    public val liveCount: Int get() = sessions.size

    /** The number of page renders refused because [maxSessions] was reached. */
    public val rejectedCount: Long get() = rejected.get()

    /**
     * Creates a session for a page render and composes it, unless there are already too many.
     *
     * The limit is approximate. Two requests that arrive together can both see room and both take
     * it, so the real maximum is [maxSessions] plus the number of renders in progress. That's
     * deliberate. An exact limit would put every page render behind one lock, to prevent an overshoot
     * that's bounded and harmless.
     *
     * @throws SessionLimitReachedException if there are already [maxSessions] live sessions.
     */
    public suspend fun create(request: RequestContext): JetlinSession {
        if (sessions.size >= maxSessions) {
            rejected.incrementAndGet()
            throw SessionLimitReachedException(maxSessions)
        }
        val session = JetlinSession(
            token = newToken(),
            view = LiveView(request, framePolicy, emptyMap(), exposeTestTags, content),
            // This composition renders the page the browser is about to receive, so the socket that
            // follows may adopt the markup it was served.
            adoptable = true,
        )
        // A view that throws while composing has already created a dispatcher and a recomposer.
        // Closing it here keeps a failing page from leaking a thread per request.
        try {
            session.view.start()
        } catch (t: Throwable) {
            session.close()
            throw t
        }
        sessions[session.token] = session
        scheduleReap(session.token, handoffTimeout)
        return session
    }

    /**
     * Claims a session for a connecting socket, and wakes it from the store if it's hibernated.
     *
     * @param token the token the client sent.
     * @param base returns what the socket's own request knows: its headers, and the attributes the
     *   application derives from them. When a session wakes, the principal has to be recomputed from
     *   the new connection instead of trusted from a snapshot that might be minutes old. It's a
     *   function because most calls never need it. A socket reconnecting to a running composition
     *   keeps the context it already has. Computing one only to discard it would run the
     *   application's `attributes` factory, which might query a database or a directory, on every
     *   reconnect for nothing.
     * @param url where the client says it is. It takes precedence over the location in the snapshot,
     *   because the user might have pressed the back button while disconnected.
     * @return the session, or `null` if the token is unknown or another socket is already attached.
     */
    public suspend fun attach(
        token: String,
        base: suspend () -> RequestContext,
        url: String?,
    ): JetlinSession? {
        sessions[token]?.let { live ->
            if (live.attached) return null
            live.attached = true
            reapers.remove(token)?.cancel()
            return live
        }

        // Take the snapshot in one atomic step, so two concurrent reconnects with one token can't
        // both get it. The loser sees null and gets the caller's unknown-session response.
        val snapshot = try {
            store.take(token)
        } catch (t: Throwable) {
            // An unreachable store should cost this reconnect, not the connection. Treating it as a
            // miss gives the client a new session, which is what an expired snapshot does anyway.
            logger.warn("Could not read stored session state; treating as a new session", t)
            null
        } ?: return null

        val restored = JetlinSession(
            token = token,
            view = LiveView(base().forUrl(url ?: snapshot.url), framePolicy, snapshot.state, exposeTestTags, content),
            // This composition is new, so its node IDs have nothing to do with the data-jl values in
            // the markup the browser holds. The client has to receive the tree.
            adoptable = false,
        )
        try {
            restored.view.start()
        } catch (t: Throwable) {
            restored.close()
            throw t
        }
        restored.attached = true
        sessions[token] = restored
        return restored
    }

    /** Releases a session when its socket closes, and starts the grace period. */
    public fun detach(session: JetlinSession) {
        session.attached = false
        scheduleReap(session.token, disconnectGrace)
    }

    /** Hibernates the session [after] the given time, unless a socket attaches first. */
    private fun scheduleReap(token: String, after: Duration) {
        reapers[token] = scope.launch {
            delay(after)
            reapers.remove(token)
            val session = sessions[token] ?: return@launch
            // A socket attached during the wait, and it owns the session now.
            if (session.attached) return@launch
            sessions.remove(token)
            hibernate(session)
        }
    }

    /** Saves the session's state to the store, if it has any, and closes the session. */
    private suspend fun hibernate(session: JetlinSession) {
        val url = session.view.currentUrl
        try {
            val state = session.view.hibernate()
            // A session with no saved values has nothing to come back to. Storing it would fill the
            // store with entries that hold only a URL the client already knows.
            if (state.isNotEmpty()) {
                store.save(session.token, SessionSnapshot(url, state))
            }
        } catch (t: Throwable) {
            // Capturing can fail on a key collision, saving can fail on an unreachable store, and a
            // composition that already died reports its failure here. None of it is this user's
            // fault, and none of it may escape. The reaper runs in a scope shared with every other
            // session, so an exception thrown here would cost all of them their hibernation, not
            // only this one.
            logger.warn("Could not store session state; it will not be restorable", t)
        } finally {
            // LiveView.hibernate() already closes the view, but the registry promises that a
            // reaped session has released its composition, and that promise shouldn't depend on a
            // detail of the layer below. close() is idempotent.
            session.close()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SessionRegistry::class.java)
    }

    /** Returns a new random session token: 24 bytes from a secure random source, Base64-encoded. */
    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
