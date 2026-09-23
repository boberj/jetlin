package jetlin.db.gradle

import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * A migration file. Its name starts with a number that determines the order migrations run in.
 *
 * @property file the SQL file.
 */
public data class Migration(val file: File) {
    /** The file name, such as `0001_initial_schema.sql`. The history table records it. */
    public val name: String get() = file.name

    /** The file's SQL, read each time. */
    public val sql: String get() = file.readText()

    /** Whether the migration can run, because it doesn't contain [ACKNOWLEDGEMENT_MARKER]. */
    public val acknowledged: Boolean get() = ACKNOWLEDGEMENT_MARKER !in sql
}

/**
 * Reads and writes the migration files in one directory.
 *
 * Migrations are SQL files, checked in with the code and named like `0001_what_it_does.sql`. They're
 * applied in name order, and each applied migration is recorded in a table, so it never runs twice.
 * The files are plain SQL with no framework-specific format, so the SQL a reviewer reads is exactly
 * the SQL that runs.
 *
 * @param directory the directory that holds the migration files.
 */
public class Migrations(private val directory: File) {

    /** Returns the migrations whose names aren't in [applied], in name order. */
    public fun pending(applied: Set<String>): List<Migration> =
        all().filter { it.name !in applied }

    /** Returns every migration in the directory, in name order. */
    public fun all(): List<Migration> =
        (directory.listFiles { file -> file.isFile && file.name.endsWith(".sql") } ?: emptyArray())
            .sortedBy { it.name }
            .map { Migration(it) }

    /**
     * Returns the file for a new migration, numbered one higher than the latest and padded to four
     * digits.
     *
     * @param description what the migration does. It becomes the rest of the file name, lowercased,
     *   with anything other than letters and digits replaced by underscores.
     */
    public fun nextFile(description: String): File {
        val next = all().mapNotNull { it.name.substringBefore('_').toIntOrNull() }.maxOrNull()?.plus(1) ?: 1
        val slug = description.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifEmpty { "migration" }
        return directory.resolve("%04d_%s.sql".format(next, slug))
    }

    /** Writes [sql] to [file], creating the directory if needed. */
    public fun write(file: File, sql: String) {
        directory.mkdirs()
        file.writeText(sql)
    }
}

/**
 * What [applyMigrations] did, for the task to report.
 *
 * @property applied the names of the migrations it applied, in order.
 * @property alreadyApplied the names of the migrations that were applied before, sorted.
 */
public data class MigrationResult(val applied: List<String>, val alreadyApplied: List<String>)

/** The table that records which migrations have been applied, and when. */
private const val HISTORY = "jetlin_migrations"

/**
 * Applies every pending migration to the database at [databaseFile].
 *
 * Each migration runs in its own transaction with foreign keys turned off, as SQLite's table rebuild
 * procedure requires. Before committing, the runner checks two things:
 *
 * - `PRAGMA foreign_key_check` reports no violations. A rebuild that leaves a dangling reference
 *   fails the migration.
 * - Every index, trigger, and view that existed before the migration still exists. Rebuilding a
 *   table drops the old table along with everything attached to it. The generator can't see which of
 *   these objects a particular database has, so instead of trying to re-create them, the runner
 *   detects the loss and fails with the names of the missing objects. You can then edit the migration
 *   to re-create them. Views over a rebuilt table are the most common case.
 *
 * On failure, the migration is rolled back and not recorded as applied. Fix the file and run the task
 * again. There's no half-migrated database to repair.
 *
 * @param databaseFile the database. It's created if it doesn't exist.
 * @param migrations the migrations to apply.
 * @throws IllegalStateException if a pending migration isn't acknowledged, or a migration fails one
 *   of the checks. Unacknowledged migrations stop the run before any migration is applied.
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

/** Runs [migration] in its own transaction, checks the result, and records it as applied. */
private fun apply(connection: Connection, migration: Migration) {
    val dependentsBefore = dependentObjects(connection)

    // Turn foreign keys off during the migration, and back on afterward. The rebuild procedure drops
    // a table that other tables still reference, which SQLite allows only while foreign keys aren't
    // enforced.
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

/**
 * Returns the names of all indexes, triggers, and views: the objects a table rebuild drops without
 * warning.
 */
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

/** Describes the first foreign key violation, or returns `null` if there's none. */
private fun violations(connection: Connection): String? {
    connection.createStatement().use { statement ->
        statement.executeQuery("PRAGMA foreign_key_check").use { results ->
            if (!results.next()) return null
            return "Foreign key violations in '${results.getString(1)}'"
        }
    }
}

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
