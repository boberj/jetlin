package jetlin.db

/**
 * The writes an `update { }` block makes, held back until the block finishes.
 *
 * Each entity's generated draft extends this. Its setters record a value here instead of writing the
 * record, and its getters read the recorded value back, so a block sees its own writes. The record
 * itself doesn't change while the block runs. That's what lets [Gate.update] show the policy the
 * whole change at once, as a [Change], whatever order the assignments come in, and store nothing if
 * the policy refuses it.
 */
public abstract class Draft<T : Record> protected constructor() {
    private val pending = LinkedHashMap<Column<T>, Pending<*>>()

    /** Returns the value this block set for [column], or [current] if it didn't set one. */
    protected fun <V> read(column: Column<T>, current: V): V {
        val write = pending[column] ?: return current
        // Only `write` puts a value here, and the generated property passes the same `V` to both.
        @Suppress("UNCHECKED_CAST")
        return (write as Pending<V>).value
    }

    /** Records [value] for [column]. [store] writes it to the record once the block has been checked. */
    protected fun <V> write(column: Column<T>, value: V, store: (V) -> Unit) {
        pending[column] = Pending(value, store)
    }

    /** The columns this block set, in the order it first set them. */
    internal val pendingColumns: Set<Column<T>> get() = pending.keys

    /** Returns the value this block set for [column]. [column] must be one of [pendingColumns]. */
    internal fun pendingValue(column: Column<T>): Any? = pending.getValue(column).value

    /** Writes every recorded value to the record, except for the columns named in [except]. */
    internal fun storePending(except: Set<String> = emptySet()) {
        for ((column, write) in pending) {
            if (column.name !in except) write.store()
        }
    }

    private class Pending<V>(val value: V, private val storeValue: (V) -> Unit) {
        fun store() = storeValue(value)
    }
}
