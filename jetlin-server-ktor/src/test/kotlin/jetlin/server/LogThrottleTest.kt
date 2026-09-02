package jetlin.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Saying it once, and saying how often it would have been said.
 *
 * Both limits are reached at request rate, so the naive version buries the one line somebody needed
 * under thousands of copies of itself. What makes the throttled version still useful is the count
 * it carries: the scale is visible without the volume.
 */
class LogThrottleTest {

    private var now = 0L
    private fun throttle(seconds: Double = 60.0) =
        LogThrottle((seconds * 1_000_000_000).toLong()) { now }

    private fun advance(seconds: Double) {
        now += (seconds * 1_000_000_000).toLong()
    }

    @Test
    fun `the first one is always let through`() {
        assertEquals(0L, throttle().attempt(), "nothing has been suppressed yet")
    }

    @Test
    fun `everything within the interval is swallowed`() {
        val throttle = throttle(seconds = 60.0)
        throttle.attempt()

        repeat(1000) {
            advance(0.01)
            assertNull(throttle.attempt())
        }
    }

    @Test
    fun `the next one through reports what it stood for`() {
        val throttle = throttle(seconds = 60.0)
        throttle.attempt()
        repeat(1000) { throttle.attempt() }

        advance(61.0)

        assertEquals(1000L, throttle.attempt(), "the count is the point of throttling rather than dropping")
    }

    @Test
    fun `the count resets once reported`() {
        val throttle = throttle(seconds = 60.0)
        throttle.attempt()
        repeat(5) { throttle.attempt() }

        advance(61.0)
        assertEquals(5L, throttle.attempt())

        advance(61.0)
        assertEquals(0L, throttle.attempt(), "a quiet minute reports nothing rather than repeating itself")
    }
}
