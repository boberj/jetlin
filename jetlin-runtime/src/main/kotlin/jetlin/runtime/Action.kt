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
 * How the last attempt at some suspending work is going.
 *
 * One sealed type rather than `running`, `error` and `result` side by side, for the reason [Fetched] is
 * one: separate fields can describe a state that cannot happen — running *and* failed *and* finished —
 * and every reader then has to know which combinations are real. It also makes a failure one write, so
 * the button coming back and the message appearing are one patch rather than two.
 *
 * Separate from [Fetched] even so, because [Idle] is a state a fetch does not have. A value that nothing
 * has asked for is still on its way; work that nobody has started has not been attempted at all.
 */
public sealed interface Run<out R> {

    /** Nothing has been attempted yet, or [Action.reset] put it back here. */
    public data object Idle : Run<Nothing>

    public data object Running : Run<Nothing>

    public data class Failed(public val cause: Throwable) : Run<Nothing>

    public data class Done<out R>(public val value: R) : Run<R>
}

/**
 * Suspending work started from an event handler, and how it is going.
 *
 * An event handler is an ordinary function, so anything slow has to be launched rather than awaited.
 * That forces the attempt to be modelled rather than hidden — which is wanted anyway, because a button
 * that stays enabled during a request and a failure with nowhere to appear are the two bugs this
 * prevents:
 *
 * ```kotlin
 * val save = rememberAction { hub.setStatus(principal, draft.value) }
 *
 * Button({ disabled(save.state is Run.Running); onClick { save() } }) { Text("Save") }
 * (save.state as? Run.Failed)?.let { P({ classes("error") }) { Text(it.cause.message.orEmpty()) } }
 * ```
 *
 * The work runs in the composition's scope, so it is cancelled when the session goes away. Anything that
 * has to finish regardless of who is watching belongs on a scope the application owns, not here.
 */
public class Action<R> internal constructor(
    private val scope: CoroutineScope,
    private val block: State<suspend () -> R>,
) {

    private val current = mutableStateOf<Run<R>>(Run.Idle)

    /** Reading this subscribes the composition, so the attempt's progress recomposes the page. */
    public val state: Run<R> get() = current.value

    /**
     * Starts the work, unless it is already running.
     *
     * Never throws: a handler that threw would take the session with it, and a failed action is a
     * message, not the end of the page. Re-entry while [Run.Running] is ignored rather than queued —
     * a second click on a button that is still working should not send a second request.
     *
     * A new attempt replaces the last one's outcome, which is also what should happen on screen: the
     * error goes away when you retry.
     */
    public operator fun invoke() {
        if (current.value is Run.Running) return
        current.value = Run.Running
        scope.launch {
            val outcome: Run<R> = try {
                Run.Done(block.value())
            } catch (cancelled: CancellationException) {
                // The composition is going away; there is nobody left to show an error to, and reporting
                // one would also cancel the scope this is running in.
                throw cancelled
            } catch (t: Throwable) {
                Run.Failed(t)
            }
            current.value = outcome
        }
    }

    /** Puts the action back to [Run.Idle], for dismissing a message. */
    public fun reset() {
        current.value = Run.Idle
    }
}

/**
 * Remembers an [Action] over [block].
 *
 * [block] takes nothing and is re-read on every call rather than captured once, which is what makes an
 * argument unnecessary: whatever the work needs, it reads from state when it runs, so it sees the draft as
 * the user left it rather than as it was when the page first composed.
 *
 * That also settles what to do about a list. An action per row — remembered inside the row's `key` — is
 * what you want anyway, because one action shared across rows would disable every button when any one of
 * them was working.
 */
@Composable
public fun <R> rememberAction(block: suspend () -> R): Action<R> {
    val scope = rememberCoroutineScope()
    val latest = rememberUpdatedState(block)
    return remember(scope) { Action(scope, latest) }
}
