package jetlin.db

import androidx.compose.runtime.snapshots.Snapshot
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.reflect.KClass

/**
 * A SQLite database file and the in-memory graph of records loaded from it.
 *
 * ## Transactions are snapshots
 *
 * Compose snapshots provide multiversion concurrency control: isolated reads, atomic apply, and
 * conflict detection on merge. That's the same model as a database transaction, so [transact] uses a
 * snapshot as one. It takes a mutable snapshot, runs the block inside it, commits the resulting
 * writes to SQLite, and applies the snapshot only after the commit succeeds.
 *
 * The order is what matters. If the database rejects a write, because of a constraint violation, a
 * failed flush, or an exception from the block, the snapshot is discarded, and no composition ever
 * sees the change. No session renders a value that the database refused, so there's nothing to
 * revert on screen. With the common approach of updating optimistically and reverting on failure,
 * the user briefly sees a wrong value. Here, they never do. That's the main reason for the design,
 * and any change that applies the snapshot before committing would lose it.
 *
 * ## Residency
 *
 * Every stored record stays in memory as a live object for the life of the process. Following a
 * relation is a field access, so there are no queries, no N+1 problem, and, most importantly, no
 * reads that block the single thread a session composes on. The cost is memory, which the live
 * sessions share, and which limits how much data an application can hold. `docs/db.md` §6 has the
 * measured figures.
 *
 * ## Limitations
 *
 * These are listed roughly in order of how likely they are to cause a problem:
 *
 * - Route guards aren't the security boundary. Record policies are. A guard improves the user
 *   experience and avoids rendering a page that would be empty, but if a guard is the only thing
 *   protecting some data, forgetting the guard leaks the data. Lookups, traversal, and `update` are
 *   all checked against policies. Guards are an extra layer on top of those checks, not a
 *   replacement.
 * - Holding a record reference grants access to it. Access is checked when code obtains a record,
 *   not each time it reads a field. A record that outlives the session that obtained it, for
 *   example in a companion object cache, a long-lived closure, or a field, can be read by whoever
 *   ends up holding it. [LeakDetector] can find such leaks in development and test builds, but
 *   nothing prevents them.
 * - Policies run during recomposition. A filtered collection runs its policy for each record on
 *   every read, and caches nothing, because a cached decision can outlive the state it was based
 *   on. So policies must be pure, cheap, and free of I/O. Not caching is also what makes revocation
 *   reactive.
 * - Visibility changes aren't propagated through relations on write. Moving a project to another
 *   team changes who can read its todos, but only the project's policy runs for that write. Nothing
 *   declares that a todo's visibility depends on its project, so the todos' policies don't run
 *   again. Reads always give the right answer. What's missing is invalidating pages that were
 *   already showing those todos. This is out of scope for the first version.
 */
