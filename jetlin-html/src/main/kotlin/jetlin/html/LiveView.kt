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
 * This class deliberately knows nothing about WebSockets or Ktor, so a test can drive it directly
 * without a browser or a server.
 *
 * @param initialRequest the request the view starts at.
 * @param framePolicy how often the view may recompose. See [FramePolicy].
 * @param restored the state that [hibernate] returned, when waking a hibernated session.
 * @param exposeTestTags whether to also write each `testTag` as a `data-test` attribute, so
 *   browser tests can select on it.
 * @param content the view's content. It receives the current request.
 */
public class LiveView(
    initialRequest: RequestContext = RequestContext(path = "/"),
    framePolicy: FramePolicy = FramePolicy.Immediate,
    restored: Map<String, JsonElement> = emptyMap(),
    private val exposeTestTags: Boolean = false,
    private val content: @Composable (RequestContext) -> Unit,
) : AutoCloseable {

    /** The view's tree. */
    public val owner: HtmlOwner = HtmlOwner()
    private val host = CompositionHost(HtmlApplier(owner), framePolicy)

    /** Holds the state that survives this composition being torn down. See [rememberSaved]. */
    private val stateRegistry = SaveableStateRegistry(restored)

    private val titleState = mutableStateOf<String?>(null)

    /**
     * The document title that the composition set, or `null` if it didn't set one.
     *
     * The page renderer reads this after the first composition settles. The title comes from the
     * composition instead of the route table because it can depend on the record the route loaded.
     * If the route table computed it, a title built from a record that the principal can't read
     * would reveal that record, even though the body refused to show it, because `<head>` is
     * rendered before the body.
     */
    public val title: String? get() = titleState.value

    /**
     * The location this session shows.
     *
     * It's Compose state, so changing it recomposes whatever reads it, and that's all navigation is:
     * the router matches the new location, the matched view replaces the old one, and the applier
     * records the difference.
     */
    private var request by mutableStateOf(initialRequest)

    /** The revision of the last message sent. Each message that changes the tree increments it. */
    private var rev = 0L

    /** The highest client event sequence number whose effects the next patch includes. */
    @Volatile
    private var ack = 0L

    /** The navigations waiting to be sent. Each one goes out after the patch that renders it. */
    private val pendingNavigations = ArrayDeque<ServerMessage.Navigate>()

    /** The URL this session shows, including the query string. */
    public val currentUrl: String get() = request.url

    private val navigator = object : Navigator {
        override fun push(url: String): Unit = goto(url, replace = false, notifyClient = true)
        override fun replace(url: String): Unit = goto(url, replace = true, notifyClient = true)
    }

    /**
     * Composes the initial tree.
     *
     * The ops from the first pass are discarded, because the first paint goes to the browser as
     * HTML. See [renderHtml].
     */
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
        // Wait until the session settles, not only until it's applied. The page is rendered from
        // this tree after start() returns, and a socket that adopts the page keeps every op
        // recorded after the drain below (see adopt()). An effect that ran after the drain but
        // before the render would reach the browser twice, once in the markup and once as a patch,
        // and an insert applied twice corrupts the page. Running the queued effects first puts their
        // ops among the ones discarded here. The wait is bounded, so an effect that never lets the
        // session settle delays the first render instead of preventing it.
        host.awaitIdle(effectsBudget = START_EFFECTS_BUDGET)
        host.confined { owner.drainOps() }
    }

    /**
     * Whether the composition behind this view is still running.
     *
     * It's `false` after a composable throws. Other failures, such as a handler that threw or a
     * store that was unreachable, leave the view usable. The server uses this property to decide
     * whether to tell the client that one click failed or that its session is gone.
     */
    public val isAlive: Boolean get() = host.isAlive

    /** Returns the tree as HTML, for the initial page load. */
    public suspend fun renderHtml(): String = host.confined { renderToHtml(owner) }

    /**
     * Waits for the view to settle, then runs [block] on the thread that owns the tree.
     *
     * The composition owns the tree, and the applier changes it on the session's thread. Anything
     * that examines the tree, such as a test that asserts on what was rendered or a debug endpoint,
     * must do it from that thread.
     *
     * @return the value [block] returns.
     */
    public suspend fun <T> inspect(block: (HtmlOwner) -> T): T {
        awaitIdle()
        return host.confined { block(owner) }
    }

    /**
     * Returns the attributes of the element that contains [renderHtml]'s output.
     *
     * The container is the root of the tree, but the page shell writes its markup instead of the
     * serializer, so the root's identity has to be passed along separately.
     */
    public suspend fun rootAttributes(): String = host.confined { rootAttributes(owner) }

    /**
     * Suspends until the view has settled: every pending recomposition is applied, queued effects
     * have run, and state written outside a snapshot is visible.
     *
     * Code that drives a view without a browser, such as a test, a renderer, or a screenshot tool,
     * calls this to know that state written from outside has taken effect. It waits as long as it
     * takes. An effect that never lets the view settle is a bug, and a hanging test exposes it.
     */
    public suspend fun awaitIdle(): Unit = host.awaitIdle()

    /**
     * Returns the whole tree as one message, for a client that connects or reconnects.
     *
     * This method discards the buffered ops first. A composition keeps running while no client is
     * connected, so when a client reconnects, the buffer describes changes to a tree that client has
     * never seen. The full tree already includes those changes, and replaying them on top of it
     * would apply them twice.
     */
    public suspend fun reset(): ServerMessage.Reset = host.confined {
        pendingNavigations.clear()
        owner.startRecording()
        ServerMessage.Reset(++rev, owner.snapshotChildren())
    }

    /**
     * Accepts a client that indexed the server-rendered markup, instead of sending it the tree.
     *
     * Unlike [reset], this method deliberately keeps the buffered ops. The composition has been live
     * since the HTML was rendered, so anything that happened since, such as a `LaunchedEffect`
     * firing or a shared store changing, is in the buffer. The buffer is exactly the difference
     * between the markup the browser holds and the current tree. Clearing it would leave the two out
     * of step without any error.
     */
    public suspend fun adopt(): ServerMessage.Ready = host.confined {
        pendingNavigations.clear()
        ServerMessage.Ready(++rev)
    }

    /**
     * Tells the view that no client is connected anymore.
     *
     * The composition stays alive, so a client that reconnects finds its session as it left it. But
     * the view stops recording ops, because the next client to connect receives the whole tree
     * anyway. Without this, a session with a running timer would pile up updates for a page that
     * nobody will see.
     */
    public suspend fun clientDetached(): Unit = host.confined { owner.stopRecording() }

    /**
     * Captures the state worth keeping, then shuts down the composition.
     *
     * This is what makes an idle session cheap. The slot table, the node tree, and the coroutines
     * all go away. What's left is a map small enough to keep in memory for thousands of sessions,
     * or to write somewhere another server can read it. You can't use the view afterward.
     *
     * Only values declared with [rememberSaved] survive. Values in `remember` are deliberately left
     * out: they're scratch space, and the point is to recompute them.
     *
     * @return the saved state, to pass as `restored` when creating the view again.
     */
    public suspend fun hibernate(): Map<String, JsonElement> {
        // Close the view whatever happens, including during the wait. A composition that has died
        // throws its failure from awaitIdle. With the wait outside the try, the sessions that most
        // needed releasing were the only ones never released.
        return try {
            // Wait until the session settles, so a value that an effect was about to save is saved.
            // The wait is bounded, because nobody is looking at a hibernating session, and one whose
            // effects never settle still has to be released.
            host.awaitIdle(effectsBudget = HIBERNATE_EFFECTS_BUDGET)
            host.confined { stateRegistry.performSave() }
        } finally {
            close()
        }
    }

    /**
     * Applies one client message, and waits until the resulting recomposition is applied.
     *
     * Whatever the message produced goes out through [messages]. Having a single writer keeps two
     * coroutines from splitting one logical update across two frames.
     */
    public suspend fun dispatch(message: ClientMessage) {
        when (message) {
            is ClientMessage.Event -> {
                ack = message.seq
                host.transact { owner.dispatch(message.node, message.event, message.payload) }
            }
            // The browser already moved, so follow it without telling it to move again.
            is ClientMessage.Navigate -> host.transact { goto(message.url, replace = false, notifyClient = false) }
            is ClientMessage.Hello -> Unit
        }
    }

    /**
     * Moves the session to [url].
     *
     * @param replace whether the browser should replace the current history entry.
     * @param notifyClient whether to tell the browser to update its address bar.
     */
    private fun goto(url: String, replace: Boolean, notifyClient: Boolean) {
        if (url == request.url) return
        request = request.forUrl(url)
        if (notifyClient) {
            pendingNavigations.addLast(ServerMessage.Navigate(url, replace))
            // A route that renders identically produces no ops. Without this signal, the sender
            // would never wake, and the address bar would keep the old URL.
            owner.signalDirty()
        }
    }

    /**
     * Everything this view sends, whatever caused it: a client event, a `LaunchedEffect`, or a
     * background coroutine writing shared state.
     *
     * Updating the client needs no separate API. When state that a composable read changes, the
     * composable recomposes, the applier records ops, and they arrive here. A navigation is emitted
     * after the patch that rendered its destination, so the address bar never gets ahead of the
     * content.
     */
    public val messages: Flow<ServerMessage> = flow {
        for (signal in owner.dirtySignals) {
            // Wait until changes are applied, not until the session settles. This runs before every
            // message the session sends, so it waits only for the recomposition that produced the
            // ops. Changes that an effect makes later record their own ops, signal again, and go
            // out in the next message.
            host.awaitApplied()
            val batch = host.confined {
                buildList {
                    if (owner.hasOverflowed) {
                        // The client is too far behind to patch. Resending the tree costs more bytes
                        // once, but it limits how much one slow client can make the server hold.
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

    /** Shuts down the composition without saving anything. */
    override fun close(): Unit = host.close()
}

/**
 * How long the first render waits for queued effects after the initial composition is applied.
 *
 * Effects that settle do so in microseconds. This budget runs out only for an effect that never
 * settles.
 */
private val START_EFFECTS_BUDGET = 250.milliseconds

/** How long hibernation waits for effects before it saves what it has. */
private val HIBERNATE_EFFECTS_BUDGET = 1.seconds
