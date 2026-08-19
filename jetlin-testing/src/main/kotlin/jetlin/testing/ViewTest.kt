package jetlin.testing

import androidx.compose.runtime.Composable
import jetlin.html.HtmlOwner
import jetlin.html.LiveView
import jetlin.html.RequestContext
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.NodeId
import jetlin.protocol.Op
import jetlin.runtime.FramePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

/**
 * Runs [body] against a view composed from [content], with no browser and no server.
 *
 * ```kotlin
 * @Test
 * fun `clearing the title blocks the save`(): Unit = runViewTest(url = "/todo/1") {
 *     setContent { TodoDetailPage() }
 *
 *     onNode(hasAttr("data-test", "title")).type("")
 *
 *     onNode(hasAttr("data-test", "title-error")).assertText("A title is required")
 *     onNode(hasAttr("data-test", "save")).assertDisabled()
 * }
 * ```
 *
 * [url] and [pathParams] stand in for what the HTTP layer would have resolved, so a view reached by
 * a route can be tested on its own without a router in the way. Supply [request] directly when the
 * view reads headers or application attributes.
 *
 * Uses `runBlocking` rather than `runTest` deliberately: a view runs on real dispatchers, and
 * virtual time would skip past waiting the recomposer has to do. Nothing here needs a sleep — every
 * interaction returns once the recomposition it caused has settled.
 */
public fun runViewTest(
    url: String = "/",
    pathParams: Map<String, String> = emptyMap(),
    request: RequestContext = RequestContext(
        path = url.substringBefore('?'),
        pathParams = pathParams,
    ),
    framePolicy: FramePolicy = FramePolicy.Immediate,
    body: suspend ViewTest.() -> Unit,
): Unit = runBlocking {
    val test = ViewTest(request, framePolicy)
    try {
        test.body()
    } finally {
        test.close()
    }
}

/**
 * One view under test: what it rendered, what can be done to it, and where it thinks it is.
 *
 * Everything is addressed through matchers rather than node ids, and every interaction goes through
 * the same path a real client's message would, so a test describes the behaviour of the view and
 * not the shape of the protocol underneath it.
 */
public class ViewTest internal constructor(
    private val request: RequestContext,
    private val framePolicy: FramePolicy,
) : AutoCloseable {

    private var content: (@Composable (RequestContext) -> Unit)? = null
    private var view: LiveView? = null
    private var seq = 0L

    /** Ops seen since the last drain, collected only while [recordUpdate] is running. */
    private var recording: MutableList<Op>? = null

    /**
     * Composes one view and waits for the first pass to finish. Call once, before anything else.
     *
     * Use [setRoutes] instead when the test navigates: a single view pinned here stays composed
     * wherever the session goes, which is not what the application does.
     */
    public suspend fun setContent(content: @Composable () -> Unit) {
        setRoutedContent { content() }
    }

    internal suspend fun setRoutedContent(content: @Composable (RequestContext) -> Unit) {
        check(view == null) { "content has already been set on this view test" }
        this.content = content
        view = LiveView(request, framePolicy, emptyMap(), content).also { it.start() }
    }

    private val live: LiveView
        get() = view ?: error("No content set; call setContent { ... } first")

    /** Where the view currently thinks it is, as it would appear in the address bar. */
    public val currentUrl: String get() = live.currentUrl

    public fun assertUrl(expected: String) {
        assertSame("Current URL", expected, currentUrl)
    }

    /** Exactly one node is expected to match; anything else fails with the tree printed. */
    public fun onNode(matcher: NodeMatcher): NodeSelection = NodeSelection(this, matcher)

    /** Every node matching, in document order. */
    public fun onAll(matcher: NodeMatcher): NodeCollection = NodeCollection(this, matcher)

    /**
     * Reads the settled node tree on the thread that owns it.
     *
     * The querying API is built on this, and it is public for the occasional assertion the matchers
     * do not cover.
     */
    public suspend fun <T> inspect(block: (HtmlOwner) -> T): T = live.inspect(block)

    /**
     * Waits for everything in flight to be applied.
     *
     * Interactions already do this, so it is only needed after changing state from outside the view
     * — a shared store written directly by the test, standing in for another user or a background
     * job.
     */
    public suspend fun awaitIdle() {
        live.awaitIdle()
        drain()
    }

    /** The server-rendered HTML. An escape hatch for when the markup itself is the thing under test. */
    public suspend fun renderHtml(): String = live.renderHtml()

    /** An indented rendering of the whole tree. For working out why a matcher found nothing. */
    public suspend fun debugTree(): String = inspect { it.root.describe() }

    /** Moves the view as a back or forward button would: the location changes, and the view follows. */
    public suspend fun navigate(url: String) {
        live.dispatch(ClientMessage.Navigate(url))
        drain()
    }

    /**
     * Puts the session through hibernation and brings it back, as a dropped connection or a deploy
     * would.
     *
     * What survives is what the view declared with `rememberSaved`; everything in `remember` is
     * recomputed. Which is which is an application decision, and this is how to check it was the
     * right one. Every existing handle keeps working — the queries re-resolve against the restored
     * view.
     */
    public suspend fun hibernateAndRestore() {
        val content = content ?: error("No content set; call setContent { ... } first")
        val saved: Map<String, JsonElement> = live.hibernate()
        view = LiveView(request.forUrl(currentUrl), framePolicy, saved, content).also { it.start() }
    }

    /** Sends one event to [node], as the client would, and waits for the update it causes. */
    internal suspend fun dispatchEvent(node: NodeId, event: String, payload: EventPayload) {
        live.dispatch(ClientMessage.Event(node = node, event = event, seq = ++seq, payload = payload))
        drain()
    }

    /**
     * Takes whatever the last change recorded, keeping it only if something is recording.
     *
     * Nothing collects `LiveView.messages` in a test, so without this the buffer would grow for the
     * whole test and eventually overflow — at which point the view drops it and the change
     * assertions would silently see nothing.
     */
    private suspend fun drain() {
        val ops = live.inspect { it.drainOps() }
        recording?.addAll(ops)
    }

    internal suspend fun <T> recordingOps(block: suspend () -> T): Pair<T, List<Op>> {
        check(recording == null) { "recordUpdate blocks cannot be nested" }
        drain()
        val collected = mutableListOf<Op>()
        recording = collected
        try {
            val result = block()
            // A change made without an interaction — a shared store written directly — still has to
            // be settled and collected before the recording closes.
            awaitIdle()
            return result to collected.toList()
        } finally {
            recording = null
        }
    }

    override fun close() {
        view?.close()
    }
}
