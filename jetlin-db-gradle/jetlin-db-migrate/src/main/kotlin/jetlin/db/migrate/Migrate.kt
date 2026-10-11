package jetlin.db.migrate

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.SQLException
import java.util.Properties
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.time.Duration
import kotlin.time.TimeSource
import org.sqlite.JDBC

/** The table that records which migrations have been applied, when, and with what content. */
internal const val HISTORY = "jetlin_migrations"

/**
 * How long a runner waits for another one to finish, in milliseconds. A second runner only exists
 * while one deployment overlaps another, and waiting for the first one's migrations is what lets the
 * second see them applied, instead of failing.
 */
private const val BUSY_TIMEOUT_MILLIS = 60_000

/** What [migrate] does with pending migrations. */
public enum class MigrationMode {
    /** Apply pending migrations. */
    Apply,

    /**
     * Apply nothing, and fail if any migration is pending. It's for operators who migrate as a
     * separate, deliberate step, for example with `dbMigrate` against a copy first.
     */
    Verify,
}

/** Something [migrate] did, for the application's log. */
public sealed interface MigrationEvent {
    /** A one-line description. */
    public val message: String

    /**
     * The database was copied before any migration ran.
     *
     * @property file the copy.
     */
    public data class BackedUp(val file: Path) : MigrationEvent {
        override val message: String get() = "backed up the database to $file"
    }

    /**
     * A migration was applied.
     *
     * @property migration the migration's name.
     * @property duration how long it took.
     */
    public data class Applied(val migration: String, val duration: Duration) : MigrationEvent {
        override val message: String get() = "applied $migration in ${duration.inWholeMilliseconds} ms"
    }
}

/**
 * What [migrate] did.
 *
 * @property applied the migrations it applied, in order.
 * @property alreadyApplied the migrations the database had before, sorted. That includes migrations
 *   another runner applied while this one waited.
 */
public data class MigrationResult(val applied: List<String>, val alreadyApplied: List<String>)

/**
 * A migration that can't run or failed, or a database whose history can't be trusted. The message
 * says which, and what to do about it.
 */
public class MigrationException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/**
 * Brings the database at [databaseFile] up to date with [migrations].
 *
 * `./gradlew dbMigrate` and `Db.open` both call this, so they apply the same checks and write the same
 * history.
 *
 * ## Where history starts
 *
 * First the runner works out which migrations the database has already had:
 *
 * 1. If it has a `jetlin_migrations` table, that's the answer.
 * 2. If it has no tables at all, it's new, and every migration is pending.
 * 3. If it has tables but no history, there's no telling which migrations it has had, so the runner
 *    stops. Such a database was created from the entities without migrations, with
 *    `MigrationSource.None`, which is for throwaway databases. Guessing is how a migration runs twice,
 *    or never.
 *
 * ## Safety
 *
 * Nothing is applied if any pending migration contains [ACKNOWLEDGEMENT_MARKER], or if a migration
 * that already ran has been edited since (its SHA-256 no longer matches the history's).
 *
 * Before the first pending migration runs, the database is copied to
 * `<file>.before-<migration name>`, and only the [keepBackups] most recent copies are kept. A failed
 * migration already rolls back; the copy is for one that succeeds and turns out to be wrong. A new
 * database isn't copied.
 *
 * Each migration runs in its own transaction, with foreign keys off, as SQLite's table rebuild
 * procedure requires. Before committing, the runner checks that `PRAGMA foreign_key_check` reports no
 * violations, and that every index, trigger, and view that existed before still exists. A rebuild
 * drops the old table and everything attached to it, and the generator can't see which of these a
 * particular database has, so the runner detects the loss and names what was lost. On any failure,
 * that migration is rolled back and not recorded, and the exception names the migration, the
 * statement, and SQLite's message.
 *
 * Each transaction starts with `BEGIN IMMEDIATE`, which takes SQLite's write lock before the history is
 * read. A second runner on the same file waits, then finds the migration applied, and skips it.
 *
 * @param databaseFile the database. It's created if it doesn't exist, except in
 *   [MigrationMode.Verify].
 * @param migrations every migration, in order.
 * @param mode whether to apply pending migrations, or only fail if there are any.
 * @param keepBackups how many copies to keep. Zero turns copying off.
 * @param onEvent receives a [MigrationEvent] for each thing the runner did.
 * @throws MigrationException if the database can't be brought up to date. It is then exactly as it
 *   was, except for migrations that were applied before the one that failed.
 */
