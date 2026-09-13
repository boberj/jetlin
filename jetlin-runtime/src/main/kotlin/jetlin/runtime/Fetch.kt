package jetlin.runtime

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
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
 * A fetch is attempted again only when something asks for it: a read past [ttl], or [invalidate]. A
 * failure records its time like a success does, so a page that keeps rendering cannot turn a broken
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
     * What a command calls when it succeeds: the next reader pays for the refresh, and if nobody is
     * reading it any more then nobody should be paying for it. A value that failed goes back to
     * [Fetched.Loading] here, because there is nothing on screen to flicker away from — which is what
     * makes this the right thing for a retry button to call.
     */
    public fun invalidate() {
        attemptedAt = null
        if (state.value is Fetched.Failed) state.value = Fetched.Loading
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
        // One snapshot, so a fetch that filled a whole page's worth of fields is still one recomposition.
        // Outside any composition, which is what GlobalSnapshotManager's pump exists to deliver.
        if (arrived != null) Snapshot.withMutableSnapshot { state.value = arrived }
        attemptedAt = now()
    }
}
