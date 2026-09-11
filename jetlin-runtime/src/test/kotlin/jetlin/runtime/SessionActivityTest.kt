package jetlin.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * [SessionActivity.quietWhile], which everything about knowing when a session is done rests on.
 *
 * Its whole job is to refuse to believe a condition that was read while the session was doing
 * something. The race it guards against is two adjacent reads apart, far too narrow to provoke
 * reliably by load, so these tests do not try: they make a task run at exactly the wrong moment, from
 * inside the condition itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionActivityTest {

    private val activity = SessionActivity()
    private val serial = Dispatchers.Default.limitedParallelism(1)
    private val recompose = activity.track(serial, SessionActivity.Lane.Recompose)
    private val effects = activity.track(serial, SessionActivity.Lane.Effects)

    @Test
    fun `a condition read while nothing runs is believed`(): Unit = runBlocking {
        assertTrue(activity.quietWhile(SessionActivity.Lane.Recompose) { true })
    }

    @Test
    fun `a task that runs to completion while the condition is read spoils the check`(): Unit = runBlocking {
        val believed = activity.quietWhile(SessionActivity.Lane.Recompose) {
            // Dispatched after the count was first read, finished before it is read again: the count
            // is back where it started, and only the dispatch sequence can tell that something ran —
            // something that, in a real session, could have taken work out of the recomposer between
            // the two reads.
            runBlocking { withContext(recompose) { } }
            true
        }
        assertFalse(believed, "a condition read while a task ran must not be believed")
    }

    @Test
    fun `work still queued keeps the lane from counting as quiet`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val queued = launch(recompose) { release.await() }
        try {
            assertFalse(activity.quietWhile(SessionActivity.Lane.Recompose) { true })
        } finally {
            release.complete(Unit)
            queued.join()
        }
    }

    @Test
    fun `effects do not count against the recompose lane, and do against settling`(): Unit = runBlocking {
        val release = CompletableDeferred<Unit>()
        val effect = launch(effects) { release.await() }
        try {
            // A patch does not wait for an effect that is busy doing its own thing...
            assertTrue(activity.quietWhile(SessionActivity.Lane.Recompose) { true })
            // ...but a session with an effect still running has not settled.
            assertFalse(activity.quietWhile(lane = null) { true })
        } finally {
            release.complete(Unit)
            effect.join()
        }
    }
}
