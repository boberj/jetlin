package jetlin.db

import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * One SQLite file, and the resident graph read out of it.
 *
 * ## The snapshot system is the transaction system
 *
 * Compose snapshots are MVCC — isolated reads, an atomic apply, conflict detection on merge — which is
 * the same shape as a database transaction. [transact] exploits that: it takes a mutable snapshot, runs
 * the block inside it, commits the resulting writes to SQLite, and only then applies the snapshot.
 *
 * Committing *before* applying is the point. A write the database refuses — a constraint violation, a
 * failed flush, or a block that threw — never becomes visible to any composition, so no session ever
 * renders a value the database rejected and there is nothing to roll back on screen. This is strictly
 * better than the usual optimistic-update-then-revert, and it is the single strongest argument for this
 * design. Anything that moves the commit after the apply has thrown it away.
 *
 * ## Residency
 *
 * Everything stored is in memory, as live objects, for the life of the process. Relation traversal is
 * therefore a pointer dereference: no query, no N+1, and — decisively — no read that can block the
 * single thread a session composes on. The cost is that memory is a real ceiling shared with the live
 * sessions, which is a cliff to document rather than discover.
 *
 * ## What this framework does not do for you
 *
 * Four things, in the order they are likely to bite.
 *
 * **A route guard is not the security boundary.** The row policy is. A guard is UX plus a cheap early
 * exit — it stops you rendering a page that would have been empty. If a guard is ever the only thing
 * protecting data, one forgotten guard is a leak. Keep the order: the lookup is gated, traversal is
 * gated, `update` is gated, and guards go on top of that, never instead of it.
 *
 * **A leaked reference is authority.** Access is checked where a record is *obtained*, not where its
 * fields are read, so a record that escapes the session that obtained it — cached in a companion object,
 * captured by a long-lived closure, stashed in a field — carries its access with it. [LeakDetector]
 * exists to make that findable in development and test builds; nothing makes it impossible.
 *
 * **Policies sit on the recomposition hot path.** A filtered collection evaluates its policy per row,
 * per read, and deliberately caches nothing, because a cached decision outlives the state it was based
 * on. Keep policies pure, cheap and free of IO. That is also what makes revocation reactive.
 *
 * **Transitive visibility on write is not caught.** Moving a project to another team changes who may
 * read its todos, but only the project's own policy is consulted — the todos' policies are not re-run
 * against the change, because nothing declares that their visibility depends on it. Reads are always
 * correct; it is the *write* that does not fan out. Out of scope for v1.
 */
