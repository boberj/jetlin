package jetlin.server

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A session reduced to what's worth keeping while nobody is using it.
 *
 * It's small by design. The composition, the node tree, and every `remember` are gone. What's left
 * is the location and whatever was declared with `rememberSaved`.
 */
@Serializable
public data class SessionSnapshot(
    /** Where the session was, so a returning client that lost its address bar still lands there. */
    public val url: String,
    /** The values declared with `rememberSaved`, by key. */
    public val state: Map<String, JsonElement>,
    /** When the session hibernated, in milliseconds since the epoch. */
    public val savedAtMillis: Long = System.currentTimeMillis(),
    /**
     * The format version of this snapshot.
     *
     * Each saved value already copes with a changed shape by falling back to its initial value.
     * This field applies the same idea one level up. A store that serializes the snapshot needs a
     * place to hook in a migration, and adding the field later would itself be a breaking change.
     */
    public val version: Int = CURRENT_VERSION,
) {
    public companion object {
        /** The format version that this code writes. */
        public const val CURRENT_VERSION: Int = 1
    }
}

/**
 * Where hibernated sessions are kept.
 *
 * Jetlin assumes a single node, so the in-memory implementation is all it needs today. A session
 * survives a dropped connection and a closed laptop lid, but not a restart. A shared implementation,
 * such as Redis, a database table, or anything with a TTL, would also survive the process, and you
 * can write one without changing anything above this interface.
 *
 * A shared store alone wouldn't make Jetlin run on several nodes, though. It only holds hibernated
 * sessions, so another node still couldn't pick up a session that's live, between the page render
 * and the socket connecting, or in its disconnect grace period. See `docs/architecture.md` for what
 * those cases would need.
 */
public interface SessionStore {

    /** Stores [snapshot] under [token], replacing any snapshot already there. */
    public suspend fun save(token: String, snapshot: SessionSnapshot)

    /**
     * Atomically removes and returns the snapshot for [token].
     *
     * It must be atomic, because waking a session transfers ownership. It isn't a read. Two sockets
     * can send the same token at once, for example a reconnect racing a retry, or two tabs restored
     * from the same saved page, and exactly one of them must end up owning the session. With a
     * separate read and delete, both could build a composition from the same snapshot, and one of
     * them would be live, attached, and invisible to the reaper that should collect it.
     *
     * @return the snapshot, or `null` if there's none.
     */
    public suspend fun take(token: String): SessionSnapshot?
}

/**
 * Keeps hibernated sessions in this process.
 *
 * That's enough to recover from a dropped connection or a closed laptop lid, which is the common
 * case. Sessions don't survive a restart.
 *
 * @param ttl how long a snapshot is kept. An expired snapshot can't be taken.
 */
public class InMemorySessionStore(
    private val ttl: Duration = 30.minutes,
) : SessionStore {

    private val snapshots = ConcurrentHashMap<String, SessionSnapshot>()

    /** The number of snapshots held, including expired ones that haven't been evicted yet. */
    public val size: Int get() = snapshots.size

    override suspend fun save(token: String, snapshot: SessionSnapshot) {
        evictExpired()
        snapshots[token] = snapshot
    }

    // ConcurrentHashMap.remove returns the previous value in one operation, which is the atomicity
    // the interface requires: concurrent callers can't both get the same snapshot.
    override suspend fun take(token: String): SessionSnapshot? =
        snapshots.remove(token)?.takeUnless { it.isExpired() }

    // Sweep on write instead of on a timer. Only hibernation grows this map, so expired entries
    // can't pile up without a write arriving to trigger a sweep.
    private fun evictExpired() {
        if (snapshots.isEmpty()) return
        snapshots.entries.removeAll { it.value.isExpired() }
    }

    /** Whether this snapshot is older than [ttl]. */
    private fun SessionSnapshot.isExpired(): Boolean =
        System.currentTimeMillis() - savedAtMillis > ttl.inWholeMilliseconds
}
