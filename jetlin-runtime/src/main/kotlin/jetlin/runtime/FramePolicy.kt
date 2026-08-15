package jetlin.runtime

import androidx.compose.runtime.MonotonicFrameClock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * How aggressively a session is allowed to turn state changes into wire patches.
 *
 * Compose always waits for a frame before recomposing, so the frame clock is the throttle for the
 * whole pipeline: one frame produces at most one patch message.
 */
public sealed interface FramePolicy {
    /**
     * Recompose as soon as there is work. Lowest latency, and the right default for
     * interaction-driven UI where each frame corresponds to one user event.
     */
    public data object Immediate : FramePolicy

    /**
     * Recompose at most once per [interval]. Use for sessions fed by high-rate server-side sources
     * (market data, log tails, telemetry) where a client cannot usefully consume every change.
     */
    public data class Paced(val interval: Duration) : FramePolicy

    public companion object {
        /** Convenience for the common "cap at N frames per second" case. */
        public fun fps(frames: Int): FramePolicy = Paced((1000.0 / frames).toLong().milliseconds)
    }
}

internal fun FramePolicy.toClock(): MonotonicFrameClock = when (this) {
    FramePolicy.Immediate -> PacedFrameClock(0)
    is FramePolicy.Paced -> PacedFrameClock(interval.inWholeNanoseconds)
}

/**
 * A frame clock that never makes the recomposer wait longer than it has to.
 *
 * With [minIntervalNanos] of 0 this is an immediate clock: `withFrameNanos` runs its callback
 * without suspending, so recomposition follows an apply notification directly. With a positive
 * interval it delays just long enough to keep frames spaced apart, batching everything that
 * happened in between into a single recomposition pass.
 *
 * Confined to one session's dispatcher, so [lastFrameNanos] needs no synchronization.
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
