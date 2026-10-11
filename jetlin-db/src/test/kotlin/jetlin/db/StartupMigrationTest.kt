package jetlin.db

import java.nio.file.Path
import java.sql.DriverManager
import jetlin.db.migrate.ACKNOWLEDGEMENT_MARKER
import jetlin.db.migrate.MigrationEvent
import jetlin.db.migrate.MigrationException
import jetlin.db.migrate.MigrationMode
import jetlin.db.migrate.MigrationSource
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests that `Db.open` brings a database up to date before loading it, whatever state it's in.
 *
 * The engine's own tests, in `jetlin-db-migrate`, cover its rules in detail. These check what an
 * application sees: the database it gets, what's logged, and that a failure stops startup.
 */
class StartupMigrationTest {

    @Test
    fun `an empty database is created by the migrations`(): Unit = withMigrations { file, migrations, events ->
        migrations.write("0001_initial.sql", initialSql())

        Db.open(file, schema(), migrations.source, onMigration = events::add).use { db ->
            db.transact { db.insert(User("Alice")) }
        }

        assertEquals(listOf("0001_initial.sql"), history(file))
        assertTrue(events.single() is MigrationEvent.Applied)
        assertEquals(emptyList(), backups(file), "a new database has nothing to back up")
    }

    @Test
    fun `a current database opens, and nothing is logged`(): Unit = withMigrations { file, migrations, events ->
        migrations.write("0001_initial.sql", initialSql())
        Db.open(file, schema(), migrations.source).close()

        Db.open(file, schema(), migrations.source, onMigration = events::add).close()

        assertEquals(emptyList(), events)
    }

    @Test
    fun `pending migrations are applied before anything is loaded`(): Unit = withMigrations { file, migrations, events ->
        // The application as it was, before tasks could be archived. Opening it with today's entities
        // would fail, so the old version's database is built with its migration alone.
        migrations.write("0001_initial.sql", initialSql(withoutArchived = true))
        jetlin.db.migrate.migrate(file, migrations.source.load())
        execute(file, "INSERT INTO users (id, name, admin) VALUES (1, 'Alice', 0)")
        execute(file, "INSERT INTO tasks (id, owner, title, done) VALUES (1, 1, 'Read the plan', 0)")

        // The next version adds the column, with a migration.
        migrations.write("0002_archive_tasks.sql", "ALTER TABLE tasks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;")
        Db.open(file, schema(), migrations.source, onMigration = events::add).use { db ->
            val task = db.resident.all(Task::class).single()
            assertEquals("Read the plan", task.title)
            assertFalse(task.archived)
        }

        assertEquals(listOf("0001_initial.sql", "0002_archive_tasks.sql"), history(file))
        assertEquals(listOf("test.db.before-0002_archive_tasks"), backups(file).map { it.name })
        assertEquals(listOf("BackedUp", "Applied"), events.map { it::class.simpleName })
    }

    @Test
    fun `an application without migrations doesn't start, and says how to write the first`(): Unit =
        withMigrations { file, _, _ ->
            // This module's test classpath has no packaged migrations, like a new application.
            val failure = assertFailsWith<IllegalArgumentException> { Db.open(file, schema()).close() }

            assertContains(failure.message.orEmpty(), "./gradlew dbDiff --name=initial_schema")
        }

    @Test
    fun `a database created without migrations is refused once there are some`(): Unit =
        withMigrations { file, migrations, _ ->
            Db.open(file, schema(), MigrationSource.None).use { db -> db.transact { db.insert(User("Alice")) } }
            migrations.write("0001_initial.sql", initialSql())

            val failure = assertFailsWith<MigrationException> { Db.open(file, schema(), migrations.source).close() }

            assertContains(failure.message.orEmpty(), "has tables but no migration history")
        }

    @Test
    fun `verify mode fails on pending migrations and names them`(): Unit = withMigrations { file, migrations, _ ->
        migrations.write("0001_initial.sql", initialSql(withoutArchived = true))
        jetlin.db.migrate.migrate(file, migrations.source.load())
        migrations.write("0002_archive_tasks.sql", "ALTER TABLE tasks ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;")

        val failure = assertFailsWith<MigrationException> {
            Db.open(file, schema(), migrations.source, onStartup = MigrationMode.Verify).close()
        }

        assertContains(failure.message.orEmpty(), "have not been applied: 0002_archive_tasks.sql")
        assertEquals(listOf("0001_initial.sql"), history(file))
    }

