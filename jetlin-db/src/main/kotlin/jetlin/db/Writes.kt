package jetlin.db

/**
 * The changes one transaction needs to write to the database.
 *
 * Each write to a [Cell] adds that cell here, so changes are tracked as they happen. There's no
 * persistence context to register objects with, and no pass that compares the graph to an earlier
 * copy to find what changed.
 *
 * An insert makes updates to the same record unnecessary, because the insert writes every column.
 * A delete makes both unnecessary. A record inserted and deleted in the same transaction is never
 * written at all.
 */
internal class WriteSet {
    private val inserted = LinkedHashSet<Record>()
    private val updated = LinkedHashMap<Record, MutableSet<String>>()
    private val deleted = LinkedHashSet<Record>()

    /** The records to insert, in order. */
    val inserts: Collection<Record> get() = inserted

    /** The records to update, each with the names of its changed columns. */
    val updates: Map<Record, Set<String>> get() = updated

    /** The records to delete, in order. */
    val deletes: Collection<Record> get() = deleted

    /** Whether there's nothing to write. */
    val isEmpty: Boolean get() = inserted.isEmpty() && updated.isEmpty() && deleted.isEmpty()

    /** Records that [record] needs inserting. */
    fun insert(record: Record) {
        inserted += record
    }

    /** Records that [cell]'s column changed, unless its record is being inserted anyway. */
    fun update(cell: Cell<*>) {
        if (cell.record in inserted) return
        updated.getOrPut(cell.record) { LinkedHashSet() } += cell.name
    }

    /** Records that [record] needs deleting, and drops its pending insert or updates. */
    fun delete(record: Record) {
        updated.remove(record)
        // If the record was inserted in this transaction, it isn't on disk yet. Dropping the pending
        // insert is enough, because there's no row to delete.
        if (!inserted.remove(record)) deleted += record
    }
}

/**
 * The transaction open on the current thread, if any.
 *
 * A thread-local works here because [Db.transact] takes a block that can't suspend. The write set
 * is set and cleared within one uninterrupted call, so it can't move to another thread with a
 * coroutine or leak into another session. A session's dispatcher runs one task at a time but
 * doesn't always use the same thread, and this relies only on the first of those.
 */
internal object Transactions {
    private val active = ThreadLocal<WriteSet?>()

    /** The current thread's open transaction, or `null` if there's none. */
    val current: WriteSet? get() = active.get()

    /**
     * Runs [block] with [writes] as the current thread's transaction.
     *
     * @throws IllegalStateException if a transaction is already running on this thread.
     */
    fun <T> running(writes: WriteSet, block: () -> T): T {
        val previous = active.get()
        check(previous == null) { "A transaction is already running on this thread" }
        active.set(writes)
        return try {
            block()
        } finally {
            active.set(previous)
        }
    }
}
