package jetlin.db.testing

import jetlin.db.isNullable

/** Makes a [WorldSpec] smaller, one step at a time, while keeping it valid. */
internal object Shrinking {

    /**
     * Returns [spec] without the record at [index], and where every other record moved to, or `null`
     * if something else holds a non-null reference to it.
     *
     * Nullable references to the removed record become `null`.
     */
    fun remove(spec: WorldSpec, index: Int): Pair<WorldSpec, Map<Int, Int>>? {
        val moved = HashMap<Int, Int>()
        val records = mutableListOf<RecordSpec>()
        for ((old, record) in spec.records.withIndex()) {
            if (old == index) continue
            val values = LinkedHashMap<String, SpecValue>()
            for ((name, value) in record.values) {
                values[name] = when {
                    value !is SpecValue.Ref -> value
                    value.index == index -> if (record.table.column(name).isNullable) {
                        SpecValue.Plain(null)
                    } else {
                        return null
                    }
                    else -> SpecValue.Ref(moved.getValue(value.index))
                }
            }
            moved[old] = records.size
            records += RecordSpec(record.table, values)
        }
        return WorldSpec(records) to moved
    }

    /**
     * Returns [attempt] with its indices moved as [moved] says, or `null` if it involves a record
     * that was removed.
     */
    fun remap(attempt: Attempt, moved: Map<Int, Int>): Attempt? {
        fun move(value: SpecValue): SpecValue? = when (value) {
            is SpecValue.Plain -> value
            is SpecValue.Ref -> moved[value.index]?.let { SpecValue.Ref(it) }
        }
        fun moveAll(values: Map<String, SpecValue>): Map<String, SpecValue>? =
            values.mapValues { move(it.value) ?: return null }

        val principal = moved[attempt.principal] ?: return null
        return when (attempt) {
            is Attempt.Update -> Attempt.Update(principal, moved[attempt.target] ?: return null, moveAll(attempt.values) ?: return null)
            is Attempt.Create -> Attempt.Create(principal, RecordSpec(attempt.record.table, moveAll(attempt.record.values) ?: return null))
            is Attempt.Delete -> Attempt.Delete(principal, moved[attempt.target] ?: return null)
        }
    }

    /** Returns [spec] with the column [name] of the record at [index] set to [value]. */
    fun set(spec: WorldSpec, index: Int, name: String, value: SpecValue): WorldSpec =
        WorldSpec(spec.records.mapIndexed { i, record -> if (i == index) record.copy(values = record.values + (name to value)) else record })
}