public class Db private constructor(
    private val connection: Connection,
    private val tables: List<Table<out Record>>,
) : AutoCloseable {

    /** The resident graph. Obtaining a record from it is an authorization decision; see [IdentityMap]. */
    public val resident: IdentityMap = IdentityMap()

    private val tablesByType: Map<KClass<out Record>, Table<out Record>> =
        tables.associateBy { it.type }

    private val tableNames: Map<KClass<out Record>, String> =
        tables.associate { it.type to it.name }

    /**
     * `PRAGMA data_version` as of our last commit.
     *
     * SQLite leaves this value alone for changes made through this connection and bumps it for changes
     * made through any other, which makes it exactly the detector for the one thing that would quietly
     * invalidate the resident graph: another process writing the file.
     */
    private var dataVersion: Long = 0

    /**
     * Held for the whole of a transaction, so that transactions do not interleave.
     *
     * Two reasons, and the first is not optional: one JDBC connection cannot carry two transactions at
     * once, and `autoCommit` is connection-wide state. The second is that serializing writes makes
     * commit order and snapshot-apply order the same order, which is what [Record]'s last-write-wins
     * merge relies on to keep memory equal to the file.
     *
     * Reads are not serialized and never block: they go through the snapshot system, which is what that
     * system is for. SQLite allows one writer anyway, so a write lock is not a concession.
     */
    private val writeLock = ReentrantLock()

    public companion object {
        /**
         * Opens (or creates) the database at [path] and loads it.
         *
         * [tables] is also the load order, and a table's non-null references may only point at tables
         * declared before it — references are resolved against what is already resident, so a forward
         * reference fails with both tables named rather than leaving a hole in the graph.
         */
        public fun open(path: Path, tables: List<Table<out Record>>): Db {
            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            return try {
                Db(connection, tables).apply {
                    configure()
                    createSchema()
                    verifySchema()
                    dataVersion = readDataVersion()
                    load()
                }
            } catch (t: Throwable) {
                connection.close()
                throw t
            }
        }
    }

    /**
     * Runs [block] as one transaction: one snapshot, one SQLite commit, one recomposition.
     *
     * Not `suspend`, and [block] cannot suspend, which is deliberate twice over. An event handler is an
     * ordinary function, so a transaction has to be callable from one. And a non-suspending block
     * cannot await an external call, which makes "write the row and POST to an API atomically" — a
     * thing SQLite cannot honour, because a rollback cannot un-send a request — fail to compile rather
     * than fail in production.
     *
     * Calling this inside a transaction that is already open joins it rather than nesting: the writes
     * accumulate and the outermost call commits them together.
     */
    public fun <T> transact(block: () -> T): T {
        // Already inside one: join it. The outer call owns the snapshot, the lock and the commit.
        if (Transactions.current != null) return block()

        return writeLock.withLock {
            val writes = WriteSet()
            val snapshot = Snapshot.takeMutableSnapshot()
            try {
                val result = snapshot.enter {
                    Transactions.running(writes) {
                        block().also { commit(writes) }
                    }
                }
                // Last, and only if the database accepted everything: applying is what makes the change
                // visible to every session in the process.
                snapshot.apply().check()
                result
            } catch (t: Throwable) {
                snapshot.dispose()
                throw t
            }
        }
    }

    /**
     * Stores [row] and makes it resident.
     *
     * Ungated on purpose: the policy-checked way in is phase 4's `View.add`, which calls this. Nothing
     * public may reach it.
     */
    internal fun <T : Record> insert(row: T): T {
        val writes = requireTransaction(row)
        require(!row.stored) { "$row is already stored" }
        tableFor(row) // Fail now, with the class named, rather than at the flush.
        resident.add(row)
        writes.insert(row)
        return row
    }

    /** Removes [row] from disk and from the resident graph. Ungated; see [insert]. */
    internal fun delete(row: Record) {
        val writes = requireTransaction(row)
        resident.remove(row)
        writes.delete(row)
    }

    private fun requireTransaction(row: Record): WriteSet =
        Transactions.current ?: error(
            "Storing or removing $row has to happen inside db.transact { }, so that it is committed " +
                "before any session can see it.",
        )

    /**
     * Writes everything the block did, inside one SQLite transaction.
     *
     * Runs while the snapshot is still entered, because the values being written are the ones only
     * visible inside it.
     */
    private fun commit(writes: WriteSet) {
        if (writes.isEmpty) return
        verifySoleWriter()

        connection.autoCommit = false
        try {
            // Foreign keys checked at commit rather than per statement, which is what makes an order
            // possible at all: a transaction can create a row and the row that points at it, or point a
            // row away from something it then deletes, and no single statement order satisfies both while
            // every statement is checked on its own. Per-transaction, and SQLite clears it on commit.
            connection.createStatement().use { it.execute("PRAGMA defer_foreign_keys=ON") }

            // Deletes first, because a primary key is *not* deferred: re-seeding a fixture under an id it
            // used before has to free the id before claiming it. Updates last, so they can name a row this
            // transaction inserted.
            writes.deletes.forEach { deleteRow(it) }
            writes.inserts.forEach { insertRow(it) }
            writes.updates.forEach { (row, columns) -> updateRow(row, columns) }
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }

        // Only now, and not at the call: both flags are plain fields rather than snapshot state, so a
        // transaction that rolled back must not leave a record claiming to be stored.
        writes.inserts.forEach {
            it.stored = true
            it.database = this
        }
        writes.deletes.forEach {
            it.stored = false
            it.database = null
        }
        dataVersion = readDataVersion()
    }

    private fun <T : Record> insertRow(row: T) {
        val table = tableFor(row)
        val columns = table.columns
        val sql = buildString {
            append("INSERT INTO ").append(table.name).append(" (id")
            columns.forEach { append(", ").append(it.name) }
            append(") VALUES (?")
            repeat(columns.size) { append(", ?") }
            append(')')
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, row.id)
            columns.forEachIndexed { index, column -> bind(statement, index + 2, column.read(row)) }
            statement.executeUpdate()
        }
    }

    private fun <T : Record> updateRow(row: T, changed: Set<String>) {
        val table = tableFor(row)
        val columns = changed.map { name ->
            table.columnsByName[name]
                ?: error("${row::class.simpleName}.$name is not a column of '${table.name}'")
        }
        val sql = "UPDATE ${table.name} SET ${columns.joinToString { "${it.name} = ?" }} WHERE id = ?"
        connection.prepareStatement(sql).use { statement ->
            columns.forEachIndexed { index, column -> bind(statement, index + 1, column.read(row)) }
            statement.setLong(columns.size + 1, row.id)
            val updated = statement.executeUpdate()
            check(updated == 1) { "Updating $row changed $updated rows; it is not on disk" }
        }
    }

    private fun deleteRow(row: Record) {
        val table = tableFor(row)
        connection.prepareStatement("DELETE FROM ${table.name} WHERE id = ?").use { statement ->
            statement.setLong(1, row.id)
            statement.executeUpdate()
        }
    }

    /**
     * Pragmas, as §4.9 of the plan specifies them.
     *
     * WAL so that a reader — a backup tool, say — never blocks the writer. `synchronous=NORMAL`
     * because WAL makes it durable across a process crash, which is the failure worth surviving here;
     * only a power cut can lose the last commits. Foreign keys on, because they are off by default in
     * SQLite and silently so.
     */
    private fun configure() {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
        }
    }

    private fun createSchema() {
        connection.createStatement().use { statement ->
            tables.forEach { table -> statement.execute(table.ddl(tableNames)) }
        }
    }

    /**
     * Refuses to boot on a schema the entities do not describe.
     *
     * `CREATE TABLE IF NOT EXISTS` is silent about a table that already exists in a different shape, which
     * is exactly what an un-applied migration looks like. Without this the first symptom would be a failed
     * insert in production, or — worse — a column quietly never read.
     *
     * Compared as sets rather than in order: column order is not part of what a schema means, and a
     * rebuild in a migration is free to write them in a different one.
     */
    private fun verifySchema() {
        val problems = tables.flatMap { table -> problemsWith(table) }
        check(problems.isEmpty()) {
            buildString {
                appendLine("The database does not match the entities:")
                problems.forEach { appendLine("  * $it") }
                append("Run `./gradlew dbDiff --name=<what changed>` and `./gradlew dbMigrate`.")
            }
        }
    }

    private fun problemsWith(table: Table<out Record>): List<String> {
        val stored = storedColumns(table.name)
        if (stored.isEmpty()) return listOf("table '${table.name}' is missing")

        val problems = mutableListOf<String>()
        val references = storedReferences(table.name)
        for (column in table.columns) {
            val actual = stored[column.name]
            if (actual == null) {
                problems += "'${table.name}.${column.name}' is missing"
                continue
            }
            if (actual.type != column.type.sql) {
                problems += "'${table.name}.${column.name}' is ${actual.type}, declared ${column.type.sql}"
            }
            if (actual.nullable != column.nullable) {
                val state = if (actual.nullable) "optional" else "required"
                problems += "'${table.name}.${column.name}' is $state, declared the other way"
            }
            val target = column.references?.let { tableNames[it] }
            if (target != null && references[column.name] != target) {
                problems += "'${table.name}.${column.name}' does not reference '$target'"
            }
        }
        val undeclared = stored.keys - table.columns.map { it.name }.toSet() - "id"
        undeclared.forEach { problems += "'${table.name}.$it' is stored but no entity declares it" }
        return problems
    }

    private class StoredColumn(val type: String, val nullable: Boolean)

    private fun storedColumns(table: String): Map<String, StoredColumn> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { results ->
                buildMap {
                    while (results.next()) {
                        val primaryKey = results.getInt("pk") == 1
                        put(
                            results.getString("name"),
                            StoredColumn(
                                type = results.getString("type"),
                                // SQLite reports an INTEGER PRIMARY KEY as nullable because it is the rowid
                                // alias and NULL there means "assign one". It is never really null.
                                nullable = !primaryKey && results.getInt("notnull") == 0,
                            ),
                        )
                    }
                }
            }
        }

    private fun storedReferences(table: String): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_list($table)").use { results ->
                buildMap {
                    while (results.next()) put(results.getString("from"), results.getString("table"))
                }
            }
        }

    /** Reads every table into the identity map, resolving references as it goes. */
    private fun load() {
        tables.forEach { table -> loadTable(table) }
    }

    private fun <T : Record> loadTable(table: Table<T>) {
        val columns = table.columns.map { it.name }
        val sql = "SELECT id${columns.joinToString("") { ", $it" }} FROM ${table.name} ORDER BY id"
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { results ->
                while (results.next()) {
                    val values = columns.associateWith { name -> results.getObject(name) }
                    val id = results.getLong("id")
                    val row = table.instantiate(Row(values, resident))
                    row.adoptStoredId(id)
                    row.stored = true
                    row.database = this
                    Ids.advanceTo(table.type, id)
                    resident.add(row)
                }
            }
        }
    }

    /**
     * Refuses to write if another connection has changed the file since our last commit.
     *
     * The resident graph is the working copy of the whole database, so an outside write does not
     * conflict with one row — it invalidates everything in memory. There is no sound way to merge that,
     * which leaves detecting it loudly.
     *
     * An exclusive lock would prevent it outright, and §4.9 asks for one, but it also locks out
     * readers, and the same section promises Litestream. A backup tool that cannot read the file is
     * worth less than a guarantee that is enforced one commit late, so this is the trade taken; see the
     * decision log.
     */
    private fun verifySoleWriter() {
        val current = readDataVersion()
        check(current == dataVersion) {
            "Another connection has written this database (PRAGMA data_version moved from $dataVersion " +
                "to $current). The resident graph is a copy of the whole file, so it is now stale in " +
                "ways that cannot be merged. One process may write a jetlin-db database; readers, " +
                "including backup tools, are fine."
        }
    }

    private fun readDataVersion(): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA data_version").use { results ->
                results.next()
                results.getLong(1)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Record> tableFor(row: T): Table<T> =
        (tablesByType[row::class] as Table<T>?)
            ?: error("No table is registered for ${row::class.simpleName}")

    override fun close() {
        connection.close()
    }
}
