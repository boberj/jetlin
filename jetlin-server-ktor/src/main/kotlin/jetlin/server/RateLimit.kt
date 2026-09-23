package jetlin.server

import java.util.concurrent.atomic.AtomicLong

/**
 * Limits how many messages one connection may send, with room for bursts.
 *
 * It's a token bucket instead of a fixed window, because real use comes in bursts. A person filling
 * in a form sends a flurry of events, then nothing for ten seconds. A limit that can't absorb the
 * flurry would have to be set so high that it stopped protecting anything.
 *
 * The class isn't thread-safe, and doesn't need to be. Each connection has its own bucket, and a
 * connection's frames are read in sequence.
 *
 * @param ratePerSecond how fast tokens are replenished, which is the sustained limit. A value of `0`
 *   or less turns limiting off.
 * @param burst how many tokens can be saved up, which is the largest burst allowed through.
 * @param nanoTime the clock, in nanoseconds. Tests replace it.
 */
internal class TokenBucket(
    private val ratePerSecond: Double,
    private val burst: Int,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var tokens: Double = burst.toDouble()
    private var last: Long = nanoTime()

    /** Takes a token if one is available, and returns `false` if the caller is going too fast. */
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
 * Lets a log message through at most once per interval, and counts the ones it suppressed.
 *
 * Limits are reached at request rate, so logging every occurrence would bury the one line someone
 * needed under thousands of copies of it, and a log that nobody can read is no better than no log.
 * Reporting the suppressed count with each line shows the scale without the volume.
 *
 * It's thread-safe, because page renders arrive concurrently. Under contention it's approximate:
 * two racing threads can lose a count or let a line through early. Neither matters for a message
 * whose job is to be noticed.
 *
 * @param intervalNanos the minimum time between two messages, in nanoseconds.
 * @param nanoTime the clock, in nanoseconds. Tests replace it.
 */
internal class LogThrottle(
    private val intervalNanos: Long,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val last = AtomicLong(nanoTime() - intervalNanos)
    private val suppressed = AtomicLong()

    /**
     * Records an occurrence and decides whether to log it.
     *
     * @return the number of occurrences suppressed since the last message, or `null` if this one
     *   should be suppressed too.
     */
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
