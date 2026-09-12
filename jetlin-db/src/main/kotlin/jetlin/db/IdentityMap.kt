package jetlin.db

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * The resident graph: one live object per stored row, indexed by type and id.
 *
 * Residency is what lets relation traversal be a pointer dereference. A composable runs on a
 * thread-confined session dispatcher, so a lazily loaded relation would have to block it or become
 * asynchronous; keeping the working set in memory removes the choice. The cost is that memory is a
 * real ceiling, shared with the live session compositions.
 *
 * Each type's rows are held in a [SnapshotStateList], so adding or removing a row invalidates the
 * compositions that had iterated it — across every session in the process, because the map is
 * process-wide and the lists are ordinary snapshot state.
 *
 * ## Why almost nothing here is public
 *
 * Handing out a record is an authorization decision. This class does no authorization, so its
 * record-returning members are `internal`: the public way to obtain a record is a policy-gated
 * [View], which is the guardrail that makes "a reference is authority" survivable. Phase 4 puts that
 * gate on top of these members; it must never become possible to go around it.
 */
public class IdentityMap {

    private val tables = ConcurrentHashMap<KClass<out Record>, SnapshotStateList<Record>>()

    /** Rows of [type] in insertion order, as live snapshot state. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Record> rows(type: KClass<T>): SnapshotStateList<T> =
        tables.computeIfAbsent(type) { mutableStateListOf() } as SnapshotStateList<T>

    /** Every resident row of [type], unfiltered. */
    internal fun <T : Record> all(type: KClass<T>): View<T> = View(rows(type), { true })

    /**
     * Makes [row] resident.
     *
     * Registration and persistence are separate steps on purpose: a record exists as an object before
     * anything has been stored, which is what lets a constructor be an ordinary constructor.
     */
    internal fun <T : Record> add(row: T): T {
        @Suppress("UNCHECKED_CAST")
        val table = rows(row::class as KClass<T>)
        require(table.none { it.id == row.id }) { "$row is already resident" }
        table += row
        return row
    }

    @Suppress("UNCHECKED_CAST")
    internal fun remove(row: Record) {
        rows(row::class as KClass<Record>).remove(row)
    }

    /**
     * The resident row of [type] with this id, or null.
     *
     * A scan rather than an index: at the target scale it costs less than maintaining a second
     * structure, and — more importantly — scanning the snapshot list subscribes the caller, so a
     * composable that looked up a row that did not exist yet recomposes when it arrives.
     */
    internal fun <T : Record> find(type: KClass<T>, id: Id<T>): T? =
        rows(type).firstOrNull { it.id == id.value }

    /** Total resident rows, for reporting graph size next to session size; see `samples:teams:benchmark`. */
    public val rowCount: Int get() = tables.values.sumOf { it.size }
}
