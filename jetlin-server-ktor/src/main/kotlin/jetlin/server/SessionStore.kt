package jetlin.server

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A session reduced to what is worth keeping while nobody is using it.
 *
 * Small by construction: the composition, the node tree and every `remember` are gone, and what is
 * left is the location plus whatever was declared with `rememberSaved`.
 */
@Serializable
public data class SessionSnapshot(
    /** Where the session was, so a rejoining client that lost its address bar still lands right. */
    public val url: String,
    public val state: Map<String, JsonElement>,
    public val savedAtMillis: Long = System.currentTimeMillis(),
)

/**
 * Where hibernated sessions live.
 *
 * The interface is deliberately narrow so that the default in-memory implementation and a shared
 * one — Redis, a table, anything with a TTL — are interchangeable. Which one is in use decides how
 * much a session can survive: in memory it survives a disconnect, and shared it also survives the
 * server that created it going away, which is what makes a rolling deploy invisible to users.
 */
public interface SessionStore {
    public suspend fun save(token: String, snapshot: SessionSnapshot)
    public suspend fun load(token: String): SessionSnapshot?
    public suspend fun remove(token: String)
}

/**
 * Keeps hibernated sessions in this process.
 *
 * Enough to make a dropped connection or a closed laptop lid recoverable, which is the common case.
 * Sessions do not survive a restart, and a load balancer must keep a user on the node that holds
 * their snapshot — use a shared [SessionStore] to lift either limit.
 */
public class InMemorySessionStore(
    private val ttl: Duration = 30.minutes,
) : SessionStore {

    private val snapshots = ConcurrentHashMap<String, SessionSnapshot>()

    public val size: Int get() = snapshots.size

    override suspend fun save(token: String, snapshot: SessionSnapshot) {
        evictExpired()
        snapshots[token] = snapshot
    }

    override suspend fun load(token: String): SessionSnapshot? {
        val snapshot = snapshots[token] ?: return null
        if (snapshot.isExpired()) {
            snapshots.remove(token)
            return null
        }
        return snapshot
    }

    override suspend fun remove(token: String) {
        snapshots.remove(token)
    }

    // Swept on write rather than on a timer: hibernation is the only thing that grows this map, so
    // it cannot accumulate expired entries without something also arriving to trigger a sweep.
    private fun evictExpired() {
        if (snapshots.isEmpty()) return
        snapshots.entries.removeAll { it.value.isExpired() }
    }

    private fun SessionSnapshot.isExpired(): Boolean =
        System.currentTimeMillis() - savedAtMillis > ttl.inWholeMilliseconds
}
