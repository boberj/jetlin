package jetlin.testing

import androidx.compose.runtime.Composable
import jetlin.html.AttributeKey
import jetlin.html.HtmlOwner
import jetlin.html.LiveView
import jetlin.html.RequestContext
import jetlin.html.RoutePattern
import jetlin.protocol.ClientMessage
import jetlin.protocol.EventPayload
import jetlin.protocol.NodeId
import jetlin.protocol.Op
import jetlin.runtime.FramePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

/**
 * Runs [body] against a view, with no browser and no server. Call [ViewTest.setContent] in [body]
 * to compose the view.
 *
 * ```kotlin
 * @Test
 * fun `clearing the title blocks the save`(): Unit = runViewTest(url = "/todo/1") {
 *     setContent(route = "/todo/{id}") { TodoDetailPage() }
 *
 *     onNode(hasTestTag("title")).type("")
 *
 *     onNode(hasTestTag("title-error")).assertText("A title is required")
 *     onNode(hasTestTag("save")).assertDisabled()
 * }
 * ```
 *
 * This function deliberately uses `runBlocking` instead of `runTest`. A view runs on real
 * dispatchers, and virtual time would skip waits that the recomposer needs. No test needs to sleep:
 * every interaction returns once the recomposition it caused has settled.
 *
 * @param url where the session starts. A view reached through a route declares its pattern in
 *   [ViewTest.setContent], which extracts the path parameters from this URL.
 * @param request the request the session starts with. Pass it when the view reads headers or
 *   application attributes.
 * @param framePolicy how often the view may recompose.
 * @param body the test.
 */
