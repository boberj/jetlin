package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The state of a fetched value: still loading, failed, or ready.
 *
 * This is a sealed type instead of a nullable value with `loading` and `error` flags. Separate fields
 * can express combinations that never occur, such as loading and failed at the same time, and every
 * reader would then have to know which combinations to ignore.
 */
public sealed interface Fetched<out V> {

    /** No value yet. The first fetch is either running or has not been scheduled. */
    public data object Loading : Fetched<Nothing>

    /**
     * The fetch failed and there is no earlier value to fall back on.
     *
     * A value only reaches this state if it was never obtained. When a *revalidation* fails, [Fetch]
     * keeps the value it already has, because showing slightly old data is more useful than replacing
     * it with an error.
     */
    public data class Failed(public val cause: Throwable) : Fetched<Nothing>

    public data class Ready<out V>(public val value: V) : Fetched<V>
}

/**
 * A single value from a slow source, such as an HTTP API, held in snapshot state.
 *
 * Reading [value] subscribes the current composition. When the value arrives, every session in this
 * process that read it recomposes. No subscription code is involved: this is the same mechanism that
 * makes a stored record reactive, so all this class needs is a state cell and a coroutine.
 *
 * ```kotlin
 * class Hub(private val client: HttpClient, private val scope: CoroutineScope) {
 *     private val profiles = ConcurrentHashMap<String, Fetch<Profile>>()
 *
 *     fun profile(of: User): Fetch<Profile> = profiles.computeIfAbsent(of.email) { email ->
 *         Fetch(scope, ttl = 30.seconds) { client.profile(email) }
 *     }
 * }
 *
 * @Composable
 * fun ProfileCard(profile: Fetch<Profile>) {
 *     when (val state = profile.value) {
 *         is Fetched.Loading -> Span { Text("…") }
 *         is Fetched.Failed -> Span { Text("unavailable") }
 *         is Fetched.Ready -> Span { Text(state.value.status) }
 *     }
 * }
 * ```
 *
 * Reading the value is what starts the fetch. The first read schedules it, later reads share the
 * fetch that is already running, and composition never waits for it. Three implementation details
 * matter for correctness, and each one has a comment where it is implemented:
 *
 * 1. **Reading writes no snapshot state.** The in-flight marker is a plain atomic and the attempt time
 *    is a plain field. If a composition wrote state it had just read, that write would invalidate the
 *    composition again on every pass, and it would never settle.
 * 2. **The fetch does not run on the session's thread.** [scope] belongs to the owner of this object
 *    and should dispatch to another thread. Each session composes on a single confined thread, so a
 *    network call there would stall the whole session.
 * 3. **The arrival is a single write.** The fetched value is one object assigned once, so a response
 *    causes one recomposition and one patch regardless of how many fields it has.
 *
 * ## Failure and staleness
 *
 * A new fetch is attempted only on request. Two things make a request: a read after [ttl] has passed,
 * and a call to [invalidate]. **Nothing happens automatically when a value expires.** A read only
 * happens when the composable that reads the value recomposes, so a page nobody interacts with keeps
 * showing the expired value until something else recomposes it: another state change, a click, a
 * navigation, or a hibernated session waking up. In other words, [ttl] limits how old a value a read
 * will accept; it is not a refresh interval. Polling by default would spend requests on values nobody
 * is looking at, and [invalidate] does not fetch for the same reason. A page that needs to stay current
 * while it is open should use [fresh]. Its polling stops when the page goes away, and it is shared with
 * every other session showing the same value.
 *
 * Failed attempts record their time just like successful ones. Otherwise a page that keeps
 * recomposing would send a request to a broken endpoint on every pass. The consequence is that with
 * the default infinite [ttl], a failed fetch stays failed until [invalidate] is called, so a page that
 * shows an error should offer a retry that calls it.
 *
 * While a revalidation is running, the previous value remains readable, and it is kept if the
 * revalidation fails. Switching back to [Fetched.Loading] on expiry would make the page flicker to a
 * placeholder and discard a value that is probably still correct.
 *
 * One known inefficiency: a read inside a snapshot that is later discarded still schedules a fetch.
 * The request is wasted, but the result is still correct.
 */
