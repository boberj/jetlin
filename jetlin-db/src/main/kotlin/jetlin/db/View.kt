package jetlin.db

/**
 * A live, filtered list of records.
 *
 * A view doesn't store records. Every operation reads the identity map again, which is why
 * `db.todos` can be used directly in a composable. Iterating it subscribes the composition to the
 * underlying list and to everything the filter read. When a record is added, removed, or becomes
 * visible or invisible, only the compositions that depend on it recompose.
 *
 * Queries use the standard library: `filter`, `sortedBy`, `groupBy`, `count` and so on. There is no
 * query DSL. At the scale this framework is designed for, a linear scan of in-memory objects is
 * cheaper than parsing a query, and it can't get out of sync with the schema.
 *
 * The filter runs on every operation and is never cached. A cached result could outlive the state
 * the policy read, which would break reactive authorization.
 */
public class View<T : Record> internal constructor(
    private val records: List<T>,
    private val visible: (T) -> Boolean,
    private val gate: Gated<T, *>? = null,
) : List<T> {

    /**
     * Stores [record] if this principal may create it.
     *
     * This only works on a view of a whole table. On a derived view, such as `project.tasks`, it's not
     * clear what the record should be added to, so it throws instead of guessing.
     */
    public fun add(record: T): T {
        val gate = gate ?: error(
            "This view is derived, so there is nothing to add to. Add through the collection the " +
                "records belong to.",
        )
        return gate.add(record)
    }

    private fun resolved(): List<T> = records.filter(visible)

    override val size: Int get() = records.count(visible)

    override fun isEmpty(): Boolean = records.none(visible)

    override fun iterator(): Iterator<T> = resolved().iterator()

    override fun listIterator(): ListIterator<T> = resolved().listIterator()

    override fun listIterator(index: Int): ListIterator<T> = resolved().listIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<T> = resolved().subList(fromIndex, toIndex)

    override fun get(index: Int): T = resolved()[index]

    override fun indexOf(element: T): Int = resolved().indexOf(element)

    override fun lastIndexOf(element: T): Int = resolved().lastIndexOf(element)

    override fun contains(element: T): Boolean = records.any { it === element && visible(it) }

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    override fun toString(): String = resolved().toString()
}