public fun migrate(
    databaseFile: Path,
    migrations: List<Migration>,
    mode: MigrationMode = MigrationMode.Apply,
    keepBackups: Int = 3,
    onEvent: (MigrationEvent) -> Unit = {},
): MigrationResult {
    require(keepBackups >= 0) { "keepBackups must not be negative, but was $keepBackups" }
    if (migrations.isEmpty()) return MigrationResult(emptyList(), emptyList())

    val file = databaseFile.toAbsolutePath()
    if (mode == MigrationMode.Verify && !file.exists()) {
        // Opening the file would create it, and Verify writes nothing.
        throw notApplied(migrations)
    }
    file.parent?.createDirectories()

    connect("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { statement ->
            // Set the timeout first: switching a new database to WAL takes a lock another runner may hold.
            statement.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MILLIS")
            statement.execute("PRAGMA journal_mode=WAL")
        }

        val plan = when (mode) {
            MigrationMode.Verify -> plan(connection, migrations, mode)
            MigrationMode.Apply -> locked(connection) {
                plan(connection, migrations, mode).also { prepareHistory(connection, it) }
            }
        }
        if (plan.pending.isEmpty()) return MigrationResult(emptyList(), plan.applied.sorted())

        if (!plan.fresh && keepBackups > 0) {
            onEvent(MigrationEvent.BackedUp(backUp(connection, file, plan.pending.first(), keepBackups)))
        }

        val applied = mutableListOf<String>()
        for (migration in plan.pending) {
            val start = TimeSource.Monotonic.markNow()
            val ran = try {
                run(connection, migration, record = true)
            } catch (failure: MigrationFailure) {
                throw MigrationException(
                    buildString {
                        append(failure.message)
                        append("\n\n").append(migration.name).append(" was rolled back, so none of it was applied.")
                        if (applied.isNotEmpty()) append(" These migrations before it were: ${applied.joinToString()}.")
                    },
                    failure.cause,
                )
            }
            if (ran) {
                applied += migration.name
                onEvent(MigrationEvent.Applied(migration.name, start.elapsedNow()))
            }
        }
        val elsewhere = plan.pending.map { it.name } - applied.toSet()
        return MigrationResult(applied, (plan.applied + elsewhere).sorted())
    }
}

/**
 * Applies each migration to an empty in-memory database, and returns the schema after each one, in
 * the same order.
 *
 * The plugin uses it to check that the migrations produce the schema the entities declare.
 *
 * @throws MigrationException if a migration fails in an empty database.
 */
public fun replay(migrations: List<Migration>): List<StoredSchema> =
    connect("jdbc:sqlite::memory:").use { connection ->
        migrations.map { migration ->
            try {
                run(connection, migration, record = false)
            } catch (failure: MigrationFailure) {
                throw MigrationException(
                    "Replaying the migrations in an empty database failed. ${failure.message}",
                    failure.cause,
                )
            }
            StoredSchema.read(connection)
        }
    }

/**
 * Opens a SQLite connection through the driver directly, not through `DriverManager`.
 *
 * Inside Gradle, the driver is on the plugin's class loader, which `DriverManager` doesn't search
 * when it registers drivers, so whether it finds the driver depends on what else has run in the
 * daemon.
 */
private fun connect(url: String): Connection = JDBC.createConnection(url, Properties())

// ---- Working out what to do -------------------------------------------------------------------------

/**
 * What [migrate] found, before it changes anything.
 *
 * @property historyColumns the history table's columns, or an empty set if there's no table.
 * @property applied the migrations the history lists.
 * @property pending the migrations to apply, in order.
 * @property fresh whether the database had nothing in it, so there's nothing to back up.
 */
private class Plan(
    val historyColumns: Set<String>,
    val applied: List<String>,
    val pending: List<Migration>,
    val fresh: Boolean,
)