public class Fetch<V>(
    private val scope: CoroutineScope,
    private val ttl: Duration = Duration.INFINITE,
    private val now: () -> Long = System::nanoTime,
    private val fetch: suspend () -> V,
) {

    private val state = mutableStateOf<Fetched<V>>(Fetched.Loading)

    /**
     * The fetch currently running, if any.
     *
     * This is intentionally not snapshot state. It is written while a composition is reading [value],
     * and writing state that the reader has just read would invalidate that reader on every pass.
     */
    private val inFlight = AtomicReference<Job?>(null)

    /** When the last attempt finished, whether it succeeded or failed. A plain field for the same reason. */
    @Volatile
    private var attemptedAt: Long? = null

    /**
     * The interval each current watcher asked for, and the single loop that serves all of them.
     *
     * This is a list and not a counter because watchers can ask for different intervals, and the loop
     * has to use the shortest. Access is synchronized on the list itself. The locked sections are short
     * and never span a suspension point.
     */
    private val watchers = mutableListOf<Duration>()
    private var polling: Job? = null

    /**
     * The current state. Reading it schedules a fetch if there is no value yet or the value is stale.
     *
     * The read subscribes the composition, so the composition recomposes when the value arrives.
     * Multiple reads in one pass, or concurrent reads from several sessions, share a single fetch.
     */
    public val value: Fetched<V>
        get() {
            val current = state.value
            if (needsFetch()) schedule()
            return current
        }

    /**
     * Marks the value as stale without fetching it.
     *
     * Use this when you know the value is out of date but don't know whether anyone still needs it. The
     * next read triggers the fetch; if nobody reads it again, no request is made.
     *
     * The behaviour depends on the current state, which affects whether you want this or [refresh]. A
     * [Fetched.Failed] value is reset to [Fetched.Loading]. That is a state write, so its readers
     * recompose and one of them reads it again straight away, which starts a fetch. A [Fetched.Ready]
     * value is left unchanged, so nothing recomposes and nothing is fetched until something else causes
     * the page to recompose.
     */
    public fun invalidate() {
        attemptedAt = null
        // Written through an applied snapshot, like the arrival. This is usually called from a
        // command's coroutine, not from a composition, and a plain write would stay in the global
        // snapshot until the GlobalSnapshotManager pump next ran.
        if (state.value is Fetched.Failed) Snapshot.withMutableSnapshot { state.value = Fetched.Loading }
    }

    /**
     * Fetches immediately, whether or not the value is stale.
     *
     * Use this when you know someone is looking at the value: after a command that changed it, from a
     * retry button, or from a poller in a `LaunchedEffect` that lives as long as the page. Unlike
     * [invalidate], it doesn't wait for a read. That is also why it shouldn't be the default choice: a
     * fetch that nobody reads is a wasted request.
     *
     * If a fetch is already running, this joins it instead of starting another.
     */
    public fun refresh() {
        invalidate()
        if (needsFetch()) schedule()
    }

    /**
     * Refreshes the value every [every] until the returned [Watch] is stopped.
     *
     * However many watchers there are, a single loop runs on this object's scope. A second caller
     * joins the existing schedule, and the loop stops when the last watcher stops. Because the value is
     * shared, two sessions showing it cost one request per interval between them, not one each.
     *
     * While anyone is watching, [every] takes precedence over [ttl]: a watcher states how fresh it
     * wants the value, which is a stronger requirement than the oldest value a read will accept. With
     * several watchers on different intervals, the loop uses the shortest.
     *
     * Code running in a composition should use [fresh] instead, which stops the watch automatically
     * when the composable leaves the page.
     */
    public fun watch(every: Duration): Watch {
        require(every > Duration.ZERO) { "a watch interval has to be positive, was $every" }
        synchronized(watchers) {
            val shortens = watchers.minOrNull()?.let { every < it } ?: true
            watchers += every
            // Only restart the loop if this watcher needs a shorter interval than the current one. The
            // common case is a second session opening the same page with the same interval; it joins
            // the running loop. Restarting on every new watcher would reset the delay each time, and a
            // busy page might never reach the end of it.
            if (polling == null || shortens) {
                polling?.cancel()
                polling = scope.launch { poll() }
            }
        }
        return Watch {
            synchronized(watchers) {
                watchers.remove(every)
                if (watchers.isEmpty()) {
                    polling?.cancel()
                    polling = null
                }
            }
        }
    }

    private suspend fun poll() {
        while (true) {
            // Recomputed on every iteration so that when a watcher leaves, the interval can lengthen
            // without restarting the loop. A new watcher that needs a shorter interval can't wait for
            // this, so `watch` restarts the loop in that case.
            val every = synchronized(watchers) { watchers.minOrNull() } ?: return
            delay(every)
            refresh()
        }
    }

    private fun needsFetch(): Boolean {
        if (inFlight.get() != null) return false
        val attempted = attemptedAt ?: return true
        return ttl.isFinite() && now() - attempted >= ttl.inWholeNanoseconds
    }

    private fun schedule() {
        // The job is created lazily and only started if it wins the compare-and-set. A caller that
        // loses the race cancels a job that never ran, so no duplicate request is sent.
        val job = scope.launch(start = CoroutineStart.LAZY) { attempt() }
        job.invokeOnCompletion { inFlight.compareAndSet(job, null) }
        if (inFlight.compareAndSet(null, job)) job.start() else job.cancel()
    }

    private suspend fun attempt() {
        val arrived: Fetched<V>? = try {
            Fetched.Ready(fetch())
        } catch (cancelled: CancellationException) {
            // Cancellation means the scope is shutting down, not that the endpoint failed. Reporting it
            // as a failure would show "unavailable" for a working endpoint.
            throw cancelled
        } catch (t: Throwable) {
            // Null means "keep the current value". A failed revalidation shouldn't discard a value
            // that was fetched successfully.
            if (state.value is Fetched.Ready) null else Fetched.Failed(t)
        }
        // Applied through a snapshot instead of written directly to the global snapshot, because
        // applying notifies the recomposers immediately; a direct write waits for
        // [GlobalSnapshotManager]'s pump. (A large response still costs one recomposition because the
        // value is one object in one cell, not because of this line.)
        if (arrived != null) Snapshot.withMutableSnapshot { state.value = arrived }
        attemptedAt = now()
    }
}

