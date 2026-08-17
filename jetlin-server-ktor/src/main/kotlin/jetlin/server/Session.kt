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
) : AutoCloseable {

    /** Set while a socket is attached; guards against two sockets driving one composition. */
    internal var attached: Boolean = false

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
 * coroutines. What remains costs a small map instead of a live session, and with a shared store it
 * can be picked up by a different server than the one that created it — which is what lets a
 * rolling deploy happen without logging everyone out.
 */
public class SessionRegistry(
    private val scope: CoroutineScope,
    private val store: SessionStore = InMemorySessionStore(),
    private val framePolicy: FramePolicy = FramePolicy.Immediate,
    private val handoffTimeout: Duration = 30.seconds,
    private val disconnectGrace: Duration = 30.seconds,
    private val content: @Composable (RequestContext) -> Unit,
) {
    private val sessions = ConcurrentHashMap<String, JetlinSession>()
    private val reapers = ConcurrentHashMap<String, Job>()
    private val random = SecureRandom()

    public val liveCount: Int get() = sessions.size

    public suspend fun create(request: RequestContext): JetlinSession {
        val session = JetlinSession(newToken(), LiveView(request, framePolicy, emptyMap(), content))
        session.view.start()
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

        val snapshot = store.load(token) ?: return null
        store.remove(token)

        val restored = JetlinSession(
            token = token,
            view = LiveView(base.forUrl(url ?: snapshot.url), framePolicy, snapshot.state, content),
        )
        restored.view.start()
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
        val state = try {
            session.view.hibernate()
        } catch (t: Throwable) {
            // Capturing state can fail on a key collision, which is a bug in the application's
            // composables rather than anything this user did. The composition is already torn down
            // by then; losing the snapshot costs them their draft, and taking down the reaper
            // coroutine would cost every other session on this node its hibernation.
            logger.warn("Could not capture session state; it will not be restorable", t)
            return
        }
        // A session with nothing declared saveable has nothing to come back to. Storing it would
        // fill the store with entries whose only content is a URL the client already knows.
        if (state.isNotEmpty()) {
            store.save(session.token, SessionSnapshot(url, state))
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
