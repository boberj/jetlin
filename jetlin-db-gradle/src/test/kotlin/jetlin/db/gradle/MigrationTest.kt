package jetlin.db.gradle

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Migrations, applied to a real database.
 *
 * Generated SQL that looks right is not the thing worth testing — SQLite's own limits are where migrations
 * go wrong, and they only show up when something runs. Every case here creates a database from the old
 * schema, puts a row in it, applies what the generator produced, and then reads the schema back out and
 * checks the row survived.
 */
class MigrationTest {

    @Test
    fun `adding an optional column keeps the rows`() {
        val before = schema(table("tasks", id(), text("title")))
        val after = schema(table("tasks", id(), text("title"), text("notes", nullable = true)))

        val database = migrated(before, after) { connection ->
            connection.createStatement().use { it.execute("INSERT INTO tasks (id, title) VALUES (1, 'one')") }
        }

        assertEquals(after.table("tasks")?.columns, columnsOf(database, "tasks"))
        assertEquals(listOf("1|one|null"), rowsOf(database, "SELECT id, title, notes FROM tasks"))
    }

    @Test
    fun `adding a required column backfills it, visibly`() {
        val before = schema(table("tasks", id(), text("title")))
        val after = schema(table("tasks", id(), text("title"), integer("done", nullable = false)))
        val sql = migrationFor(before, after)

        // The backfill is in the SQL a human reads, not hidden in the tool.
        assertContains(sql, "ADD COLUMN done INTEGER NOT NULL DEFAULT 0")

        val database = migrated(before, after) { connection ->
            connection.createStatement().use { it.execute("INSERT INTO tasks (id, title) VALUES (1, 'one')") }
        }

        assertEquals(listOf("1|one|0"), rowsOf(database, "SELECT id, title, done FROM tasks"))
    }

    @Test
    fun `dropping a column is destructive and has to be acknowledged`() {
        val before = schema(table("tasks", id(), text("title"), text("notes", nullable = true)))
        val after = schema(table("tasks", id(), text("title")))
        val sql = migrationFor(before, after)

        assertContains(sql, ACKNOWLEDGEMENT_MARKER)
        assertContains(sql, "drop tasks.notes, and the values in it")

        val database = file()
        create(database, before)
        val migrations = directory()
        val store = Migrations(migrations)
        val file = store.nextFile("drop notes")
        store.write(file, sql)

        val refusal = assertFailsWith<IllegalStateException> { applyMigrations(database, store) }
        assertContains(refusal.message.orEmpty(), "have not been acknowledged")
        assertTrue("notes" in columnsOf(database, "tasks").map { it.name }, "nothing was applied")

        // Reading it is the acknowledgement.
        store.write(file, sql.replace("$ACKNOWLEDGEMENT_MARKER\n", ""))
        applyMigrations(database, store)

        assertEquals(after.table("tasks")?.columns, columnsOf(database, "tasks"))
    }

    @Test
    fun `changing a column's type rebuilds the table and keeps the values`() {
        val before = schema(table("tasks", id(), text("title"), text("rank")))
        val after = schema(table("tasks", id(), text("title"), integer("rank", nullable = false)))
        val sql = migrationFor(before, after)

        assertContains(sql, "cannot be altered in place")
        assertContains(sql, "ALTER TABLE tasks__jetlin_new RENAME TO tasks;")

        val database = migrated(before, after, acknowledge = true) { connection ->
            connection.createStatement().use {
                it.execute("INSERT INTO tasks (id, title, rank) VALUES (1, 'one', '7')")
            }
        }

        assertEquals(after.table("tasks")?.columns, columnsOf(database, "tasks"))
        assertEquals(listOf("1|one|7"), rowsOf(database, "SELECT id, title, rank FROM tasks"))
    }

    @Test
    fun `adding a foreign key to an existing column rebuilds the table`() {
        val users = table("users", id(), text("name"))
        val before = schema(users, table("tasks", id(), text("title"), integer("owner", nullable = false)))
        val after = schema(
            users,
            table("tasks", id(), text("title"), integer("owner", nullable = false, references = "users")),
        )
        val sql = migrationFor(before, after)

        assertContains(sql, "owner INTEGER NOT NULL REFERENCES users(id)")

        val database = migrated(before, after) { connection ->
            connection.createStatement().use {
                it.execute("INSERT INTO users (id, name) VALUES (1, 'Alice')")
                it.execute("INSERT INTO tasks (id, title, owner) VALUES (1, 'one', 1)")
            }
        }

        assertEquals(after.table("tasks")?.columns, columnsOf(database, "tasks"))
        assertEquals(listOf("1|one|1"), rowsOf(database, "SELECT id, title, owner FROM tasks"))
    }

    @Test
    fun `a rebuild that leaves a dangling reference fails and rolls back`() {
        val users = table("users", id(), text("name"))
        val before = schema(users, table("tasks", id(), integer("owner", nullable = false)))
        val after = schema(
            users,
            table("tasks", id(), integer("owner", nullable = false, references = "users")),
        )

        val database = file()
        create(database, before)
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use {
                // A row pointing at a user that does not exist: legal before the constraint, not after.
                it.execute("INSERT INTO tasks (id, owner) VALUES (1, 404)")
            }
        }
        val store = Migrations(directory())
        store.write(store.nextFile("add fk"), migrationFor(before, after))

        val failure = assertFailsWith<IllegalStateException> { applyMigrations(database, store) }

