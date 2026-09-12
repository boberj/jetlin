package jetlin.db.gradle

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/** One migration file: its ordering prefix, its name on disk, and its SQL. */
public data class Migration(val file: File) {
    public val name: String get() = file.name

    public val sql: String get() = file.readText()

    public val acknowledged: Boolean get() = ACKNOWLEDGEMENT_MARKER !in sql
}

/**
 * Reading, writing and applying the migrations of one repository.
 *
 * Migrations are SQL files checked in next to the code, named `0001_what_it_does.sql`, applied in name
 * order, and recorded in a table so that applying twice does nothing. No framework-specific format: what
 * is in the file is what runs, which is the only way a generated migration can be reviewed honestly.
 */
public class Migrations(private val directory: File) {

    public fun pending(applied: Set<String>): List<Migration> =
        all().filter { it.name !in applied }

    public fun all(): List<Migration> =
        (directory.listFiles { file -> file.isFile && file.name.endsWith(".sql") } ?: emptyArray())
            .sortedBy { it.name }
            .map { Migration(it) }

    /** The next file name, keeping the numeric prefix ordered and padded. */
    public fun nextFile(description: String): File {
        val next = all().mapNotNull { it.name.substringBefore('_').toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        val slug = description.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "migration" }
        return directory.resolve("%04d_%s.sql".format(next, slug))
    }

    public fun write(file: File, sql: String) {
        directory.mkdirs()
        file.writeText(sql)
    }
}

/** What [applyMigrations] did, for the task to report. */
public data class MigrationResult(val applied: List<String>, val alreadyApplied: List<String>)

private const val HISTORY = "jetlin_migrations"

/**
 * Applies every pending migration to the database at [databaseFile].
 *
 * Each migration runs as one transaction with foreign keys disabled, which is what the rebuild procedure
 * requires, and is checked before committing:
 *
 * - `PRAGMA foreign_key_check` — a rebuild that left a dangling reference is a failure, not a warning.
 * - every index, trigger and view that existed on a rebuilt table still exists. A rebuild drops the old
 *   table, and everything attached to it goes with it. Rather than guess at re-creating them — the
 *   generator cannot see what a database has — the runner notices and stops, naming what was lost, so the
 *   migration can be edited to re-create it. Views referencing a rebuilt table are the classic trap here.
 *
 * A failure rolls the migration back and leaves the history untouched, so the fix is to edit the file and
 * run again rather than to repair a half-migrated database by hand.
 */
public fun applyMigrations(databaseFile: File, migrations: Migrations): MigrationResult {
    databaseFile.parentFile?.mkdirs()
    DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}").use { connection ->
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode=WAL")
            statement.execute("PRAGMA busy_timeout=5000")
            statement.execute(
                "CREATE TABLE IF NOT EXISTS $HISTORY (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)",
            )
        }

        val applied = mutableSetOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM $HISTORY").use { results ->
                while (results.next()) applied += results.getString(1)
            }
        }

        val pending = migrations.pending(applied)
        val unacknowledged = pending.filterNot { it.acknowledged }
        check(unacknowledged.isEmpty()) {
            "These migrations destroy data and have not been acknowledged: " +
                unacknowledged.joinToString { it.name } +
                ". Read each one and delete its `$ACKNOWLEDGEMENT_MARKER` line to allow it."
        }

        for (migration in pending) {
            apply(connection, migration)
        }
        return MigrationResult(pending.map { it.name }, applied.sorted())
    }
}

private fun apply(connection: Connection, migration: Migration) {
    val dependentsBefore = dependentObjects(connection)

    // Off for the duration, and restored afterwards: the rebuild procedure drops a table other tables
    // still reference, which is only legal while the constraint is not being enforced row by row.
    connection.createStatement().use { it.execute("PRAGMA foreign_keys=OFF") }
    connection.autoCommit = false
    try {
        connection.createStatement().use { statement ->
            for (piece in statements(migration.sql)) statement.execute(piece)
        }

        violations(connection)?.let { error("$it after ${migration.name}") }

        val lost = dependentsBefore - dependentObjects(connection)
        check(lost.isEmpty()) {
            "${migration.name} dropped objects a rebuilt table owned: ${lost.joinToString()}. A rebuild " +
                "drops the old table and everything attached to it; add the CREATE statements for these " +
                "to the end of the migration."
        }

        connection.prepareStatement("INSERT INTO $HISTORY (name, applied_at) VALUES (?, datetime('now'))")
            .use { statement ->
                statement.setString(1, migration.name)
                statement.executeUpdate()
            }
        connection.commit()
    } catch (t: Throwable) {
        connection.rollback()
        throw t
    } finally {
        connection.autoCommit = true
        connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
    }
}

/** Indexes, triggers and views, by name: what a rebuild silently takes with it. */
private fun dependentObjects(connection: Connection): Set<String> {
    val objects = mutableSetOf<String>()
    connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT type, name FROM sqlite_schema WHERE type IN ('index', 'trigger', 'view') " +
                "AND name NOT LIKE 'sqlite_%'",
        ).use { results ->
            while (results.next()) objects += "${results.getString(1)} ${results.getString(2)}"
        }
    }
    return objects
}

private fun violations(connection: Connection): String? {
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use { results ->
            if (!results.next()) return null
            return "Foreign key violations in '${results.getString(1)}'"
        }
    }
}

/**
 * Splits a migration into statements.
 *
 * Naive on purpose — semicolons outside quotes and comments — because a migration is DDL and a `BEGIN …
 * END` trigger body is the one thing it cannot handle. A trigger written by hand can be applied by a
 * migration that contains only it, and that limitation is worth less than the clarity of a short splitter.
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
