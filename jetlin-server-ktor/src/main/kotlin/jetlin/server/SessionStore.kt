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
    /**
     * Format of this envelope.
     *
     * Individual saved values already tolerate a changed shape by falling back to their initializer.
     * This is the same idea one level up: a store that serializes the envelope needs somewhere to
     * hang a migration, and adding the field after the fact would itself be the breaking change.
     */
    public val version: Int = CURRENT_VERSION,
) {
    public companion object {
        public const val CURRENT_VERSION: Int = 1
    }
}

/**
 * Where hibernated sessions live.
 *
 * Jetlin currently assumes a single node, so the in-memory implementation is the whole story: a
 * session survives a dropped connection and a closed laptop lid, but not a restart. A shared
 * implementation — Redis, a table, anything with a TTL — would additionally survive the process,
 * and this interface is shaped so that it can be written without changing anything above it.
 *
 * A shared store on its own would not make Jetlin multi-node, though. It only ever holds
 * *hibernated* sessions, so another node still could not pick up a session that is live, mid-handoff
 * between the page render and the socket connecting, or inside its disconnect grace period. See
 * `docs/architecture.md` for what those windows would need.
 */
public interface SessionStore {

    public suspend fun save(token: String, snapshot: SessionSnapshot)

    /**
     * Atomically returns the snapshot for [token] and removes it, or null if there is none.
     *
     * Atomic because waking is a transfer of ownership, not a read: two sockets can quote the same
     * token at once — a reconnect racing a retry, two tabs restored from the same saved page — and
     * exactly one of them must end up owning the session. A separate read and delete would let both
     * build a composition from the same snapshot, leaving one of them live, attached, and invisible
     * to the reaper that should collect it.
     */
    public suspend fun take(token: String): SessionSnapshot?
}

/**
 * Keeps hibernated sessions in this process.
 *
 * Enough to make a dropped connection or a closed laptop lid recoverable, which is the common case.
 * Sessions do not survive a restart.
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

    // ConcurrentHashMap.remove returns the previous value as one operation, which is the atomicity
    // the interface asks for: concurrent callers cannot both come away with the same snapshot.
    override suspend fun take(token: String): SessionSnapshot? =
        snapshots.remove(token)?.takeUnless { it.isExpired() }

    // Swept on write rather than on a timer: hibernation is the only thing that grows this map, so
    // it cannot accumulate expired entries without something also arriving to trigger a sweep.
    private fun evictExpired() {
        if (snapshots.isEmpty()) return
        snapshots.entries.removeAll { it.value.isExpired() }
    }

    private fun SessionSnapshot.isExpired(): Boolean =
        System.currentTimeMillis() - savedAtMillis > ttl.inWholeMilliseconds
}
