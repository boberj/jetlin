package jetlin.runtime

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Controls how often a session turns state changes into patches for the browser.
 *
 * Compose always waits for a frame before it recomposes, so the frame clock throttles the whole
 * pipeline. Each frame produces at most one patch message.
 */
public sealed interface FramePolicy {
    /**
     * Recomposes as soon as there is work.
     *
     * This policy has the lowest latency. It's the right default for a UI driven by user
     * interaction, where each frame corresponds to one user event.
     */
    public data object Immediate : FramePolicy

    /**
     * Recomposes at most once per [interval].
     *
     * Use this for sessions fed by a fast server-side source, such as market data, a log tail, or
     * telemetry, where the browser can't make use of every change.
     */
    public data class Paced(val interval: Duration) : FramePolicy

    public companion object {
        /** Returns a [Paced] policy that recomposes at most [frames] times per second. */
        public fun fps(frames: Int): FramePolicy = Paced((1000.0 / frames).toLong().milliseconds)
    }
}

/** Returns the frame clock that enforces this policy. */
internal fun FramePolicy.toClock(): MonotonicFrameClock = when (this) {
    FramePolicy.Immediate -> PacedFrameClock(0)
    is FramePolicy.Paced -> PacedFrameClock(interval.inWholeNanoseconds)
}

/**
 * A frame clock that never makes the recomposer wait longer than necessary.
 *
 * When [minIntervalNanos] is `0`, the clock is immediate: `withFrameNanos` runs its callback without
 * suspending, so recomposition follows an apply notification directly. When the interval is
 * positive, the clock delays only long enough to keep frames that far apart. Everything that
 * happens during the delay is batched into one recomposition pass.
 *
 * Only one session's dispatcher uses a given clock, so [lastFrameNanos] needs no synchronization.
 */
internal class PacedFrameClock(private val minIntervalNanos: Long) : MonotonicFrameClock {
    private var lastFrameNanos = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        if (minIntervalNanos > 0) {
            val elapsed = System.nanoTime() - lastFrameNanos
            if (elapsed < minIntervalNanos) {
                delay((minIntervalNanos - elapsed) / 1_000_000)
            }
        }
        lastFrameNanos = System.nanoTime()
        return onFrame(lastFrameNanos)
    }
}