    @Test
    fun `an unacknowledged migration stops startup before anything runs`(): Unit = withMigrations { file, migrations, _ ->
        migrations.write("0001_initial.sql", initialSql())
        Db.open(file, schema(), migrations.source).close()
        migrations.write("0002_notes.sql", "ALTER TABLE tasks ADD COLUMN notes TEXT;")
        migrations.write("0003_drop_notes.sql", "$ACKNOWLEDGEMENT_MARKER\nALTER TABLE tasks DROP COLUMN notes;")

        val failure = assertFailsWith<MigrationException> { Db.open(file, schema(), migrations.source).close() }

        assertContains(failure.message.orEmpty(), "have not been acknowledged: 0003_drop_notes.sql")
        assertEquals(listOf("0001_initial.sql"), history(file), "not even 0002 ran")
    }

    @Test
    fun `a failing migration is rolled back, leaves a backup, and stops startup`(): Unit =
        withMigrations { file, migrations, _ ->
            migrations.write("0001_initial.sql", initialSql())
            Db.open(file, schema(), migrations.source).use { db -> db.transact { db.insert(User("Alice")) } }
            migrations.write("0002_broken.sql", "UPDATE users SET name = upper(name);\nUPDATE nowhere SET x = 1;")

            val failure = assertFailsWith<MigrationException> { Db.open(file, schema(), migrations.source).close() }

            assertContains(failure.message.orEmpty(), "0002_broken.sql failed at this statement")
            assertContains(failure.message.orEmpty(), "no such table: nowhere")
            Db.open(file, schema(), MigrationSource.None).use { db ->
                assertEquals("Alice", db.resident.all(User::class).single().name, "the first statement was rolled back")
            }
            assertEquals(listOf("test.db.before-0002_broken"), backups(file).map { it.name })
        }

    @Test
    fun `migrations that don't produce the entities' schema fail verification`(): Unit =
        withMigrations { file, migrations, _ ->
            // With migrations, they alone create tables. Db.open doesn't fill the gap from the entities.
            migrations.write("0001_initial.sql", initialSql(withoutArchived = true))

            val failure = assertFailsWith<IllegalStateException> { Db.open(file, schema(), migrations.source).close() }

            assertContains(failure.message.orEmpty(), "'tasks.archived' is missing")
        }
}

// ---- Fixtures -----------------------------------------------------------------------------------------

/** A directory of migration files, as `db/migrations` would be. */
private class MigrationDirectory(val path: Path) {
    val source: MigrationSource = MigrationSource.Directory(path)

    fun write(name: String, sql: String) {
        path.resolve(name).writeText(sql)
    }
}

@OptIn(ExperimentalPathApi::class)
private fun withMigrations(block: (Path, MigrationDirectory, MutableList<MigrationEvent>) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        val migrations = MigrationDirectory(directory.resolve("migrations").createDirectories())
        block(directory.resolve("test.db"), migrations, mutableListOf())
    } finally {
        directory.deleteRecursively()
    }
}

/**
 * The SQL of a first migration for this module's entities, built from the same DDL that `Db.open`
 * uses without migrations, optionally from before `Task.archived` existed.
 */
private fun initialSql(withoutArchived: Boolean = false): String {
    val tables = schema()
    val names = tables.associate { it.type to it.name }
    val sql = tables.joinToString("") { "${it.ddl(names)};\n" }
    return if (withoutArchived) sql.replace(",\n  archived INTEGER NOT NULL", "") else sql
}

private fun execute(file: Path, sql: String) {
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { it.execute(sql) }
    }
}

private fun history(file: Path): List<String> =
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM jetlin_migrations ORDER BY name").use { results ->
                buildList { while (results.next()) add(results.getString(1)) }
            }
        }
    }

private fun backups(file: Path): List<Path> =
    file.parent.listDirectoryEntries("${file.name}.before-*").sortedBy { it.name }
