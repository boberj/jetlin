package jetlin.db

/**
 * A live, filtered list of records.
 *
 * A view is not a query result and holds no rows of its own: it reads the identity map every time it
 * is asked something. That is what makes `db.todos` usable straight from a composable — iterating it
 * subscribes the composition to the underlying list *and* to whatever the filter read, so a row
 * appearing, vanishing or ceasing to be visible recomposes exactly the readers that would care.
 *
 * Ordinary stdlib operations are the query language: `filter`, `sortedBy`, `groupBy`, `count`. There
 * is deliberately no DSL — at the scale this framework targets a linear scan over resident objects is
 * cheaper than parsing anything, and a scan cannot drift out of step with the schema.
 *
 * The filter is applied on every operation rather than cached, because caching it is exactly how
 * reactive authorization gets broken: a cached decision outlives the state the policy read.
 */
public class View<T : Record> internal constructor(
    private val rows: List<T>,
    private val visible: (T) -> Boolean,
    private val gate: Gated<T, *>? = null,
) : List<T> {

    /**
     * Stores [row], if this viewer may create it.
     *
     * Only on a view that is a whole collection. A derived one — `project.tasks`, or anything that came
     * out of `filter` — has no answer to "added to what", and silently adding to the wrong place is
     * worse than not offering it.
     */
    public fun add(row: T): T {
        val gate = gate ?: error(
            "This view is derived, so there is nothing to add to. Add through the collection the " +
                "records belong to.",
        )
        return gate.add(row)
    }

    private fun resolved(): List<T> = rows.filter(visible)

    override val size: Int get() = rows.count(visible)

    override fun isEmpty(): Boolean = rows.none(visible)

    override fun iterator(): Iterator<T> = resolved().iterator()

    override fun listIterator(): ListIterator<T> = resolved().listIterator()

    override fun listIterator(index: Int): ListIterator<T> = resolved().listIterator(index)

    override fun subList(fromIndex: Int, toIndex: Int): List<T> = resolved().subList(fromIndex, toIndex)

    override fun get(index: Int): T = resolved()[index]

    override fun indexOf(element: T): Int = resolved().indexOf(element)

    override fun lastIndexOf(element: T): Int = resolved().lastIndexOf(element)

    override fun contains(element: T): Boolean = rows.any { it === element && visible(it) }

    override fun containsAll(elements: Collection<T>): Boolean = elements.all { contains(it) }

    override fun toString(): String = resolved().toString()
}
