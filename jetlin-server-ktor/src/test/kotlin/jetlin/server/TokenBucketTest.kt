package jetlin.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the rate limit on a clock that the test controls.
 *
 * It's worth testing away from a socket. Everything interesting about a token bucket is what it does
 * over time, and a test that waits for real seconds is both slow and flaky.
 */
class TokenBucketTest {

    private var now = 0L
    private fun bucket(rate: Double = 10.0, burst: Int = 5) = TokenBucket(rate, burst) { now }

    private fun advance(seconds: Double) {
        now += (seconds * 1_000_000_000).toLong()
    }

    @Test
    fun `a full bucket lets a burst straight through`() {
        val bucket = bucket(burst = 5)

        repeat(5) { assertTrue(bucket.tryConsume(), "token ${it + 1} of the burst should be free") }
        assertFalse(bucket.tryConsume(), "the sixth arrives with the bucket empty")
    }

    @Test
    fun `tokens come back at the configured rate`() {
        val bucket = bucket(rate = 10.0, burst = 5)
        repeat(5) { bucket.tryConsume() }

        advance(0.05) // half a token, at ten a second
        assertFalse(bucket.tryConsume())

        advance(0.05) // now a whole one
        assertTrue(bucket.tryConsume())
    }

    @Test
    fun `waiting does not bank more than the burst`() {
        val bucket = bucket(rate = 10.0, burst = 5)
        repeat(5) { bucket.tryConsume() }

        // A minute of silence would be six hundred tokens if they built up without limit. A burst
        // size exists so that they don't.
        advance(60.0)

        assertEquals(5, generateSequence { bucket.tryConsume() }.takeWhile { it }.count())
    }

    @Test
    fun `a sustained sender is held to the rate`() {
        val bucket = bucket(rate = 10.0, burst = 5)
        repeat(5) { bucket.tryConsume() }

        // One second, asked a hundred times. What gets through is the rate, not the asking.
        var allowed = 0
        repeat(100) {
            advance(0.01)
            if (bucket.tryConsume()) allowed++
        }
        // Allow a token either way instead of exactly ten. Tokens are replenished in fractions, and
        // ten additions of a tenth don't reach one in binary floating point. Being off by one token a
        // second isn't worth defending against, and a test that pinned it would test arithmetic, not
        // behavior.
        assertTrue(allowed in 9..11, "expected roughly the configured rate, got $allowed")
    }

    @Test
    fun `a rate of zero means no limit at all`() {
        val bucket = bucket(rate = 0.0, burst = 1)

        repeat(1000) { assertTrue(bucket.tryConsume()) }
    }

    @Test
    fun `a clock that goes backwards does not hand out free tokens`() {
        val bucket = bucket(rate = 10.0, burst = 5)
        repeat(5) { bucket.tryConsume() }

        // System.nanoTime is monotonic in principle, but has been known to go backward in practice.
        now -= 1_000_000_000
        assertFalse(bucket.tryConsume())
    }
}