        assertContains(failure.message.orEmpty(), "Foreign key violations")
        assertEquals(emptyList(), appliedMigrations(database), "a failed migration is not recorded")
    }

    @Test
    fun `a rebuild that would lose an index stops rather than losing it`() {
        val before = schema(table("tasks", id(), text("title"), text("rank")))
        val after = schema(table("tasks", id(), text("title"), integer("rank", nullable = false)))

        val database = file()
        create(database, before)
        DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
            connection.createStatement().use { it.execute("CREATE INDEX tasks_by_rank ON tasks(rank)") }
        }
        val store = Migrations(directory())
        val file = store.nextFile("retype rank")
        val sql = migrationFor(before, after).replace("$ACKNOWLEDGEMENT_MARKER\n", "")
        store.write(file, sql)

        // The generator cannot see what a database has on it, so the runner is what notices. Views are the
        // classic version of this trap; an index is the easy one to demonstrate.
        val failure = assertFailsWith<IllegalStateException> { applyMigrations(database, store) }
        assertContains(failure.message.orEmpty(), "index tasks_by_rank")

        // With the index re-created in the migration, it goes through.
        store.write(file, sql + "\nCREATE INDEX tasks_by_rank ON tasks(rank);\n")
        applyMigrations(database, store)

        assertTrue("index tasks_by_rank" in objectsOf(database))
    }

    @Test
    fun `a migration is applied once`() {
        val before = schema(table("tasks", id(), text("title")))
        val after = schema(table("tasks", id(), text("title"), text("notes", nullable = true)))

        val database = file()
        create(database, before)
        val store = Migrations(directory())
        store.write(store.nextFile("add notes"), migrationFor(before, after))

        assertEquals(1, applyMigrations(database, store).applied.size)
        assertEquals(0, applyMigrations(database, store).applied.size)
    }

    @Test
    fun `a new table is created with its foreign keys`() {
        val after = schema(
            table("users", id(), text("name")),
            table("tasks", id(), integer("owner", nullable = false, references = "users")),
        )
        val database = migrated(SchemaFile.Empty, after) { }

        assertEquals(after.table("tasks")?.columns, columnsOf(database, "tasks"))
        assertEquals(after.table("users")?.columns, columnsOf(database, "users"))
    }

    @Test
    fun `migrations are numbered in order and named after what they do`() {
        val store = Migrations(directory())
        val first = store.nextFile("share todos by team")
        store.write(first, "-- one\n")
        val second = store.nextFile("Add   archived!")

        assertEquals("0001_share_todos_by_team.sql", first.name)
        assertEquals("0002_add_archived.sql", second.name)
    }
}

// ---- Fixtures -----------------------------------------------------------------------------------------

private fun schema(vararg tables: TableSchema) = SchemaFile(tables = tables.toList())

private fun table(name: String, vararg columns: ColumnSchema) = TableSchema(name, columns = columns.toList())

private fun id() = ColumnSchema("id", "INTEGER", nullable = false, primaryKey = true)

private fun text(name: String, nullable: Boolean = false) = ColumnSchema(name, "TEXT", nullable)

private fun integer(name: String, nullable: Boolean = false, references: String? = null) =
    ColumnSchema(name, "INTEGER", nullable, references)

private fun migrationFor(before: SchemaFile, after: SchemaFile): String =
    migrationSql(before, after, diff(before, after))

/** Builds a database from [before], lets [seed] put rows in it, then applies the generated migration. */
private fun migrated(
    before: SchemaFile,
    after: SchemaFile,
    acknowledge: Boolean = true,
    seed: (java.sql.Connection) -> Unit,
): File {
    val database = file()
    create(database, before)
    DriverManager.getConnection("jdbc:sqlite:$database").use(seed)

    val store = Migrations(directory())
    val sql = migrationFor(before, after)
    store.write(store.nextFile("test"), if (acknowledge) sql.replace("$ACKNOWLEDGEMENT_MARKER\n", "") else sql)
    applyMigrations(database, store)
    return database
}

private fun create(database: File, schema: SchemaFile) {
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            for (table in schema.tables) {
                val columns = table.columns.joinToString(", ") { it.definition() }
                statement.execute("CREATE TABLE ${table.name} ($columns)")
            }
        }
    }
}

/** The schema as the database now has it, in the same shape the snapshot records. */
private fun columnsOf(database: File, table: String): List<ColumnSchema> =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        val references = mutableMapOf<String, String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA foreign_key_list($table)").use { results ->
                while (results.next()) references[results.getString("from")] = results.getString("table")
            }
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { results ->
                buildList {
                    while (results.next()) {
                        val name = results.getString("name")
                        val primaryKey = results.getInt("pk") == 1
                        add(
                            ColumnSchema(
                                name = name,
                                type = results.getString("type"),
                                // SQLite reports `notnull = 0` for an INTEGER PRIMARY KEY, because it is the
                                // rowid alias and writing NULL there means "assign one". It is never
                                // actually null, so reading it back as optional would be a false difference.
                                nullable = !primaryKey && results.getInt("notnull") == 0,
                                references = references[name],
                                primaryKey = primaryKey,
                            ),
                        )
                    }
                }
            }
        }
    }

private fun rowsOf(database: File, sql: String): List<String> =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { results ->
                buildList {
                    while (results.next()) {
                        add((1..results.metaData.columnCount).joinToString("|") { results.getString(it) ?: "null" })
                    }
                }
            }
        }
    }

private fun objectsOf(database: File): Set<String> =
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                "SELECT type, name FROM sqlite_schema WHERE type IN ('index','trigger','view') " +
                    "AND name NOT LIKE 'sqlite_%'",
            ).use { results ->
                buildSet { while (results.next()) add("${results.getString(1)} ${results.getString(2)}") }
            }
        }
    }

private fun appliedMigrations(database: File): List<String> =
    rowsOf(database, "SELECT name FROM jetlin_migrations")

private fun file(): File = Files.createTempDirectory("jetlin-db-gradle").toFile().resolve("test.db")

private fun directory(): File = Files.createTempDirectory("jetlin-db-migrations").toFile()
