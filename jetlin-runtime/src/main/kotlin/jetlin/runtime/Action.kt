package jetlin.runtime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The state of the most recent attempt at a piece of suspending work.
 *
 * Like [Fetched], this is a sealed type instead of separate `running`, `error` and `result` fields,
 * which could describe impossible combinations such as running and failed at once. A single field also
 * means a failure is a single write, so re-enabling the button and showing the error arrive in the
 * same patch.
 *
 * It is a separate type from [Fetched] because of [Idle], which a fetch has no equivalent of. A value
 * that nobody has read yet is still going to be fetched, whereas work that nobody has started simply
 * hasn't been attempted.
 */
public sealed interface Run<out R> {

    /** Not attempted yet, or cleared by [Action.reset]. */
    public data object Idle : Run<Nothing>

    public data object Running : Run<Nothing>

    public data class Failed(public val cause: Throwable) : Run<Nothing>

    public data class Done<out R>(public val value: R) : Run<R>
}

/**
 * Suspending work started from an event handler, together with its current [Run] state.
 *
 * Event handlers are not suspending functions, so slow work has to be launched instead of awaited.
 * Exposing the attempt's state lets the page handle the two usual problems with that: a button that
 * stays enabled while the request is running, and an error with nowhere to be shown.
 *
 * ```kotlin
 * val save = rememberAction { hub.setStatus(principal, draft.value) }
 *
 * Button({ disabled(save.state is Run.Running); onClick { save() } }) { Text("Save") }
 * (save.state as? Run.Failed)?.let { P({ classes("error") }) { Text(it.cause.message.orEmpty()) } }
 * ```
 *
 * The work runs in the composition's coroutine scope and is cancelled when the session ends. Work
 * that must complete even if nobody is watching should run on a scope owned by the application.
 */
public class Action<R> internal constructor(
    private val scope: CoroutineScope,
    private val block: State<suspend () -> R>,
) {

    private val current = mutableStateOf<Run<R>>(Run.Idle)

    /** Reading this subscribes the composition, so the page recomposes as the attempt progresses. */
    public val state: Run<R> get() = current.value

    /**
     * Starts the work, unless it is already running.
     *
     * This never throws. An exception here would end the session, and a failed action should only
     * produce an error message. Calls made while the state is [Run.Running] are ignored, not queued,
     * so clicking a busy button twice doesn't send a second request.
     *
     * Starting a new attempt replaces the previous outcome, so a retry clears the old error.
     */
    public operator fun invoke() {
        if (current.value is Run.Running) return
        current.value = Run.Running
        scope.launch {
            val outcome: Run<R> = try {
                Run.Done(block.value())
            } catch (cancelled: CancellationException) {
                // The composition is being disposed, so there is no page to show an error on.
                // Recording this as a failure would also swallow the cancellation of this scope.
                throw cancelled
            } catch (t: Throwable) {
                Run.Failed(t)
            }
            current.value = outcome
        }
    }

    /** Resets the state to [Run.Idle], for example to dismiss an error message. */
    public fun reset() {
        current.value = Run.Idle
    }
}

/**
 * Remembers an [Action] that runs [block].
 *
 * [block] takes no arguments. The latest [block] is used on each call instead of the one from the
 * first composition, so the work reads its inputs from state at the moment it runs. For example, it
 * sees the draft text as the user left it, not as it was when the page was first composed.
 *
 * For a list, remember one action per row inside the row's `key`. A single action shared by all rows
 * would disable every row's button while any one of them was running.
 */
@Composable
public fun <R> rememberAction(block: suspend () -> R): Action<R> {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(block)
    return remember(scope) { Action(scope, latest) }
}
