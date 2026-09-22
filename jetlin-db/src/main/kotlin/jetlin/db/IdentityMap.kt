package jetlin.db

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * The in-memory graph: one live object per stored row, indexed by type and id.
 *
 * Keeping every record in memory means following a relation is a field access. Composables run on a
 * session's single confined thread, so a lazily loaded relation would either block that thread or
 * have to become asynchronous. Keeping the data resident avoids both, at the cost of memory, which is
 * shared with the live sessions and limits how much data an application can hold.
 *
 * Each type's records are kept in a [SnapshotStateList]. Adding or removing a record therefore
 * invalidates every composition that iterated the list, in every session, because the map is shared
 * across the process and the lists are ordinary snapshot state.
 *
 * ## Why most members are internal
 *
 * Returning a record to application code grants access to it, and this class performs no access
 * checks. Its record-returning members are therefore `internal`. Application code obtains records
 * through [Gate], which returns policy-filtered [View]s. There must be no public way around the gate.
 */
public class IdentityMap {

    private val tables = ConcurrentHashMap<KClass<out Record>, SnapshotStateList<Record>>()

    /** The records of [type] in insertion order, as a snapshot state list. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Record> records(type: KClass<T>): SnapshotStateList<T> =
        tables.computeIfAbsent(type) { mutableStateListOf() } as SnapshotStateList<T>

    /** All records of [type], without any policy filtering. */
    internal fun <T : Record> all(type: KClass<T>): View<T> = View(records(type), { true })

    /**
     * Adds [record] to the in-memory graph.
     *
     * This is separate from storing the record in the database. A record is created as an ordinary
     * object before it is stored, which lets entity constructors be normal constructors.
     */
    internal fun <T : Record> add(record: T): T {
        @Suppress("UNCHECKED_CAST")
        val table = records(record::class as KClass<T>)
        require(table.none { it.id == record.id }) { "$record is already resident" }
        table += record
        return record
    }

    @Suppress("UNCHECKED_CAST")
    internal fun remove(record: Record) {
        records(record::class as KClass<Record>).remove(record)
    }

    /**
     * Returns the record of [type] with this id, or null.
     *
     * This scans the list instead of using an index. At the intended scale, a scan is cheaper than
     * maintaining a second data structure. It also subscribes the caller to the list, so a composable
     * that looked up a record before it existed recomposes when the record is added.
     */
    internal fun <T : Record> find(type: KClass<T>, id: Id<T>): T? =
        records(type).firstOrNull { it.id == id.value }

    /** The total number of records in memory. Used by `samples:teams:benchmark` to report memory per record. */
    public val recordCount: Int get() = tables.values.sumOf { it.size }
}
