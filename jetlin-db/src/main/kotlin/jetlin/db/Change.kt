package jetlin.db

import androidx.compose.runtime.snapshots.Snapshot
import kotlin.reflect.KProperty1

/**
 * A change that an `update { }` block wants to make to one record, before anything is saved.
 *
 * The framework passes it to [Policy.canChange], which decides whether the change is allowed. It
 * holds three things:
 *
 * - [record]: the record as it is now.
 * - [columns] and [newValue]: which columns change, and to what.
 * - [afterwards]: a way to look at the record as it would be after the change.
 *
 * For example, when Alice runs `todo.update { title = "Buy milk"; done = true }` on a todo that
 * isn't done yet:
 *
 * ```kotlin
 * change.record.title              // "Buy bread", the title now
 * change.newValue(Todo::title)     // "Buy milk"
 * change.columns                   // setOf(Todos.title, Todos.done)
 * change.afterwards { it.title }   // "Buy milk"
 * ```
 *
 * You only use this class if you write [Policy.canChange] yourself. Policies built with [policy]
 * handle it for you.
 */
public class Change<T : Record> internal constructor(
    /** The record as it is now, before the change. */
    public val record: T,
    private val draft: Draft<T>,
) {
    /**
     * The columns whose value changes, in the order the block first set them.
     *
     * A column the block sets to the value it already has isn't included, so
     * `update { title = title }` changes nothing.
     */
    public val columns: Set<Column<T>> = draft.pendingColumns
        .filterTo(LinkedHashSet()) { column -> draft.pendingValue(column) != currentValue(column) }

    /**
     * Checks whether this change gives [property] a new value.
     *
     * @return True if [property]'s value changes; false otherwise.
     */
    public fun changes(property: KProperty1<T, *>): Boolean = columns.any { it.name == property.name }

    /**
     * Gets the value [property] would have after this change.
     *
     * If the change doesn't set [property], that's its current value.
     *
     * @return The value after the change.
     */
    public fun <V> newValue(property: KProperty1<T, V>): V {
        val column = draft.pendingColumns.firstOrNull { it.name == property.name } ?: return property.get(record)
        // The draft stored this value through the generated property of the same name and type.
        @Suppress("UNCHECKED_CAST")
        return draft.pendingValue(column) as V
    }

    /**
     * Runs [check] on the record as it would be after this change, then discards the change.
     *
     * Inside [check], the record has its new values, and so does everything that reads it:
     * relations, collections, and other records' policies. Nothing is saved, and no page sees the
     * change. This works whether or not a transaction is open.
     *
     * ```kotlin
     * // Allowed only if the principal still owns the document afterwards.
     * change.afterwards { doc -> doc.owner == principal }
     * ```
     *
     * [check] receives [record] itself, so identity comparisons such as `doc == principal` work.
     *
     * @return The value [check] returns.
     */
    public fun <R> afterwards(check: (T) -> R): R = afterwards(keeping = emptySet(), check)

    /**
     * Runs [check] on the record as it would be after this change, except that the columns named in
     * [keeping] keep their current values.
     *
     * A reassignment uses this to check everything else about a change as if the reassigned column
     * hadn't moved.
     */
    internal fun <R> afterwards(keeping: Set<String>, check: (T) -> R): R =
        Trials.run(apply = { draft.storePending(except = keeping) }) { check(record) }

    /** Gets the value [column] would have after this change. */
    internal fun newValueOf(column: Column<T>): Any? =
        if (column in draft.pendingColumns) draft.pendingValue(column) else currentValue(column)

    private fun currentValue(column: Column<T>): Any? = record.cells.getValue(column.name).value
}

/**
 * Runs code against changes that are never kept.
 *
 * A trial takes a nested snapshot, applies some writes inside it, runs a check, and throws the
 * snapshot away. Everything the check reads sees the writes, but nothing outside the trial ever does.
 * While a trial runs, writes to stored records aren't added to the open transaction's write set,
 * and are allowed even when no transaction is open. They're discarded, so there's nothing to save.
 */
internal object Trials {
    private val depth = ThreadLocal.withInitial { 0 }

    /** Whether a trial is running on this thread. [Record.recordWrite] checks it. */
    val active: Boolean get() = depth.get() > 0

    /** Runs [apply] and then [check] in a snapshot that's discarded afterwards. */
    fun <R> run(apply: () -> Unit, check: () -> R): R {
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            return snapshot.enter {
                depth.set(depth.get() + 1)
                try {
                    apply()
                    check()
                } finally {
                    depth.set(depth.get() - 1)
                }
            }
        } finally {
            snapshot.dispose()
        }
    }
}
