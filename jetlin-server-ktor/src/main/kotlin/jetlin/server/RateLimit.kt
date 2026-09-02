package jetlin.server

import java.util.concurrent.atomic.AtomicLong

/**
 * How many messages one connection may send, and how far it may run ahead of that.
 *
 * A token bucket rather than a fixed window, because real use is bursty and a window is not: a
 * person filling in a form produces a flurry of events and then nothing for ten seconds, and a limit
 * that cannot absorb the flurry has to be set so high that it stops protecting anything.
 *
 * [ratePerSecond] is the rate tokens are replenished at and so the sustained ceiling; [burst] is how
 * many can be banked, and therefore how large a flurry is allowed through. A value of zero or less
 * for the rate means no limiting at all.
 *
 * Not thread-safe, and does not need to be: one of these belongs to one connection, and a
 * connection's frames are read in sequence.
 */
internal class TokenBucket(
    private val ratePerSecond: Double,
    private val burst: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens: Double = burst.toDouble()
    private var last: Long = nanoTime()

    /** Takes a token if one is available. False means the caller is going too fast. */
    fun tryConsume(): Boolean {
        if (ratePerSecond <= 0) return true

        val now = nanoTime()
        val elapsed = (now - last).coerceAtLeast(0) / 1_000_000_000.0
        last = now
        tokens = minOf(burst.toDouble(), tokens + elapsed * ratePerSecond)

        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}

/**
 * Lets a message through at most once per interval, and counts what it swallowed.
 *
 * Both limits are reached at request rate, so logging every occurrence would bury the one line
 * somebody needed under thousands of copies of itself — and a log nobody can read is the same as no
 * log. Reporting the suppressed count with each line keeps the scale visible without the volume.
 *
 * Thread-safe because page renders arrive concurrently. Approximate under contention: two threads
 * racing can lose a count or emit a line early, neither of which matters for something whose job is
 * to be noticed.
 */
internal class LogThrottle(
    private val intervalNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val last = AtomicLong(nanoTime() - intervalNanos)
    private val suppressed = AtomicLong()

    /** How many were suppressed since the last message, or null if this one should be too. */
    fun attempt(): Long? {
        val now = nanoTime()
        val previous = last.get()
        if (now - previous < intervalNanos || !last.compareAndSet(previous, now)) {
            suppressed.incrementAndGet()
            return null
        }
        return suppressed.getAndSet(0)
    }
}
