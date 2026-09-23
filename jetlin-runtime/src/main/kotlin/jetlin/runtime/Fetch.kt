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
 * The state of a fetched value: loading, failed, or ready.
 *
 * This is a sealed type instead of a nullable value with `loading` and `error` flags. Separate
 * fields can express combinations that never occur, such as loading and failed at once, and every
 * reader would then need to know which combinations to ignore.
 */
public sealed interface Fetched<out V> {

    /** There's no value yet. The first fetch is running or hasn't been scheduled. */
    public data object Loading : Fetched<Nothing>

    /**
     * The fetch threw [cause], and there's no earlier value to fall back on.
     *
     * A value reaches this state only if it was never fetched successfully. When a revalidation
     * fails, [Fetch] keeps the value it already has, because slightly old data is more useful than
     * an error.
     */
    public data class Failed(public val cause: Throwable) : Fetched<Nothing>

    /** The fetch returned [value]. */
    public data class Ready<out V>(public val value: V) : Fetched<V>
}

/**
 * A single value from a slow source, such as an HTTP API, held in snapshot state.
 *
 * Reading [value] subscribes the current composable. When the value arrives, every session in this
 * process that read it recomposes. There's no subscription code: a stored record is reactive through
 * the same mechanism, so this class needs only a state cell and a coroutine.
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
 * Reading the value starts the fetch. The first read schedules it, later reads share the fetch
 * that's already running, and composition never waits for it. Three implementation details matter
 * for correctness, and the code that implements each one has a comment explaining it:
 *
 * 1. Reading writes no snapshot state. The in-flight marker is a plain atomic, and the attempt time
 *    is a plain field. If a composition wrote state it had just read, the write would invalidate the
 *    composition again on every pass, and it would never settle.
 * 2. The fetch doesn't run on the session's thread. [scope] belongs to the owner of this object and
 *    should dispatch to another thread. Each session composes on one thread, so a network call
 *    there would stall the whole session.
 * 3. The arrival is a single write. The fetched value is one object, assigned once, so a response
 *    causes one recomposition and one patch however many fields it has.
 *
 * ## Failure and staleness
 *
 * A new fetch starts only on request, and two things request one: a read after [ttl] has passed,
 * and a call to [invalidate] or [refresh]. Nothing happens by itself when a value expires. A read
 * happens only when the composable that reads the value recomposes. So a page that nobody
 * interacts with keeps showing the expired value until something else recomposes it, such as
 * another state change, a click, a navigation, or a hibernated session waking up.
 *
 * In other words, [ttl] is the oldest value a read accepts, not a refresh interval. Polling by
 * default would spend requests on values that nobody is looking at, and [invalidate] doesn't fetch
 * for the same reason. If a page needs to stay current while it's open, use [fresh]. Its polling
 * stops when the page goes away, and every session showing the same value shares it.
 *
 * A failed attempt records its time, just like a successful one. Otherwise, a page that keeps
 * recomposing would send a request to a broken endpoint on every pass. As a result, with the
 * default infinite [ttl], a failed fetch stays failed until something calls [invalidate]. A page
 * that shows an error should therefore offer a retry that calls it.
 *
 * While a revalidation runs, the previous value stays readable, and it's kept if the revalidation
 * fails. Switching back to [Fetched.Loading] on expiry would make the page flicker to a placeholder
 * and discard a value that's probably still correct.
 *
 * A read inside a snapshot that's later discarded still schedules a fetch. The request is wasted,
 * but the result is still correct.
 *
 * @param scope the scope that fetches run in. It should dispatch to a thread other than the
 *   session's.
 * @param ttl the maximum age of a value that a read accepts before it triggers a revalidation. The
 *   default, [Duration.INFINITE], never revalidates by itself.
 * @param now the clock, in nanoseconds. Tests replace it.
 * @param fetch the suspending function that fetches the value.
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
     * This is deliberately not snapshot state. It's written while a composition reads [value], and
     * writing state that the reader just read would invalidate that reader on every pass.
     */
    private val inFlight = AtomicReference<Job?>(null)

    /**
     * When the last attempt finished, whether it succeeded or failed.
     *
     * It's a plain field for the same reason as [inFlight].
     */
    @Volatile
    private var attemptedAt: Long? = null

    /**
     * The interval that each current watcher asked for.
     *
     * This is a list instead of a counter because watchers can ask for different intervals, and the
     * loop has to use the shortest. Synchronize on the list to access it or [polling]. The locked
     * sections are short and never span a suspension point.
     */
    private val watchers = mutableListOf<Duration>()

    /** The one loop that serves every watcher, or `null` when nobody is watching. */
    private var polling: Job? = null

    /**
     * The current state.
     *
     * Reading it schedules a fetch if there's no value yet or the value is stale. The read also
     * subscribes the composable, so it recomposes when the value arrives. Several reads in one pass,
     * or concurrent reads from several sessions, share one fetch.
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
     * Use this when you know the value is out of date but don't know whether anyone still needs it.
     * The next read triggers the fetch. If nothing reads it again, no request is sent.
     *
     * What happens depends on the current state, which also decides whether you want this method or
     * [refresh]:
     *
     * - A [Fetched.Failed] value is reset to [Fetched.Loading]. That's a state write, so its readers
     *   recompose, and one of them reads it again right away, which starts a fetch.
     * - A [Fetched.Ready] value stays as it is. Nothing recomposes, and nothing is fetched until
     *   something else recomposes the page.
     */
    public fun invalidate() {
        attemptedAt = null
        // Write through an applied snapshot, as attempt() does. Callers are usually a command's
        // coroutine, not a composition, and a plain write would wait in the global snapshot until
        // GlobalSnapshotManager next ran.
        if (state.value is Fetched.Failed) Snapshot.withMutableSnapshot { state.value = Fetched.Loading }
    }

    /**
     * Fetches immediately, whether or not the value is stale.
     *
     * Use this when you know someone is looking at the value: after a command that changed it, from
     * a retry button, or from a poller in a `LaunchedEffect` that lasts as long as the page. Unlike
     * [invalidate], it doesn't wait for a read. For the same reason, don't reach for it by default: a
     * fetch that nobody reads is a wasted request.
     *
     * If a fetch is already running, this method joins it instead of starting another.
     */
    public fun refresh() {
        invalidate()
        if (needsFetch()) schedule()
    }

    /**
     * Refreshes the value every [every] until the returned [Watch] is stopped.
     *
     * However many watchers there are, one loop runs on this object's scope. A second caller joins
     * the existing schedule, and the loop stops when the last watcher stops. Because the value is
     * shared, two sessions that show it cost one request per interval between them, not one each.
     *
     * While anyone is watching, [every] takes precedence over [ttl]. A watcher states how fresh it
     * wants the value, which is a stronger requirement than the oldest value a read accepts. If
     * watchers ask for different intervals, the loop uses the shortest.
     *
     * In a composable, use [fresh] instead. It stops the watch when the composable leaves the page.
     *
     * @param every how often to refresh. Must be positive.
     * @return a handle that stops this watcher.
     * @throws IllegalArgumentException if [every] isn't positive.
     */
    public fun watch(every: Duration): Watch {
        require(every > Duration.ZERO) { "a watch interval has to be positive, was $every" }
        synchronized(watchers) {
            val shortens = watchers.minOrNull()?.let { every < it } ?: true
            watchers += every
            // Restart the loop only if this watcher needs a shorter interval than the current one.
            // Usually a second session opens the same page with the same interval, and it joins the
            // running loop. Restarting for every new watcher would reset the delay each time, and a
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

    /** Refreshes at the shortest watcher's interval, and returns when nobody is watching. */
    private suspend fun poll() {
        while (true) {
            // Recompute the interval on every iteration, so it can grow when a watcher leaves without
            // restarting the loop. A new watcher that needs a shorter interval can't wait for this,
            // so watch() restarts the loop in that case.
            val every = synchronized(watchers) { watchers.minOrNull() } ?: return
            delay(every)
            refresh()
        }
    }

    /** Whether a read should start a fetch: nothing is in flight, and the value is missing or stale. */
    private fun needsFetch(): Boolean {
        if (inFlight.get() != null) return false
        val attempted = attemptedAt ?: return true
        return ttl.isFinite() && now() - attempted >= ttl.inWholeNanoseconds
    }

    /** Starts a fetch, unless another caller started one first. */
    private fun schedule() {
        // Create the job lazily and start it only if it wins the compare-and-set. A caller that
        // loses the race cancels a job that never ran, so no duplicate request is sent.
        val job = scope.launch(start = CoroutineStart.LAZY) { attempt() }
        job.invokeOnCompletion { inFlight.compareAndSet(job, null) }
        if (inFlight.compareAndSet(null, job)) job.start() else job.cancel()
    }

    /** Runs one fetch and publishes the outcome. */
    private suspend fun attempt() {
        val arrived: Fetched<V>? = try {
            Fetched.Ready(fetch())
        } catch (cancelled: CancellationException) {
            // Cancellation means the scope is shutting down, not that the endpoint failed. Treating
            // it as a failure would show "unavailable" for an endpoint that works.
            throw cancelled
        } catch (t: Throwable) {
            // Null means "keep the current value." A failed revalidation shouldn't discard a value
            // that was fetched successfully.
            if (state.value is Fetched.Ready) null else Fetched.Failed(t)
        }
        // Apply the value through a snapshot instead of writing it to the global snapshot. Applying
        // notifies the recomposers right away, while a direct write waits for GlobalSnapshotManager.
        // A large response costs one recomposition because the value is one object in one cell, not
        // because of this line.
        if (arrived != null) Snapshot.withMutableSnapshot { state.value = arrived }
        attemptedAt = now()
    }
}

/** A request to keep a [Fetch] refreshed. Call [stop] when you no longer need the value. */
public fun interface Watch {
    /** Ends this request. The polling stops when no other watcher remains. */
    public fun stop()
}

/**
 * Returns the value, and refreshes it every [every] while this composable is on the page.
 *
 * Use this to poll while the user is looking. It needs no lifecycle code, because the composition
 * is the lifecycle. The watch starts when the composable enters the composition and stops when it
 * leaves, whether the session navigated away, closed, or hibernated.
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
 * The [Fetch] counts its watchers, not each composition, so sessions that show the same value share
 * one loop and one request per interval. Ten people viewing a dashboard cost the same as one, and the
 * requests stop when the last of them closes the page.
 *
 * Hibernating a session stops its watch, as intended: the composition is torn down, so nobody is
 * looking. When the session wakes and recomposes, it starts watching again, and the first read
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
