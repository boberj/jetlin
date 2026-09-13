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
 * A value that has not arrived yet, failed, or arrived.
 *
 * One sealed type rather than a value plus a pair of flags, because three independent fields can say
 * things that cannot be true — loading *and* failed *and* present — and then every reader has to know
 * which combinations are real.
 */
public sealed interface Fetched<out V> {

    /** Nothing has arrived. Either the first fetch is outstanding, or it has not been scheduled yet. */
    public data object Loading : Fetched<Nothing>

    /**
     * The fetch failed and there is nothing to show.
     *
     * Only ever the state of a value that was never obtained: a *revalidation* that fails leaves the
     * value already in hand, because replacing data on screen with an error is a worse answer than
     * showing something a minute old. See [Fetch].
     */
    public data class Failed(public val cause: Throwable) : Fetched<Nothing>

    public data class Ready<out V>(public val value: V) : Fetched<V>
}

/**
 * One value from somewhere slow, held as snapshot state.
 *
 * Reading [value] subscribes the calling composition, and the arrival recomposes every session that had
 * read it — in this process, with no subscription bookkeeping anywhere. That is the same mechanism a
 * stored record uses, which is why this needs no machinery beyond a cell and a coroutine:
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
 * The read is what triggers the fetch: the first one schedules it, the ones that follow join the
 * outstanding one, and composition never blocks. Three things about how that is done are load-bearing,
 * and each is a comment at the line that implements it:
 *
 * 1. **The read writes no snapshot state.** The in-flight marker is a plain atomic and the fetch time is
 *    a plain field. State written during a pass that read it re-invalidates the reader, every pass,
 *    forever — a composition that never settles.
 * 2. **The fetch does not run on the session's thread.** [scope] belongs to whoever owns this object and
 *    should dispatch elsewhere; a session composes on one confined thread, and network latency on it
 *    stalls everything that session is doing.
 * 3. **The arrival is one write.** However many fields the fetched value has, it is one object and one
 *    assignment, so one response is one recomposition and one patch.
 *
 * ## What happens when it goes wrong, and when it goes stale
 *
 * A fetch is attempted again only when something asks for it, and the only two things that ask are a read
 * past [ttl] and [invalidate]. **Nothing wakes up when a value expires.** A read happens when the
 * composable that reads it recomposes, so a page sitting still goes on showing an expired value until
 * something recomposes it — another cell changing, a click, a navigation, or a hibernated session waking
 * and rebuilding. [ttl] is a bound on what a read will accept, not a refresh interval; polling by default
 * would mean paying for a value nobody is looking at, which is the same reason [invalidate] does not
 * refetch. A page that really must stay fresh while it sits there says so, with [fresh], and then the
 * refreshing lasts exactly as long as the page does and is shared with every other session showing it.
 *
 * A failure records its time like a success does, so a page that keeps rendering cannot turn a broken
 * endpoint into a request per recomposition — which means a failed fetch under the default infinite
 * [ttl] stays failed until [invalidate] is called. A page that shows "unavailable" should offer a retry
 * that does exactly that.
 *
 * While a revalidation is outstanding the previous value stays readable, and if it fails the previous
 * value stays. Flipping back to [Fetched.Loading] on expiry would reintroduce the placeholder flicker
 * that committing before applying exists to avoid, and it throws away something true for nothing.
 *
 * One hazard worth knowing: a read inside a snapshot that is later discarded still schedules a fetch.
 * Wasted, not wrong.
 */