/** Works out which migrations the database has had, checks everything, and returns what to apply. */
private fun plan(connection: Connection, migrations: List<Migration>, mode: MigrationMode): Plan {
    val historyColumns = columnNames(connection, HISTORY)
    val schema = StoredSchema.read(connection)

    if (historyColumns.isEmpty() && !schema.isEmpty) {
        throw MigrationException(
            "The database has tables but no migration history, so there's no telling which migrations " +
                "it has had. It was created from the entities without migrations, with " +
                "MigrationSource.None, which is meant for throwaway databases. Delete it so the " +
                "migrations can create it, or create the $HISTORY table (name TEXT PRIMARY KEY, " +
                "applied_at TEXT NOT NULL, sha256 TEXT) and insert a row for each migration it has had. " +
                "Nothing was applied.",
        )
    }
    val hashes = when {
        historyColumns.isEmpty() -> emptyMap()
        else -> history(connection, hasHashes = "sha256" in historyColumns)
    }

    val edited = migrations.filter { migration -> hashes[migration.name]?.let { it != migration.sha256 } == true }
    if (edited.isNotEmpty()) {
        throw MigrationException(
            "These migrations were edited after they were applied to this database: " +
                "${edited.joinToString()}. This database and a new one would now have different schemas. " +
                "Restore the files as they were, and put the change in a new migration.",
        )
    }

    val applied = hashes.keys
    val pending = migrations.filter { it.name !in applied }
    if (mode == MigrationMode.Verify && pending.isNotEmpty()) throw notApplied(pending)

    val unacknowledged = pending.filterNot { it.acknowledged }
    if (unacknowledged.isNotEmpty()) {
        throw MigrationException(
            "These migrations destroy data and have not been acknowledged: ${unacknowledged.joinToString()}. " +
                "Read each one and delete its `$ACKNOWLEDGEMENT_MARKER` line to allow it. Nothing was applied.",
        )
    }

    return Plan(
        historyColumns = historyColumns,
        applied = applied.toList(),
        pending = pending,
        fresh = schema.isEmpty && hashes.isEmpty(),
    )
}

/** The error for migrations that [MigrationMode.Verify] found pending. */
private fun notApplied(pending: List<Migration>): MigrationException =
    MigrationException(
        "These migrations have not been applied: ${pending.joinToString()}. Migrations are only being " +
            "verified, not applied. Apply them with `./gradlew dbMigrate` against this database, or let " +
            "the application apply them when it starts.",
    )

/**
 * Creates the history table, or adds the `sha256` column to one that an older runner created. It runs
 * inside the same locked transaction as [plan].
 */
private fun prepareHistory(connection: Connection, plan: Plan) {
    connection.createStatement().use { statement ->
        when {
            plan.historyColumns.isEmpty() -> statement.execute(
                "CREATE TABLE $HISTORY (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL, sha256 TEXT)",
            )
            // Rows from before hashes were recorded keep NULL, and aren't checked.
            "sha256" !in plan.historyColumns -> statement.execute("ALTER TABLE $HISTORY ADD COLUMN sha256 TEXT")
        }
    }
}

/** Returns the names of [table]'s columns, or an empty set if there's no such table. */
private fun columnNames(connection: Connection, table: String): Set<String> =
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { results ->
            buildSet { while (results.next()) add(results.getString("name")) }
        }
    }

/** Reads the history: each applied migration's name, and its hash, or `null` if none was recorded. */
private fun history(connection: Connection, hasHashes: Boolean): Map<String, String?> =
    connection.createStatement().use { statement ->
        val sql = if (hasHashes) "SELECT name, sha256 FROM $HISTORY" else "SELECT name, NULL FROM $HISTORY"
        statement.executeQuery(sql).use { results ->
            buildMap { while (results.next()) put(results.getString(1), results.getString(2)) }
        }
    }

/** Records [migration] as applied. */
private fun record(connection: Connection, migration: Migration) {
    connection.prepareStatement("INSERT INTO $HISTORY (name, applied_at, sha256) VALUES (?, datetime('now'), ?)")
        .use { statement ->
            statement.setString(1, migration.name)
            statement.setString(2, migration.sha256)
            statement.executeUpdate()
        }
}

// ---- Applying one migration -------------------------------------------------------------------------

/** A migration that failed, before [migrate] or [replay] says what that means for the database. */
private class MigrationFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs [migration] in its own transaction, checks the result, and, if [record] is set, records it.
 *
 * @return `false` if another runner applied [migration] while this one waited for the lock.
 * @throws MigrationFailure if a statement or a check fails. The transaction is rolled back.
 */
