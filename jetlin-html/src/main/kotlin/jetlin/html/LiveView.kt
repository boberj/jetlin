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
import jetlin.runtime.LocalSaveableStateRegistry
import jetlin.runtime.SaveableStateRegistry
import jetlin.runtime.rememberSaved
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

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
    restored: Map<String, JsonElement> = emptyMap(),
    /** Writes `testTag` out as `data-test` as well, so browser tests can select on it. */
    private val exposeTestTags: Boolean = false,
    private val content: @Composable (RequestContext) -> Unit,
) : AutoCloseable {

    public val owner: HtmlOwner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner), framePolicy)

    /** Holds state that survives this composition being torn down; see [rememberSaved]. */
    private val stateRegistry = SaveableStateRegistry(restored)

    private val titleState = mutableStateOf<String?>(null)

    /**
     * The document title the composition asked for, or null if it asked for nothing.
     *
     * Read by whatever renders the page, *after* the first composition has settled. It comes from the
     * composition rather than from the route table because a route's title can depend on what the route
     * resolved — and a title derived from a row that the principal may not read would disclose it in
     * `<head>`, which is rendered before the body that refused to show it.
     */
    public val title: String? get() = titleState.value

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
                LocalSaveableStateRegistry provides stateRegistry,
                LocalTestTagsExposed provides exposeTestTags,
                LocalDocumentTitle provides TitleSink { titleState.value = it },
            ) {
                content(request)
            }
        }
        // Settled rather than merely applied, and for a reason that only shows up later. The page is
        // rendered from this tree after start returns, and a socket that adopts that page keeps every
        // op recorded after the drain below — see adopt(). An effect that runs after the drain but
        // before the render would therefore reach the browser twice: once in the markup, once as a
        // patch, and an insert applied twice is a corrupt page. Letting effects that were already
        // queued run first folds them into the ops thrown away here. Bounded, so that an effect that
        // never lets the session settle delays a first render instead of preventing it.
        host.awaitIdle(effectsBudget = START_EFFECTS_BUDGET)
        host.confined { owner.drainOps() }
    }

    /**
     * Whether the composition behind this view is still running.
     *
     * False after a composable threw. Everything else — a handler that failed, a store that was
     * unreachable — leaves the view usable, and telling the two apart is what decides whether a
     * client is told its click failed or its session is gone.
     */
    public val isAlive: Boolean get() = host.isAlive

    /** Server-rendered HTML for the initial page load. */
    public suspend fun renderHtml(): String = host.confined { renderToHtml(owner) }

    /**
     * Reads the node tree once it has settled, on the thread that owns it.
     *
     * The tree belongs to the composition and is mutated by the applier on a confined dispatcher,
     * so anything examining it — a test asserting on what was rendered, a debug endpoint — has to
     * do so from there rather than from whatever thread it happens to be on.
     */
    public suspend fun <T> inspect(block: (HtmlOwner) -> T): T {
        awaitIdle()
        return host.confined { block(owner) }
    }

    /**
     * Attributes for the element [renderHtml]'s output is placed inside.
     *
     * The container is the root of the tree, but its markup is written by the page shell rather than
     * by the serializer, so its identity has to be handed over separately.
     */
    public suspend fun rootAttributes(): String = host.confined { rootAttributes(owner) }

    /**
     * Suspends until the view has settled: every pending recomposition applied, effects that were
     * already queued run, and state written from outside a snapshot taken into account.
     *
     * Needed by anything driving a view without a browser — a test, a renderer, a screenshot tool —
     * to know that state written from outside has finished taking effect. Waits as long as it takes;
     * an effect that never lets the view settle is a bug, and a test is the right place to hang on it.
     */
    public suspend fun awaitIdle(): Unit = host.awaitIdle()

    /**
     * The whole tree as a single message, for a client attaching or rejoining.
     *
     * Buffered ops are discarded first. A composition keeps running while no client is attached, so
     * by reconnect time the buffer holds mutations describing a tree the arriving client has never
     * seen. The full snapshot subsumes them, and replaying them on top of it would apply indices
     * twice.
     */
    public suspend fun reset(): ServerMessage.Reset = host.confined {
        pendingNavigations.clear()
        owner.startRecording()
        ServerMessage.Reset(++rev, owner.snapshotChildren())
    }

    /**
     * Accepts a client that has indexed the server-rendered markup instead of sending it the tree.
     *
     * The buffer is deliberately left alone, which is the whole difference from [reset]. This
     * composition has been live since the HTML was rendered, so anything that happened in between —
     * a `LaunchedEffect` firing, a shared store changing — is sitting in that buffer, and it is
     * exactly the delta between the markup the browser holds and the tree as it now stands. Clearing
     * it would leave the two quietly out of step.
     */
    public suspend fun adopt(): ServerMessage.Ready = host.confined {
        pendingNavigations.clear()
        ServerMessage.Ready(++rev)
    }

    /**
     * Tells the view that nobody is listening any more.
     *
     * The composition stays alive — a reconnecting client should find its session where it left it —
     * but edits stop being recorded, because the next client to attach is sent the whole tree
     * regardless. Without this, a session with a running timer would accumulate updates for a page
     * that will never be shown.
     */
    public suspend fun clientDetached(): Unit = host.confined { owner.stopRecording() }

    /**
     * Captures the state worth keeping, then shuts the composition down.
     *
     * This is what makes an idle session cheap: the slot table, the node tree and the coroutines
     * all go away, and what remains is a map small enough to hold in memory for thousands of
     * sessions, or to write somewhere another server can read. The view is unusable afterwards.
     *
     * Only values registered through [rememberSaved] survive. Everything in `remember` is
     * deliberately not captured — it is scratch space, and recomputing it is the point.
     */
    public suspend fun hibernate(): Map<String, JsonElement> {
        // Closed whatever happens, and that includes the wait: a composition that has already died
        // reports its failure from awaitIdle, and leaving that outside the try meant the one kind of
        // session most in need of releasing was the one kind that never was.
        return try {
            // Settled, so that a value an effect was about to save is saved. Bounded, because a
            // session is hibernated when nobody is looking at it, and one whose effects never settle
            // still has to be released.
            host.awaitIdle(effectsBudget = HIBERNATE_EFFECTS_BUDGET)
            host.confined { stateRegistry.performSave() }
        } finally {
            close()
        }
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
            // Applied, not settled: this runs before every message a session sends, so it waits for
            // the recomposition that produced the ops and for nothing else. Changes an effect makes
            // afterwards record ops of their own, signal again, and go out in the next message.
            host.awaitApplied()
            val batch = host.confined {
                buildList {
                    if (owner.hasOverflowed) {
                        // Too far behind to patch incrementally. Resending the tree costs more
                        // bytes once, but bounds what one slow client can make the server hold.
                        owner.startRecording()
                        add(ServerMessage.Reset(++rev, owner.snapshotChildren()))
                    } else {
                        val ops = owner.drainOps()
                        if (ops.isNotEmpty()) add(ServerMessage.Patch(++rev, ack, ops))
                    }
                    while (pendingNavigations.isNotEmpty()) add(pendingNavigations.removeFirst())
                }
            }
            batch.forEach { emit(it) }
        }
    }

    override fun close(): Unit = host.close()
}

/**
 * How long a first render waits for effects that were already queued, once the initial composition
 * has been applied. Effects that settle do so in microseconds; this only ever runs out for one that
 * never does.
 */
private val START_EFFECTS_BUDGET = 250.milliseconds

/** How long a hibernation waits for effects before saving what it has. */
private val HIBERNATE_EFFECTS_BUDGET = 1.seconds
