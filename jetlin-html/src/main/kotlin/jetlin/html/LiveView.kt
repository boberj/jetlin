package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import jetlin.protocol.ClientMessage
import jetlin.protocol.ServerMessage
import jetlin.runtime.CompositionHost
import jetlin.runtime.FramePolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One live server-side view: a composition, its virtual DOM, its current location, and the messages
 * it produces for the browser.
 *
 * Transport-agnostic on purpose — this class knows nothing about WebSockets or Ktor, so it can be
 * driven directly from a test without a browser or a server in the loop.
 */
public class LiveView(
    initialRequest: RequestContext = RequestContext(path = "/"),
    framePolicy: FramePolicy = FramePolicy.Immediate,
    private val content: @Composable (RequestContext) -> Unit,
) : AutoCloseable {

    public val owner: HtmlOwner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner), framePolicy)

    /**
     * The location this session is currently showing.
     *
     * Compose state, so changing it recomposes whatever reads it — which is the whole of navigation:
     * the router re-resolves, the matched view swaps, and the applier records the difference.
     */
    private var request by mutableStateOf(initialRequest)

    private var rev = 0L

    /** Highest client event sequence whose effects are folded into the next patch. */
    @Volatile
    private var ack = 0L

    /** Navigations waiting to be sent, ordered behind the patch that renders them. */
    private val pendingNavigations = ArrayDeque<ServerMessage.Navigate>()

    public val currentUrl: String get() = request.url

    private val navigator = object : Navigator {
        override fun push(url: String): Unit = goto(url, replace = false, notifyClient = true)
        override fun replace(url: String): Unit = goto(url, replace = true, notifyClient = true)
    }

    /** Composes the initial tree. Ops from the first pass are dropped: first paint ships as HTML. */
    public suspend fun start() {
        host.setContent {
            CompositionLocalProvider(
                LocalHtmlOwner provides owner,
                LocalNavigator provides navigator,
                LocalRequest provides request,
            ) {
                content(request)
            }
        }
        host.confined { owner.drainOps() }
    }

    /** Server-rendered HTML for the initial page load. */
    public suspend fun renderHtml(): String = host.confined { renderToHtml(owner) }

    /**
     * The whole tree as a single message, for a client attaching or rejoining.
     *
     * Buffered ops are discarded first. A composition keeps running while no client is attached, so
     * by reconnect time the buffer holds mutations describing a tree the arriving client has never
     * seen. The full snapshot subsumes them, and replaying them on top of it would apply indices
     * twice.
     */
    public suspend fun reset(): ServerMessage.Reset = host.confined {
        owner.drainOps()
        pendingNavigations.clear()
        ServerMessage.Reset(++rev, owner.snapshotChildren())
    }

    /**
     * Applies one client message and waits for the resulting recomposition to settle. Anything it
     * produced leaves through [messages]; keeping a single writer avoids two coroutines splitting
     * one logical update between two frames.
     */
    public suspend fun dispatch(message: ClientMessage) {
        when (message) {
            is ClientMessage.Event -> {
                ack = message.seq
                host.transact { owner.dispatch(message.node, message.event, message.payload) }
            }
            // The browser already moved; follow it without telling it to move again.
            is ClientMessage.Navigate -> host.transact { goto(message.url, replace = false, notifyClient = false) }
            is ClientMessage.Hello -> Unit
        }
    }

    private fun goto(url: String, replace: Boolean, notifyClient: Boolean) {
        if (url == request.url) return
        request = request.forUrl(url)
        if (notifyClient) {
            pendingNavigations.addLast(ServerMessage.Navigate(url, replace))
            // A route that renders identically produces no ops, and without this the sender would
            // never wake and the address bar would stay behind.
            owner.signalDirty()
        }
    }

    /**
     * Everything this view wants to send, whatever caused it — a client event, a `LaunchedEffect`,
     * or a background coroutine writing shared state.
     *
     * Sending updates to the client needs no separate API: when state a composable read changes,
     * that composable recomposes, the applier records ops, and they arrive here. A navigation is
     * emitted after the patch that rendered its destination, so the address bar never runs ahead of
     * the content.
     */
    public val messages: Flow<ServerMessage> = flow {
        for (signal in owner.dirtySignals) {
            host.awaitIdle()
            val batch = host.confined {
                val ops = owner.drainOps()
                buildList {
                    if (ops.isNotEmpty()) add(ServerMessage.Patch(++rev, ack, ops))
                    while (pendingNavigations.isNotEmpty()) add(pendingNavigations.removeFirst())
                }
            }
            batch.forEach { emit(it) }
        }
    }

    override fun close(): Unit = host.close()
}
