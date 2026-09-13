package jetlin.db

/**
 * What one transaction has to write to disk.
 *
 * Dirty tracking falls out of the cells rather than being computed: a write to a [Cell] records the
 * cell it happened to, so there is no persistence context to register objects with and no dirty-check
 * pass comparing the graph against a copy of itself.
 *
 * An insert supersedes the updates to the same record — the insert statement carries every column — and
 * a delete supersedes both. A record inserted and deleted inside one transaction is never written at
 * all.
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
        // A record that was only ever inserted in this transaction has never existed on disk, so there
        // is nothing to delete — dropping the insert is the whole of it.
        if (!inserted.remove(record)) deleted += record
    }
}

/**
 * The transaction this thread is inside, if any.
 *
 * A thread-local is the right ambient here precisely because [Db.transact] takes a non-suspending
 * block: the write set lives for exactly one dispatch, so it cannot outlive the coroutine that
 * installed it or leak into another session. A session's dispatcher guarantees one task at a time but
 * not one thread forever, and this relies only on the former.
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
