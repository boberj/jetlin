package jetlin.db

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

/**
 * The in-memory graph: one live object per stored row, indexed by type and ID.
 *
 * With every record in memory, following a relation is a field access. Composables run on a
 * session's single thread, so a lazily loaded relation would either block that thread or have to
 * become asynchronous. Keeping the data in memory avoids both, at the cost of memory, which the live
 * sessions share, and which limits how much data an application can hold.
 *
 * Each type's records are kept in a [SnapshotStateList]. So adding or removing a record invalidates
 * every composition that iterated the list, in every session, because the whole process shares the
 * map, and the lists are ordinary snapshot state.
 *
 * ## Why most members are internal
 *
 * Returning a record to application code grants access to it, and this class performs no access
 * checks. So its members that return records are `internal`. Application code obtains records
 * through [Gate], which returns policy-filtered [View]s. There must be no public way around the gate.
 */
public class IdentityMap {

    private val tables = ConcurrentHashMap<KClass<out Record>, SnapshotStateList<Record>>()

    /** Returns the records of [type] in insertion order, as a snapshot state list. */
    @Suppress("UNCHECKED_CAST")
    internal fun <T : Record> records(type: KClass<T>): SnapshotStateList<T> =
        tables.computeIfAbsent(type) { mutableStateListOf() } as SnapshotStateList<T>

    /** Returns all records of [type], without any policy filtering. */
    internal fun <T : Record> all(type: KClass<T>): View<T> = View(records(type), { true })

    /**
     * Adds [record] to the in-memory graph.
     *
     * This is separate from storing the record in the database. A record is created as an ordinary
     * object before it's stored, which lets entity constructors be normal constructors.
     *
     * @throws IllegalArgumentException if a record of the same type with the same ID is already here.
     */
    internal fun <T : Record> add(record: T): T {
        @Suppress("UNCHECKED_CAST")
        val table = records(record::class as KClass<T>)
        require(table.none { it.id == record.id }) { "$record is already resident" }
        table += record
        return record
    }

    /** Removes [record] from the in-memory graph. */
    @Suppress("UNCHECKED_CAST")
    internal fun remove(record: Record) {
        records(record::class as KClass<Record>).remove(record)
    }

    /**
     * Returns the record of [type] with [id], or `null` if there's none.
     *
     * This scans the list instead of using an index. At the intended scale, a scan is cheaper than
     * maintaining a second data structure. It also subscribes the caller to the list, so a composable
     * that looked up a record before it existed recomposes when the record is added.
     */
    internal fun <T : Record> find(type: KClass<T>, id: Id<T>): T? =
        records(type).firstOrNull { it.id == id.value }

    /**
     * The total number of records in memory. `samples:teams:benchmark` uses it to report memory per
     * record.
     */
    public val recordCount: Int get() = tables.values.sumOf { it.size }
}
