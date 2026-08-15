package jetlin.server

import androidx.compose.runtime.Composable
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import jetlin.html.LiveView
import jetlin.runtime.FramePolicy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
 * Keeps compositions alive between the page render and the socket connecting.
 *
 * The initial HTTP response and the WebSocket are two separate requests, and the composition built
 * for the first is exactly the one the second wants. Holding it here for a few seconds means each
 * view is composed once per session rather than once per transport.
 *
 * Sessions are reaped on a timer: if no socket ever arrives, or a socket goes away and does not
 * come back within the grace period, the composition is closed and its memory released.
 *
 * This is also where hibernation belongs: instead of closing a disconnected session, capture its
 * saveable state, drop the composition, and store the snapshot under the same token so a reconnect
 * — possibly on another node — can restore it.
 */
public class SessionRegistry(
    private val scope: CoroutineScope,
    private val handoffTimeout: Duration = 30.seconds,
    private val disconnectGrace: Duration = 30.seconds,
) {
    private val sessions = ConcurrentHashMap<String, JetlinSession>()
    private val reapers = ConcurrentHashMap<String, Job>()
    private val random = SecureRandom()

    public val activeCount: Int get() = sessions.size

    public suspend fun create(
        framePolicy: FramePolicy = FramePolicy.Immediate,
        content: @Composable () -> Unit,
    ): JetlinSession {
        val session = JetlinSession(newToken(), LiveView(framePolicy, content))
        session.view.start()
        sessions[session.token] = session
        scheduleReap(session.token, handoffTimeout)
        return session
    }

    /** Claims a session for a connecting socket, cancelling any pending reap. */
    public fun attach(token: String): JetlinSession? {
        val session = sessions[token] ?: return null
        if (session.attached) return null
        session.attached = true
        reapers.remove(token)?.cancel()
        return session
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
            sessions.remove(token)?.takeIf { !it.attached }?.close()
        }
    }

    private fun newToken(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