private fun run(connection: Connection, migration: Migration, record: Boolean): Boolean {
    // Foreign keys can be switched only outside a transaction. They're off during the migration because
    // the rebuild procedure drops a table that other tables still reference.
    connection.createStatement().use { it.execute("PRAGMA foreign_keys=OFF") }
    try {
        return locked(connection) {
            if (record && migration.name in history(connection, hasHashes = false)) return@locked false

            val dependentsBefore = dependentObjects(connection)
            connection.createStatement().use { statement ->
                for (piece in statements(migration.sql)) {
                    try {
                        statement.execute(piece)
                    } catch (e: SQLException) {
                        throw MigrationFailure(
                            "${migration.name} failed at this statement:\n\n${piece.prependIndent("    ")}\n\n" +
                                "SQLite said: ${e.message}",
                            e,
                        )
                    }
                }
            }

            violations(connection)?.let { throw MigrationFailure("$it after ${migration.name}") }

            val lost = dependentsBefore - dependentObjects(connection)
            if (lost.isNotEmpty()) {
                throw MigrationFailure(
                    "${migration.name} dropped objects a rebuilt table owned: ${lost.joinToString()}. A " +
                        "rebuild drops the old table and everything attached to it; add the CREATE " +
                        "statements for these to the end of the migration.",
                )
            }

            if (record) record(connection, migration)
            true
        }
    } finally {
        connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }
}

/**
 * Runs [block] in a transaction that holds SQLite's write lock from the start, and commits it, or
 * rolls it back if [block] throws.
 */
private inline fun <T> locked(connection: Connection, block: () -> T): T {
    connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
    val result = try {
        block()
    } catch (t: Throwable) {
        try {
            connection.createStatement().use { it.execute("ROLLBACK") }
        } catch (rollback: SQLException) {
            // Some errors make SQLite roll back on its own, and then there's no transaction left.
            t.addSuppressed(rollback)
        }
        throw t
    }
    connection.createStatement().use { it.execute("COMMIT") }
    return result
}

/**
 * Returns the names of all indexes, triggers, and views: the objects a table rebuild drops without
 * warning.
 */
private fun dependentObjects(connection: Connection): Set<String> = StoredSchema.read(connection).objects

/** Describes the first foreign key violation, or returns `null` if there's none. */
private fun violations(connection: Connection): String? {
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use { results ->
            if (!results.next()) return null
            return "Foreign key violations in '${results.getString(1)}'"
        }
    }
}

// ---- Backups ----------------------------------------------------------------------------------------

/**
 * Copies the database to `<file>.before-<first>`, deletes all but the [keep] most recent copies, and
 * returns the new copy.
 *
 * `VACUUM INTO` writes a consistent copy without blocking writers. It writes to a temporary name that
 * is then moved into place, so a runner that crashes midway, or two that start at once, never leave a
 * partial copy under the real name.
 */
private fun backUp(connection: Connection, databaseFile: Path, first: Migration, keep: Int): Path {
    val prefix = "${databaseFile.name}.before-"
    val target = databaseFile.resolveSibling(prefix + first.name.removeSuffix(".sql"))
    val partial = databaseFile.resolveSibling("${target.name}.partial-${UUID.randomUUID()}")
    try {
        connection.prepareStatement("VACUUM INTO ?").use { statement ->
            statement.setString(1, partial.toString())
            statement.execute()
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } finally {
        partial.deleteIfExists()
    }

    // Migration names sort in the order they run, so the last names are the most recent copies.
    databaseFile.parent.listDirectoryEntries("$prefix*")
        .filter { ".partial-" !in it.name }
        .sortedByDescending { it.name }
        .drop(keep)
        .forEach { it.deleteIfExists() }
    return target
}

// ---- SQL --------------------------------------------------------------------------------------------

/**
 * Splits a migration into statements at semicolons that are outside quotes and comments.
 *
 * It's deliberately simple, because migrations are mostly DDL. It recognizes `--` comments but not
 * block comments, so a semicolon inside a block comment splits the statement. It also can't handle a
 * trigger with a `BEGIN … END` body, because it treats the semicolons inside the body as statement
 * separators. Supporting that would need a real SQL tokenizer.
 */
internal fun statements(sql: String): List<String> {
    val statements = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var index = 0

    while (index < sql.length) {
        val character = sql[index]
        when {
            quote != null -> {
                current.append(character)
                if (character == quote) quote = null
                index++
            }
            character == '\'' || character == '"' -> {
                quote = character
                current.append(character)
                index++
            }
            character == '-' && sql.startsWith("--", index) -> {
                val end = sql.indexOf('\n', index).let { if (it == -1) sql.length else it }
                index = end
            }
            character == ';' -> {
                statements += current.toString()
                current.clear()
                index++
            }
            else -> {
                current.append(character)
                index++
            }
        }
    }
    if (current.isNotBlank()) statements += current.toString()
    return statements.map { it.trim() }.filter { it.isNotEmpty() }
}