public class Db private constructor(
    private val connection: Connection,
    private val tables: List<Table<out Record>>,
) : AutoCloseable {

    /**
     * The in-memory graph of records. Getting a record out of it requires an access check. See
     * [IdentityMap].
     */
    public val resident: IdentityMap = IdentityMap()

    /** Each entity class's table. */
    private val tablesByType: Map<KClass<out Record>, Table<out Record>> =
        tables.associateBy { it.type }

    /** Each entity class's table name. */
    private val tableNames: Map<KClass<out Record>, String> =
        tables.associate { it.type to it.name }

    /**
     * The value of `PRAGMA data_version` after this connection's last commit.
     *
     * SQLite increments this value when another connection changes the file, but not for changes made
     * through this connection. That makes it a reliable way to detect another process writing the
     * file, which would make the in-memory graph out of date without any error.
     */
    private var dataVersion: Long = 0

    /**
     * Held for the whole of each transaction, so transactions run one at a time.
     *
     * There are two reasons for this. A JDBC connection can't run two transactions at once, and
     * `autoCommit` applies to the whole connection. Running them one at a time also makes commits and
     * snapshot applies happen in the same order, which the last-write-wins merge policy in [Record]
     * relies on to keep memory consistent with the file.
     *
     * Reads aren't serialized and never block, because they go through the snapshot system. SQLite
     * allows only one writer at a time anyway, so this lock costs no concurrency.
     */
    private val writeLock = ReentrantLock()

    public companion object {
        /**
         * Opens the database at [path], creating it if necessary, and loads every table into memory.
         *
         * Tables are loaded in the order of [tables]. A non-null reference can point only to a table
         * earlier in the list, because references are resolved against records that are already
         * loaded. A reference to a later table fails with an error that names both tables.
         *
         * @throws IllegalStateException if the stored schema doesn't match the entities, or a
         *   reference points to a table later in the list.
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
     * Runs [block] as a single transaction: one snapshot, one SQLite commit, and one recomposition.
     *
     * Neither this function nor [block] is `suspend`, for two reasons. Event handlers aren't
     * suspending functions, and they need to call this. And a block that can't suspend can't await a
     * call to an external service. A rollback can't undo an HTTP request, so a database write and an
     * API call in one transaction can't be atomic. With this signature, trying to combine them is a
     * compile error.
     *
     * A call made while a transaction is already open joins that transaction instead of nesting. Its
     * writes are added to the outer transaction and committed with it.
     *
     * @return the value [block] returns.
     * @throws Throwable whatever [block] or the commit threw. The transaction is then rolled back,
     *   and no composition sees any of its changes.
     */
    public fun <T> transact(block: () -> T): T {
        // A transaction is already open, so join it. The outer call owns the snapshot, lock and commit.
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
                // Apply the snapshot last, after the commit succeeded. Applying it is what makes the
                // changes visible to the rest of the process.
                snapshot.apply().check()
                result
            } catch (t: Throwable) {
                snapshot.dispose()
                throw t
            }
        }
    }

    /**
     * Stores [record] and adds it to the in-memory graph.
     *
     * This performs no policy check. The checked entry point is `View.add`, which calls this, and no
     * public code may call it directly.
     */
    internal fun <T : Record> insert(record: T): T {
        val writes = requireTransaction(record)
        require(record.database == null) { "$record is already stored" }
        tableFor(record) // Fail here, with the class name, instead of later during the commit.
        resident.add(record)
        writes.insert(record)
        return record
    }

    /**
     * Deletes [record] from the database and the in-memory graph. It performs no policy check. See
     * [insert].
     */
    internal fun delete(record: Record) {
        val writes = requireTransaction(record)
        resident.remove(record)
        writes.delete(record)
    }

    /** Returns the open transaction's writes, or throws if no transaction is open. */
    private fun requireTransaction(record: Record): WriteSet =
        Transactions.current ?: error(
            "Storing or removing $record has to happen inside db.transact { }, so that it is committed " +
                "before any session can see it.",
        )

    /**
     * Writes all of the block's changes to SQLite in one transaction.
     *
     * This runs while the snapshot is still entered, because the new values are visible only inside
     * it.
     */
    private fun commit(writes: WriteSet) {
        if (writes.isEmpty) return
        verifySoleWriter()

        connection.autoCommit = false
        try {
            // Check foreign keys at commit instead of after each statement. Otherwise, no fixed order
            // of statements works for every transaction. Inserting a row and a row that references it
            // needs one order, and moving a reference away from a row that's then deleted needs the
            // other. The setting applies to this transaction only. SQLite resets it on commit.
            connection.createStatement().use { it.execute("PRAGMA defer_foreign_keys=ON") }

            // Run deletes first, because primary key constraints can't be deferred. Re-seeding a
            // fixture with an ID it used before has to free that ID before inserting it again. Run
            // updates last, so they can refer to rows inserted in this transaction.
            writes.deletes.forEach { deleteRow(it) }
            writes.inserts.forEach { insertRow(it) }
            writes.updates.forEach { (record, columns) -> updateRow(record, columns) }
            connection.commit()
        } catch (t: Throwable) {
            connection.rollback()
            throw t
        } finally {
            connection.autoCommit = true
        }

        // Set `database` only after a successful commit. It's a plain field, not snapshot state, so
        // setting it earlier would leave a record marked as stored after a rollback.
        writes.inserts.forEach { it.database = this }
        writes.deletes.forEach { it.database = null }
        dataVersion = readDataVersion()
    }

    // The next three functions write a record to its table. They're named after rows because this is
    // where the object becomes a SQLite row. Elsewhere in this module, the object is called a record.

    /** Inserts [record] as a new row. */
    private fun <T : Record> insertRow(record: T) {
        val table = tableFor(record)
        val columns = table.columns
        val sql = buildString {
            append("INSERT INTO ").append(table.name).append(" (id")
            columns.forEach { append(", ").append(it.name) }
            append(") VALUES (?")
            repeat(columns.size) { append(", ?") }
            append(')')
        }
        connection.prepareStatement(sql).use { statement ->
            statement.setLong(1, record.id)
            columns.forEachIndexed { index, column -> bind(statement, index + 2, column.read(record)) }
            statement.executeUpdate()
        }
    }

    /** Writes the [changed] columns of [record] to its row. */
    private fun <T : Record> updateRow(record: T, changed: Set<String>) {
        val table = tableFor(record)
        val columns = changed.map { name ->
            table.columnsByName[name]
                ?: error("${record::class.simpleName}.$name is not a column of '${table.name}'")
        }
        val sql = "UPDATE ${table.name} SET ${columns.joinToString { "${it.name} = ?" }} WHERE id = ?"
        connection.prepareStatement(sql).use { statement ->
            columns.forEachIndexed { index, column -> bind(statement, index + 1, column.read(record)) }
            statement.setLong(columns.size + 1, record.id)
            val updated = statement.executeUpdate()
            check(updated == 1) { "Updating $record changed $updated rows; it is not on disk" }
        }
    }

    /** Deletes [record]'s row. */
    private fun deleteRow(record: Record) {
        val table = tableFor(record)
        connection.prepareStatement("DELETE FROM ${table.name} WHERE id = ?").use { statement ->
            statement.setLong(1, record.id)
            statement.executeUpdate()
        }
    }

    /**
     * Sets the connection pragmas. See `docs/db-framework-plan.md` §4.9.
     *
     * - WAL mode, so readers such as a backup tool never block the writer.
     * - `synchronous=NORMAL`, which in WAL mode survives a process crash. Only a power loss can lose
     *   the most recent commits.
     * - `foreign_keys=ON`, because SQLite turns off foreign key enforcement by default, without a
     *   warning.
     * - `busy_timeout=5000`, so a statement waits up to five seconds for a lock held by a reader
     *   instead of failing at once.
     */
    private fun configure() {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA synchronous=NORMAL")
            statement.execute("PRAGMA foreign_keys=ON")
            statement.execute("PRAGMA busy_timeout=5000")
        }
    }

    /** Creates any table that doesn't exist yet. It doesn't change existing tables. */
    private fun createSchema() {
        connection.createStatement().use { statement ->
            tables.forEach { table -> statement.execute(table.ddl(tableNames)) }
        }
    }

    /**
     * Fails startup if the stored schema doesn't match the entities.
     *
     * `CREATE TABLE IF NOT EXISTS` does nothing when a table exists with a different structure, which
     * is what happens when a migration hasn't been applied. Without this check, the first sign of the
     * problem would be a failed insert in production, or a column that's never read, without any
     * error.
     *
     * Columns are compared as sets. Column order has no meaning in the schema, and a migration that
     * rebuilds a table can change it.
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

    /** Returns a description of each difference between [table]'s declaration and the stored table. */
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

    /** A column as SQLite reports it. */
    private class StoredColumn(val type: String, val nullable: Boolean)

    /** Returns the stored columns of [table] by name, or an empty map if the table doesn't exist. */
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
                                // SQLite reports an INTEGER PRIMARY KEY as nullable because it's an
                                // alias for the rowid, where inserting NULL means "allocate an ID."
                                // It's never null.
                                nullable = !primaryKey && results.getInt("notnull") == 0,
                            ),
                        )
                    }
                }
            }
        }

    /** Returns the table that each foreign key column of [table] references, by column name. */
    private fun storedReferences(table: String): Map<String, String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_list($table)").use { results ->
                buildMap {
                    while (results.next()) put(results.getString("from"), results.getString("table"))
                }
            }
        }

    /** Loads every table into the identity map, resolving references along the way. */
    private fun load() {
        tables.forEach { table -> loadTable(table) }
    }

    /** Loads every row of [table] into the identity map, in ID order. */
    private fun <T : Record> loadTable(table: Table<T>) {
        val columns = table.columns.map { it.name }
        val sql = "SELECT id${columns.joinToString("") { ", $it" }} FROM ${table.name} ORDER BY id"
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { results ->
                while (results.next()) {
                    val values = columns.associateWith { name -> results.getObject(name) }
                    val id = results.getLong("id")
                    val record = table.instantiate(Row(values, resident))
                    record.adoptStoredId(id)
                    record.database = this
                    Ids.advanceTo(table.type, id)
                    resident.add(record)
                }
            }
        }
    }

    /**
     * Throws if another connection has written the file since this connection's last commit.
     *
     * The in-memory graph is a copy of the whole database, so an external write doesn't conflict with
     * only one row: any part of the graph might now be wrong. There's no safe way to merge that, so
     * the only option is to fail visibly.
     *
     * An exclusive lock would prevent external writes entirely, and §4.9 of the plan asks for one. But
     * in WAL mode, an exclusive lock also blocks readers, which would stop Litestream from reading
     * the file, and the same section relies on Litestream for backups. Detecting the problem one
     * commit late is the better trade-off. The decision log in `docs/db-framework-plan.md` §13 records
     * this.
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

    /** Reads `PRAGMA data_version`. */
    private fun readDataVersion(): Long =
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA data_version").use { results ->
                results.next()
                results.getLong(1)
            }
        }

    /** Returns [record]'s table, or throws if its class has none. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Record> tableFor(record: T): Table<T> =
        (tablesByType[record::class] as Table<T>?)
            ?: error("No table is registered for ${record::class.simpleName}")

    /** Closes the database connection. */
    override fun close() {
        connection.close()
    }
}
