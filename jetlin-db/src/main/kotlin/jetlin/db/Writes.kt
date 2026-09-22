package jetlin.db

/**
 * The changes one transaction needs to write to the database.
 *
 * Each write to a [Cell] adds that cell here, so changes are tracked as they happen. There is no
 * persistence context to register objects with, and no pass that compares the graph to an earlier
 * copy to find what changed.
 *
 * An insert makes updates to the same record unnecessary, because the insert writes every column. A
 * delete makes both unnecessary. A record inserted and deleted in the same transaction is never
 * written at all.
 */
internal class WriteSet {
    private val inserted = LinkedHashSet<Record>()
    private val updated = LinkedHashMap<Record, MutableSet<String>>()
    private val deleted = LinkedHashSet<Record>()

    val inserts: Collection<Record> get() = inserted
    val updates: Map<Record, Set<String>> get() = updated
    val deletes: Collection<Record> get() = deleted

    val isEmpty: Boolean get() = inserted.isEmpty() && updated.isEmpty() && deleted.isEmpty()

    fun insert(record: Record) {
        inserted += record
    }

    fun update(cell: Cell<*>) {
        if (cell.record in inserted) return
        updated.getOrPut(cell.record) { LinkedHashSet() } += cell.name
    }

    fun delete(record: Record) {
        updated.remove(record)
        // If the record was inserted in this transaction, it isn't on disk yet. Dropping the pending
        // insert is enough; there is no row to delete.
        if (!inserted.remove(record)) deleted += record
    }
}

/**
 * The transaction open on the current thread, if any.
 *
 * A thread-local works here because [Db.transact] takes a block that can't suspend. The write set is
 * set and cleared within one uninterrupted call, so it can't move to another thread with a coroutine
 * or leak into another session. A session's dispatcher runs one task at a time but doesn't always use
 * the same thread; this only relies on the first of those.
 */
internal object Transactions {
    private val active = ThreadLocal<WriteSet?>()

    val current: WriteSet? get() = active.get()

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
