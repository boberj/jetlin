package jetlin.db

/**
 * The writes an `update { }` block makes, held back until the block finishes.
 *
 * Each entity's generated draft extends this. Its setters record a value here instead of writing the
 * record, and its getters read the recorded value back, so a block sees its own writes. The record
 * itself doesn't change while the block runs. That's what lets [Gate.update] check every column
 * against the record as it was before the block, whatever order the assignments come in, and then
 * check the finished record as a whole.
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

    /** Writes every recorded value to the record. */
    internal fun storePending() {
        for (write in pending.values) write.store()
    }

    private class Pending<V>(val value: V, private val storeValue: (V) -> Unit) {
        fun store() = storeValue(value)
    }
}
