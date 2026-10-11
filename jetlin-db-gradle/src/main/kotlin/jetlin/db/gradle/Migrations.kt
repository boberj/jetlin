package jetlin.db.gradle

import java.io.File
import jetlin.db.migrate.Migration
import jetlin.db.migrate.MigrationSource
import jetlin.db.migrate.StoredColumn
import jetlin.db.migrate.StoredSchema
import jetlin.db.migrate.replay

/**
 * Reads and writes the migration files in one directory.
 *
 * Migrations are SQL files, checked in with the code and named like `0001_what_it_does.sql`. They're
 * applied in name order, and each applied migration is recorded in a table, so it never runs twice.
 * The files are plain SQL with no framework-specific format, so the SQL a reviewer reads is exactly
 * the SQL that runs. Applying them is the engine's job, in `jetlin-db-migrate`. This class only finds
 * and writes the files.
 *
 * @param directory the directory that holds the migration files.
 */
public class Migrations(private val directory: File) {

    /** Returns every migration in the directory, in name order. */
    public fun all(): List<Migration> = MigrationSource.Directory(directory.toPath()).load()

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
 * Replays [migrations] in an empty database, and describes each way the result differs from the
 * schema the entities declare, or returns an empty list if it doesn't.
 *
 * Comparing the entities with `db/schema.json` catches a forgotten `dbDiff`, but not a migration that
 * was edited by hand into something that no longer produces `schema.json`. This catches that, at build
 * time instead of during a deployment. Only tables and columns are compared, because the entities
 * don't declare indexes, triggers, or views.
 *
 * @throws jetlin.db.migrate.MigrationException if a migration fails in an empty database.
 */
public fun replayProblems(declared: SchemaFile, migrations: List<Migration>): List<String> {
    if (migrations.isEmpty()) return emptyList()
    val replayed = replay(migrations).last()
    return replayed.differencesFrom(declared.toStoredSchema(), includeObjects = false)
}

/** Returns this schema in the form the engine reads from a database. */
internal fun SchemaFile.toStoredSchema(): StoredSchema =
    StoredSchema(
        tables.associate { table ->
            table.name to table.columns.associate { column ->
                column.name to StoredColumn(
                    type = column.type.uppercase(),
                    nullable = column.nullable && !column.primaryKey,
                    references = column.references,
                    primaryKey = column.primaryKey,
                )
            }
        },
    )
