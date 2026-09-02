package jetlin.server

import androidx.compose.runtime.Composable
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
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

/** One user's live view plus the bookkeeping the transport needs around it. */
public class JetlinSession internal constructor(
    public val token: String,
    public val view: LiveView,
    adoptable: Boolean,
) : AutoCloseable {

    /** Set while a socket is attached; guards against two sockets driving one composition. */
    internal var attached: Boolean = false

    private var adoptable: Boolean = adoptable

    /**
     * Whether this socket may keep the markup the browser already has, asked once and answered once.
     *
     * True for exactly the first socket to reach a composition that rendered a page, because only
     * that browser is holding markup this composition produced. A reconnecting one is not: the
     * server stopped recording edits when the previous socket went away, so its tree has moved on in
     * ways the browser never saw. Deciding this here rather than trusting the client's request means
     * a confused or malicious one cannot talk the server into leaving it with a stale page.
     */
    internal fun claimAdoption(): Boolean {
        val allowed = adoptable
        adoptable = false
        return allowed
    }

    override fun close(): Unit = view.close()
}

/**
 * Owns the lifecycle of every session: live, briefly orphaned, hibernated, gone.
 *
 * A session passes through three states.
 *
 * **Live.** The composition is in memory and a socket is attached, or one is about to be — the page
 * render creates the session and the WebSocket that follows adopts it, so a view is composed once
 * per session rather than once per request.
 *
 * **Orphaned.** The socket has gone. The composition stays up for [disconnectGrace], because most
 * disconnections are a tunnel, a sleeping laptop or a flaky network, and reattaching to a running
 * composition is instant and lossless.
 *
 * **Hibernated.** The grace ran out. Whatever was declared with `rememberSaved` is written to the
 * [SessionStore] and the composition is destroyed, releasing its slot table, node tree and
 * coroutines. What remains costs a few hundred bytes instead of a live session.
 *
 * All of this assumes a single node. Only the hibernated state is ever written anywhere another
 * process could read it, so a second node could not pick up a session that is live, waiting for its
 * socket, or inside its grace period — a shared [SessionStore] alone would not change that.
 */
public class SessionRegistry(
    private val scope: CoroutineScope,
    private val store: SessionStore = InMemorySessionStore(),
    private val framePolicy: FramePolicy = FramePolicy.Immediate,
    private val handoffTimeout: Duration = 30.seconds,
    private val disconnectGrace: Duration = 30.seconds,
    private val exposeTestTags: Boolean = false,
    private val content: @Composable (RequestContext) -> Unit,
) {
    private val sessions = ConcurrentHashMap<String, JetlinSession>()
    private val reapers = ConcurrentHashMap<String, Job>()
    private val random = SecureRandom()

    public val liveCount: Int get() = sessions.size

    public suspend fun create(request: RequestContext): JetlinSession {
        val session = JetlinSession(
            token = newToken(),
            view = LiveView(request, framePolicy, emptyMap(), exposeTestTags, content),
            // This composition rendered the page the browser is about to receive, so the socket that
            // follows may adopt what it was served.
            adoptable = true,
        )
        // A view that throws while composing has still allocated a dispatcher and a recomposer.
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
     * Claims a session for a connecting socket, waking a hibernated one if necessary.
     *
     * [base] supplies whatever the socket's own request knows — headers, and any attributes the
     * application derives from them. On a restore that matters: the principal has to be recomputed
     * from the new connection rather than trusted from a snapshot that may be minutes old.
     *
     * [url] is where the client says it is, which beats the snapshot's idea of where the session
     * was: the user may have used the back button while disconnected.
     */
    public suspend fun attach(token: String, base: RequestContext, url: String?): JetlinSession? {
        sessions[token]?.let { live ->
            if (live.attached) return null
            live.attached = true
            reapers.remove(token)?.cancel()
            return live
        }

        // One atomic step, so concurrent reconnects for one token cannot both come away with the
        // snapshot. The loser sees null and falls through to the caller's unknown-session path.
        val snapshot = try {
            store.take(token)
        } catch (t: Throwable) {
            // A store that is unreachable should cost this reconnect, not the connection. Treating
            // it as a miss lands the client on a fresh session, which is what an expired snapshot
            // already does.
            logger.warn("Could not read stored session state; treating as a new session", t)
            null
        } ?: return null

        val restored = JetlinSession(
            token = token,
            view = LiveView(base.forUrl(url ?: snapshot.url), framePolicy, snapshot.state, exposeTestTags, content),
            // Composed afresh, so its node ids bear no relation to the data-jl values in whatever
            // markup the browser is still holding. It has to be sent the tree.
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

    /** Releases a session when its socket goes away, starting the grace period. */
    public fun detach(session: JetlinSession) {
        session.attached = false
        scheduleReap(session.token, disconnectGrace)
    }

    private fun scheduleReap(token: String, after: Duration) {
        reapers[token] = scope.launch {
            delay(after)
            reapers.remove(token)
            val session = sessions[token] ?: return@launch
            // Reattached while we were waiting; the new socket owns it now.
            if (session.attached) return@launch
            sessions.remove(token)
            hibernate(session)
        }
    }

    private suspend fun hibernate(session: JetlinSession) {
        val url = session.view.currentUrl
        try {
            val state = session.view.hibernate()
            // A session with nothing declared saveable has nothing to come back to. Storing it
            // would fill the store with entries whose only content is a URL the client already
            // knows.
            if (state.isNotEmpty()) {
                store.save(session.token, SessionSnapshot(url, state))
            }
        } catch (t: Throwable) {
            // Capturing can fail on a key collision, saving can fail on an unreachable store, and a
            // composition that already died reports that here. None of it is this user's doing, and
            // none of it may escape: the reaper runs in a coroutine shared with every other session,
            // so an exception thrown here would cost all of them their hibernation rather than just
            // this one.
            logger.warn("Could not store session state; it will not be restorable", t)
        } finally {
            // Belt and braces. hibernate() closes the view on its way out, but this is the promise
            // the registry makes — that a reaped session has released its composition — and it
            // should not rest on a detail of the layer below. close() is idempotent.
            session.close()
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(SessionRegistry::class.java)
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