public fun runViewTest(
    url: String = "/",
    request: RequestContext = RequestContext(path = url.substringBefore('?')),
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
 * One view under test: what it rendered, what you can do to it, and where it thinks it is.
 *
 * Tests find nodes with matchers instead of node IDs, and every interaction takes the same path as a
 * real client's message. So a test describes the view's behavior, not the protocol underneath it.
 */
public class ViewTest internal constructor(
    private var request: RequestContext,
    private val framePolicy: FramePolicy,
) : AutoCloseable {

    /** The content under test, kept so [hibernateAndRestore] can compose it again. */
    private var content: (@Composable (RequestContext) -> Unit)? = null
    private var view: LiveView? = null

    /** The sequence number of the last event sent, as a client would number it. */
    private var seq = 0L

    /** The ops seen since the last drain. They're collected only while [recordUpdate] runs. */
    private var recording: MutableList<Op>? = null

    /**
     * Composes one view and waits for the first pass to finish. Call it once, before anything else.
     *
     * If the test navigates, use [setRoutes] instead. A single view set here stays composed wherever
     * the session goes, which isn't what the application does.
     *
     * @param route the pattern the view is registered at, such as `/todo/{id}`. Pass it whenever the
     *   view reads a path parameter. The parameters come from matching the test's URL against it, so
     *   you write the ID once, and the two can't disagree.
     * @throws IllegalStateException if [route] doesn't match the test's URL, or content was already
     *   set.
     */
    public suspend fun setContent(route: String? = null, content: @Composable () -> Unit) {
        val params = route?.let { pattern ->
            RoutePattern(pattern).match(request.path)
                ?: error("The route '$pattern' does not match the test's url '${request.path}'")
        }
        val routed = if (params == null) request else request.withPathParams(params)
        setRoutedContent(routed) { content() }
    }

    /** Composes [content] with the request [initial]. [setContent] and [setRoutes] call this. */
    internal suspend fun setRoutedContent(
        initial: RequestContext = request,
        content: @Composable (RequestContext) -> Unit,
    ) {
        check(view == null) { "content has already been set on this view test" }
        this.content = content
        view = LiveView(initial, framePolicy, emptyMap(), false, content).also { it.start() }
    }

    /** The view, which exists once content is set. */
    private val live: LiveView
        get() = view ?: error("No content set; call setContent { ... } first")

    /**
     * Sets a session attribute, usually the principal.
     *
     * ```kotlin
     * setAttribute(PrincipalKey, root)
     * setRoutes(appRoutes)
     * ```
     *
     * The attribute applies to every view composed after this call, including the one that
     * [hibernateAndRestore] creates. That lets you test waking from hibernation. A woken session
     * recomputes its attributes from the new connection, so changing the principal and then calling
     * [hibernateAndRestore] simulates a role revoked while the session was hibernated.
     */
    public fun <T> setAttribute(key: AttributeKey<T>, value: T?) {
        request = request.with(key, value)
    }

    /** Where the view thinks it is, as the address bar would show it. */
    public val currentUrl: String get() = live.currentUrl

    /**
     * Waits for the view to settle, and returns the document title that the composition set.
     *
     * Assert on it separately from the body. The title is rendered into `<head>` before the body, so
     * a title computed from a record reveals the record even if the body refused to show it.
     */
    public suspend fun title(): String? {
        live.awaitIdle()
        return live.title
    }

    /**
     * Asserts that the view is at [expected].
     *
     * @throws AssertionError if [currentUrl] differs.
     */
    public fun assertUrl(expected: String) {
        assertSame("Current URL", expected, currentUrl)
    }

    /** The subtree that queries are confined to, or `null` for the whole page. See [within]. */
    private var scope: NodeSelection? = null

    /**
     * Selects the one node that matches [matcher].
     *
     * The selection is resolved when you act on it or assert on it. If no node or more than one node
     * matches then, it fails and prints the tree.
     */
    public fun onNode(matcher: NodeMatcher): NodeSelection = NodeSelection(this, matcher, scope = scope)

    /** Selects every node that matches [matcher], in document order. */
    public fun onAll(matcher: NodeMatcher): NodeCollection = NodeCollection(this, matcher, scope = scope)

    /**
     * Runs [block] with every query confined to the subtree under [selection].
     *
     * Use it to say where something is, not only what it is.
     * `onAll(hasTag("button") and hasText("up"))[2]` is an index across the whole page that happens to
     * land on the third row's button. This says what you meant:
     *
     * ```kotlin
     * within(onAll(hasTestTag("todo"))[2]) {
     *     onNode(hasText("up")).click()
     * }
     * ```
     *
     * [selection] is resolved again for each query inside the block instead of once, for the same
     * reason selections are lazy everywhere else: a recomposition can replace the node. Blocks nest,
     * and an inner scope is resolved within its outer one.
     */
    public suspend fun within(selection: NodeSelection, block: suspend ViewTest.() -> Unit) {
        val previous = scope
        scope = selection
        try {
            block()
        } finally {
            scope = previous
        }
    }

    /**
     * Waits for the view to settle, then runs [block] on the tree, on the thread that owns it.
     *
     * The query API is built on this. It's public for the occasional assertion that the matchers
     * don't cover.
     */
    public suspend fun <T> inspect(block: (HtmlOwner) -> T): T = live.inspect(block)

    /**
     * Waits for everything in progress to be applied.
     *
     * Interactions already wait, so you need this only after changing state from outside the view,
     * for example when the test writes a shared store directly to stand in for another user or a
     * background job.
     */
    public suspend fun awaitIdle() {
        live.awaitIdle()
        drain()
    }

    /** Returns the server-rendered HTML. Use it when the markup itself is what you're testing. */
    public suspend fun renderHtml(): String = live.renderHtml()

    /** Returns the whole tree as indented text. Use it to work out why a matcher found nothing. */
    public suspend fun debugTree(): String = inspect { it.root.describe() }

    /**
     * Moves the view to [url] as the back or forward button would: the location changes, and the
     * view follows.
     */
    public suspend fun navigate(url: String) {
        live.dispatch(ClientMessage.Navigate(url))
        drain()
    }

    /**
     * Hibernates the session and wakes it again, as a dropped connection or a deployment would.
     *
     * What the view declared with `rememberSaved` survives, and everything in `remember` is
     * recomputed. The application decides which is which, and this is how you check the decision.
     * Existing selections keep working, because queries are resolved again against the restored
     * view.
     */
    public suspend fun hibernateAndRestore() {
        val content = content ?: error("No content set; call setContent { ... } first")
        val saved: Map<String, JsonElement> = live.hibernate()
        view = LiveView(request.forUrl(currentUrl), framePolicy, saved, false, content).also { it.start() }
    }

    /** Sends one event to [node], as the client would, and waits for the update it causes. */
    internal suspend fun dispatchEvent(node: NodeId, event: String, payload: EventPayload) {
        live.dispatch(ClientMessage.Event(node = node, event = event, seq = ++seq, payload = payload))
        drain()
    }

    /**
     * Takes the ops that the last change recorded, and keeps them only if [recordUpdate] is running.
     *
     * Nothing collects `LiveView.messages` in a test. Without this, the buffer would grow for the
     * whole test and eventually overflow. The view would then drop it, and the change assertions
     * would see nothing, without any error.
     */
    private suspend fun drain() {
        val ops = live.inspect { it.drainOps() }
        recording?.addAll(ops)
    }

    /**
     * Runs [block] and returns its result with every op recorded meanwhile. [recordUpdate] uses
     * this.
     */
    internal suspend fun <T> recordingOps(block: suspend () -> T): Pair<T, List<Op>> {
        check(recording == null) { "recordUpdate blocks cannot be nested" }
        drain()
        val collected = mutableListOf<Op>()
        recording = collected
        try {
            val result = block()
            // A change made without an interaction, such as a shared store written directly, still
            // has to settle and be collected before the recording ends.
            awaitIdle()
            return result to collected.toList()
        } finally {
            recording = null
        }
    }

    /** Shuts down the view. [runViewTest] calls this for you. */
    override fun close() {
        view?.close()
    }
}
