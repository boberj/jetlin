package jetlin.server

import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json

/**
 * What any [SessionStore] has to do, written down so a second implementation has something to
 * conform to rather than a first implementation to imitate.
 *
 * Subclass and supply a store; every implementation runs the same tests.
 */
abstract class SessionStoreContract {

    /** Builds an empty store whose snapshots expire after [ttl]. */
    abstract fun createStore(ttl: Duration): SessionStore

    private fun store(ttl: Duration = 30.minutes): SessionStore = createStore(ttl)

    private fun snapshot(url: String = "/", draft: String = "typed") = SessionSnapshot(
        url = url,
        state = mapOf("draft" to JsonPrimitive(draft)),
    )

    @Test
    fun `take returns what save stored`(): Unit = runBlocking {
        val store = store()
        store.save("token", snapshot(url = "/todo/1", draft = "half a sentence"))

        val taken = assertNotNull(store.take("token"))
        assertEquals("/todo/1", taken.url)
        assertEquals(JsonPrimitive("half a sentence"), taken.state["draft"])
    }

    @Test
    fun `taking removes the snapshot`(): Unit = runBlocking {
        val store = store()
        store.save("token", snapshot())

        assertNotNull(store.take("token"))
        assertNull(store.take("token"), "a snapshot may only be handed out once")
    }

    @Test
    fun `take misses for a token that was never stored`(): Unit = runBlocking {
        assertNull(store().take("never-seen"))
    }

    @Test
    fun `tokens do not interfere with each other`(): Unit = runBlocking {
        val store = store()
        store.save("first", snapshot(url = "/one"))
        store.save("second", snapshot(url = "/two"))

        assertEquals("/one", assertNotNull(store.take("first")).url)
        assertEquals("/two", assertNotNull(store.take("second")).url)
    }

    @Test
    fun `saving twice under one token keeps the later snapshot`(): Unit = runBlocking {
        val store = store()
        store.save("token", snapshot(draft = "first"))
        store.save("token", snapshot(draft = "second"))

        assertEquals(JsonPrimitive("second"), assertNotNull(store.take("token")).state["draft"])
    }

    @Test
    fun `a snapshot past its time to live is not returned`(): Unit = runBlocking {
        val store = store(ttl = 50.milliseconds)
        store.save("token", snapshot())

        delay(200)
        assertNull(store.take("token"), "an expired session must not be resumable")
    }

    /**
     * The property the interface exists for.
     *
     * Waking a session is a transfer of ownership: two sockets can quote one token at the same time,
     * and if both were handed the snapshot both would build a composition from it, leaving one live,
     * attached, and invisible to the reaper meant to collect it. Implementing `take` as a read
     * followed by a delete fails this test, which is the point of having it.
     */
    @Test
    fun `concurrent takes hand the snapshot to exactly one caller`(): Unit = runBlocking {
        repeat(50) { attempt ->
            val store = store()
            val token = "token-$attempt"
            store.save(token, snapshot(draft = "contested"))

            val winners = Collections.synchronizedList(mutableListOf<SessionSnapshot>())
            (1..8).map {
                async { store.take(token)?.let(winners::add) }
            }.awaitAll()

            assertEquals(1, winners.size, "expected exactly one caller to win, got ${winners.size}")
        }
    }

    /**
     * Any store that is not this process's memory has to serialize the envelope, so the shape has
     * to survive a round trip whether or not the implementation under test performs one.
     */
    @Test
    fun `a snapshot survives a serialization round trip`(): Unit = runBlocking {
        val store = store()
        val original = snapshot(url = "/todo/7?tab=notes", draft = "with \"quotes\" and \n newlines")
        store.save("token", original)

        val taken = assertNotNull(store.take("token"))
        val json = Json.encodeToString(SessionSnapshot.serializer(), taken)
        val decoded = Json.decodeFromString(SessionSnapshot.serializer(), json)

        assertEquals(taken, decoded)
        assertEquals(SessionSnapshot.CURRENT_VERSION, decoded.version)
    }
}

class InMemorySessionStoreTest : SessionStoreContract() {
    override fun createStore(ttl: Duration): SessionStore = InMemorySessionStore(ttl)
}