public class Fetch<V>(
    private val scope: CoroutineScope,
    private val ttl: Duration = Duration.INFINITE,
    private val now: () -> Long = System::nanoTime,
    private val fetch: suspend () -> V,
) {

    private val state = mutableStateOf<Fetched<V>>(Fetched.Loading)

    /**
     * The outstanding fetch, if there is one.
     *
     * Deliberately *not* snapshot state: this is written while a composition is reading [value], and a
     * write to state the reader just read would invalidate that reader on every pass.
     */
    private val inFlight = AtomicReference<Job?>(null)

    /** When the last attempt finished, success or failure. A plain field, for the same reason. */
    @Volatile
    private var attemptedAt: Long? = null

    /**
     * What each current watcher asked for, and the one loop serving all of them.
     *
     * A list rather than a count because watchers may ask for different intervals, and the shortest is
     * the only answer that satisfies everyone. Guarded by the list itself; the critical sections are two
     * lines and are never held across a suspension.
     */
    private val watchers = mutableListOf<Duration>()
    private var polling: Job? = null

    /**
     * The current state of the value, scheduling the fetch if nothing has it yet or the copy is stale.
     *
     * Reading subscribes the composition, so the arrival recomposes it. Reading twice in one pass costs
     * one fetch, and so does reading from two sessions at once.
     */
    public val value: Fetched<V>
        get() {
            val current = state.value
            if (needsFetch()) schedule()
            return current
        }

    /**
     * Marks the value as needing a fresh fetch, without fetching now.
     *
     * For when the value is known to be out of date but it is not this caller's business whether anyone
     * still cares — the next reader pays, and if there is no next reader then nobody should have paid.
     *
     * Note the asymmetry, because it decides which of this and [refresh] you want: a value that *failed*
     * goes back to [Fetched.Loading] here, and that is a write, so its readers recompose and one of them
     * re-reads immediately. A value that is [Fetched.Ready] is left exactly as it is, so nothing
     * recomposes and nothing re-reads — the mark is silent until something else brings the page round.
     */
    public fun invalidate() {
        attemptedAt = null
        // Applied, for the same reason the arrival is: this is called from a command's coroutine, which
        // is not a composition, and a loose write would sit in the global snapshot until the pump ran.
        if (state.value is Fetched.Failed) Snapshot.withMutableSnapshot { state.value = Fetched.Loading }
    }

    /**
     * Fetches now, whether or not the copy is stale.
     *
     * For a caller who knows somebody is looking: a command that just changed the thing, a retry button,
     * a poller in a `LaunchedEffect` that lives as long as the page does. It does not wait to be read,
     * which is the whole difference from [invalidate] — and the reason not to reach for it by default,
     * since a fetch nobody is waiting for is a request nobody needed.
     *
     * Joins an outstanding fetch rather than starting a second one.
     */
    public fun refresh() {
        invalidate()
        if (needsFetch()) schedule()
    }

    /**
     * Keeps the value refreshed every [every] until the returned [Watch] is stopped.
     *
     * One loop, on this object's own scope, however many watchers there are: the second page to ask joins
     * the first one's schedule rather than starting a second, and the loop stops when the last of them
     * stops watching. That is the whole point of the value being shared — two sessions showing the same
     * thing are one request between them, not two each.
     *
     * `every` wins over [ttl] while anybody is watching: a watcher is saying how fresh it wants the value,
     * which is a stronger statement than how stale a read is willing to accept. Two watchers asking for
     * different intervals get the shorter one, because that is the only answer that satisfies both.
     *
     * Callers in a composition want [fresh] instead, which ties this to the page rather than leaving it
     * to be stopped by hand.
     */
    public fun watch(every: Duration): Watch {
        require(every > Duration.ZERO) { "a watch interval has to be positive, was $every" }
        synchronized(watchers) {
            val shortens = watchers.minOrNull()?.let { every < it } ?: true
            watchers += every
            // Restarted only when this watcher wants it sooner than anyone already watching. A new
            // watcher on the same interval — the ordinary case, a second session opening the same page —
            // joins the schedule in flight, because restarting on every arrival would mean a busy page
            // never reaching the end of a wait.
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
            // Re-read every turn, so that a watcher leaving relaxes the interval without a restart. A
            // watcher *arriving* with something shorter cannot wait for that — see `watch`.
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
        // Started lazily so that losing the race costs a cancelled job rather than a second request.
        val job = scope.launch(start = CoroutineStart.LAZY) { attempt() }
        job.invokeOnCompletion { inFlight.compareAndSet(job, null) }
        if (inFlight.compareAndSet(null, job)) job.start() else job.cancel()
    }

    private suspend fun attempt() {
        val arrived: Fetched<V>? = try {
            Fetched.Ready(fetch())
        } catch (cancelled: CancellationException) {
            // Not a failure: the scope is going away, and "unavailable" on a page being torn down would
            // be a lie about the endpoint.
            throw cancelled
        } catch (t: Throwable) {
            // Null means keep what we have. A revalidation that fails is not a reason to lose a value
            // that worked.
            if (state.value is Fetched.Ready) null else Fetched.Failed(t)
        }
        // Published as an apply rather than written loose into the global snapshot: applying notifies
        // the recomposers, where a bare write waits for [GlobalSnapshotManager]'s pump to notice it.
        // (What makes a twenty-field response cost one recomposition is not this line but the value
        // being one object: every field a page reads is a read of this one cell.)
        if (arrived != null) Snapshot.withMutableSnapshot { state.value = arrived }
        attemptedAt = now()
    }
}

/** A standing request to keep a [Fetch] fresh. Stop it when whoever wanted it stops looking. */
public fun interface Watch {
    public fun stop()
}

/**
 * The value, kept refreshed every [every] for as long as this composable is on the page.
 *
 * The framework's answer to "poll while the user is looking at it", and the reason it can be one line:
 * a composition *is* the lifecycle. While the page is composed the value is watched; when the session
 * navigates away, closes or hibernates, the composition goes and so does the watch.
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
 * Two sessions showing the same value share one loop and one request, because they share the [Fetch] —
 * the watching is reference-counted on it, not on either composition. Ten people watching a dashboard
 * cost what one person watching it costs, and when the tenth closes the tab the requests stop.
 *
 * A hibernated session stops watching, which is the right answer and not an accident: its composition is
 * torn down, so nobody is looking. When it wakes and recomposes it watches again, and the first read
 * revalidates anything that went stale while it slept.
 */
@Composable
public fun <V> Fetch<V>.fresh(every: Duration): Fetched<V> {
    DisposableEffect(this, every) {
        val watch = watch(every)
        onDispose { watch.stop() }
    }
    return value
}