/** A request to keep a [Fetch] refreshed. Call [stop] when the caller no longer needs the value. */
public fun interface Watch {
    public fun stop()
}

/**
 * Returns the value and keeps it refreshed every [every] while this composable is on the page.
 *
 * This covers the "poll while the user is looking" case. It needs no lifecycle plumbing because the
 * composition is the lifecycle: the watch starts when the composable enters the composition and stops
 * when it leaves, whether because the session navigated away, closed or hibernated.
 *
 * ```kotlin
 * @Composable
 * fun StatusCard(hub: Hub, principal: User) {
 *     when (val profile = hub.profile(principal).fresh(every = 10.seconds)) {
 *         is Fetched.Loading -> Span { Text("…") }
 *         is Fetched.Failed -> Span { Text("unavailable") }
 *         is Fetched.Ready -> Span { Text(profile.value.status) }
 *     }
 * }
 * ```
 *
 * Watchers are counted on the [Fetch], not per composition, so sessions showing the same value share
 * one loop and one request per interval. Ten people viewing a dashboard cost the same as one, and the
 * requests stop once the last of them closes the page.
 *
 * Hibernating a session stops its watch, which is intended: its composition is torn down, so nobody
 * is looking. When the session wakes and recomposes it starts watching again, and the first read
 * revalidates any value that went stale in the meantime.
 */
@Composable
public fun <V> Fetch<V>.fresh(every: Duration): Fetched<V> {
    DisposableEffect(this, every) {
        val watch = watch(every)
        onDispose { watch.stop() }
    }
    return value
}
