package jetlin.db.migrate

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
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
 * Tests the engine against real database files: where a database's history starts, and what stops a
 * migration from running.
 *
 * The generated SQL is tested in the plugin, which owns the generator. These migrations are written by
 * hand, so each test shows exactly what the database goes through.
 */
class MigrateTest {

    private val initial = Migration(
        "0001_initial.sql",
        """
        CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL);
        CREATE TABLE tasks (id INTEGER PRIMARY KEY, owner INTEGER NOT NULL REFERENCES users(id), title TEXT NOT NULL);
        """.trimIndent(),
    )
    private val addNotes = Migration("0002_add_notes.sql", "ALTER TABLE tasks ADD COLUMN notes TEXT;")
    private val addDone = Migration("0003_add_done.sql", "ALTER TABLE tasks ADD COLUMN done INTEGER NOT NULL DEFAULT 0;")

    // ---- Where history starts -----------------------------------------------------------------------

    @Test
    fun `a new database gets every migration, and no backup`() {
        val database = file()

        val result = migrate(database, listOf(initial, addNotes))

        assertEquals(listOf("0001_initial.sql", "0002_add_notes.sql"), result.applied)
        assertEquals(listOf("0001_initial.sql", "0002_add_notes.sql"), history(database))
        assertEquals(emptyList(), backups(database), "there was nothing to back up")
    }

    @Test
    fun `a database with history gets only what it hasn't had`() {
        val database = file()
        migrate(database, listOf(initial))

        val result = migrate(database, listOf(initial, addNotes))

        assertEquals(listOf("0002_add_notes.sql"), result.applied)
        assertEquals(listOf("0001_initial.sql"), result.alreadyApplied)
    }

    @Test
    fun `a database with tables but no history is refused`() {
        val database = file()
        // What Db.open does with MigrationSource.None: create the tables from the entities, with no
        // history. Even though the schema is exactly what 0001 creates, nothing says 0001 ran.
        execute(
            database,
            "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)",
            "CREATE TABLE tasks (id INTEGER PRIMARY KEY, owner INTEGER NOT NULL REFERENCES users(id), title TEXT NOT NULL)",
        )

        val failure = assertFailsWith<MigrationException> { migrate(database, listOf(initial, addNotes)) }

        assertContains(failure.message.orEmpty(), "has tables but no migration history")
        assertFalse(hasHistory(database), "nothing was written")
        assertEquals(emptyList(), backups(database))
    }

    // ---- Edited migrations ---------------------------------------------------------------------------

    @Test
    fun `a migration edited after it ran stops everything`() {
        val database = file()
        migrate(database, listOf(initial))
        val edited = Migration(initial.name, initial.sql.replace("name TEXT NOT NULL", "name TEXT"))

        val failure = assertFailsWith<MigrationException> { migrate(database, listOf(edited, addNotes)) }

        assertContains(failure.message.orEmpty(), "edited after they were applied to this database: 0001_initial.sql")
        assertEquals(listOf("0001_initial.sql"), history(database), "0002 didn't run")
    }

    @Test
    fun `line endings are not an edit`() {
        val database = file()
        migrate(database, listOf(initial))

        val checkedOutOnWindows = Migration(initial.name, initial.sql.replace("\n", "\r\n"))

        assertEquals(listOf("0002_add_notes.sql"), migrate(database, listOf(checkedOutOnWindows, addNotes)).applied)
    }

    @Test
    fun `history from before hashes gains the column and is not checked`() {
        val database = file()
        // The history table as the first runner, and Pollster's copy of it, created it.
        execute(
            database,
            "CREATE TABLE jetlin_migrations (name TEXT PRIMARY KEY, applied_at TEXT NOT NULL)",
            "INSERT INTO jetlin_migrations (name, applied_at) VALUES ('0001_initial.sql', datetime('now'))",
        )
        initial.sql.split(";").filter { it.isNotBlank() }.forEach { execute(database, it) }
        // The file differs from whatever ran, but nothing recorded what ran, so there's nothing to check.
        val reformatted = Migration(initial.name, initial.sql + "\n-- reformatted\n")

        val result = migrate(database, listOf(reformatted, addNotes))

        assertEquals(listOf("0002_add_notes.sql"), result.applied)
        assertEquals(
            listOf("0001_initial.sql|null", "0002_add_notes.sql|${addNotes.sha256}"),
            rows(database, "SELECT name, sha256 FROM jetlin_migrations ORDER BY name"),
        )
    }

    // ---- What stops a migration ----------------------------------------------------------------------

    @Test
    fun `an unacknowledged migration stops the run before anything is applied`() {
        val database = file()
        migrate(database, listOf(initial))
        val dropNotes = Migration("0003_drop_notes.sql", "$ACKNOWLEDGEMENT_MARKER\nALTER TABLE tasks DROP COLUMN notes;")

        val failure = assertFailsWith<MigrationException> { migrate(database, listOf(initial, addNotes, dropNotes)) }

        assertContains(failure.message.orEmpty(), "have not been acknowledged: 0003_drop_notes.sql")
        assertEquals(listOf("0001_initial.sql"), history(database), "0002 didn't run either")
        assertEquals(emptyList(), backups(database))
    }

    @Test
    fun `a failing migration rolls back, names the statement, and leaves a backup`() {
        val database = file()
        migrate(database, listOf(initial))
        execute(database, "INSERT INTO users (id, name) VALUES (1, 'Alice')")
        val broken = Migration(
            "0003_broken.sql",
            "ALTER TABLE users ADD COLUMN email TEXT;\nUPDATE nowhere SET x = 1;",
        )

        val failure = assertFailsWith<MigrationException> { migrate(database, listOf(initial, addNotes, broken)) }

        val message = failure.message.orEmpty()
        assertContains(message, "0003_broken.sql failed at this statement")
        assertContains(message, "UPDATE nowhere SET x = 1")
        assertContains(message, "no such table: nowhere")
        assertContains(message, "0003_broken.sql was rolled back, so none of it was applied")
        assertContains(message, "These migrations before it were: 0002_add_notes.sql")
        assertEquals(listOf("0001_initial.sql", "0002_add_notes.sql"), history(database))
        assertFalse("email" in columns(database, "users"), "the first statement was rolled back too")

        // The backup is the database as it was before this run, from before 0002.
        val backup = backups(database).single()
        assertEquals("test.db.before-0002_add_notes", backup.name)
        assertEquals(listOf("0001_initial.sql"), history(backup))
        assertEquals(listOf("1|Alice"), rows(backup, "SELECT id, name FROM users"))
    }

    @Test
    fun `only the most recent backups are kept`() {
        val database = file()
        val migrations = listOf(initial) + (2..6).map { Migration("%04d_m.sql".format(it), "SELECT $it;") }
        migrate(database, migrations.take(1))

        for (count in 2..6) migrate(database, migrations.take(count), keepBackups = 3)

        assertEquals(
            listOf("test.db.before-0004_m", "test.db.before-0005_m", "test.db.before-0006_m"),
            backups(database).map { it.name },
        )
    }

    @Test
    fun `backups can be turned off`() {
        val database = file()
        migrate(database, listOf(initial))

        migrate(database, listOf(initial, addNotes), keepBackups = 0)

        assertEquals(emptyList(), backups(database))
    }

    // ---- Verify mode ---------------------------------------------------------------------------------

    @Test
    fun `verify mode fails on pending migrations, names them, and applies nothing`() {
        val database = file()
        migrate(database, listOf(initial))

        val failure = assertFailsWith<MigrationException> {
            migrate(database, listOf(initial, addNotes, addDone), mode = MigrationMode.Verify)
        }

        assertContains(failure.message.orEmpty(), "have not been applied: 0002_add_notes.sql, 0003_add_done.sql")
        assertEquals(listOf("0001_initial.sql"), history(database))
    }

    @Test
    fun `verify mode passes a current database`() {
        val database = file()
        migrate(database, listOf(initial, addNotes))

        val result = migrate(database, listOf(initial, addNotes), mode = MigrationMode.Verify)

        assertEquals(emptyList(), result.applied)
    }

    @Test
    fun `verify mode doesn't create a database that doesn't exist`() {
        val database = file()

        assertFailsWith<MigrationException> { migrate(database, listOf(initial), mode = MigrationMode.Verify) }

        assertFalse(database.exists())
    }

    // ---- Two runners ---------------------------------------------------------------------------------

    @Test
    fun `two runners started at once apply each migration once`() {
        val database = file()
        migrate(database, listOf(initial))
        // Slow enough that the second runner reaches the lock while the first holds it.
        val slow = Migration(
            "0002_fill.sql",
            """
            CREATE TABLE numbers (n INTEGER NOT NULL);
            WITH RECURSIVE count(n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM count WHERE n < 300000)
            INSERT INTO numbers SELECT n FROM count;
            """.trimIndent(),
        )
        val migrations = listOf(initial, slow, Migration("0003_add_notes.sql", addNotes.sql))

        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val results = (1..2).map {
            pool.submit<MigrationResult> {
                start.await()
                migrate(database, migrations)
            }
        }
        start.countDown()
        val applied = results.map { it.get(60, TimeUnit.SECONDS).applied }
        pool.shutdown()

        assertEquals(listOf("0002_fill.sql", "0003_add_notes.sql"), applied.flatten().sorted())
        assertEquals(listOf("0001_initial.sql", "0002_fill.sql", "0003_add_notes.sql"), history(database))
        assertEquals(listOf("300000"), rows(database, "SELECT count(*) FROM numbers"))
    }

    // ---- Sources -------------------------------------------------------------------------------------

    @Test
    fun `the classpath source reads exactly the migrations the index lists`() {
        val root = Files.createTempDirectory("jetlin-db-classpath")
        val directory = root.resolve(MigrationSource.RESOURCE_DIRECTORY).createDirectories()
        directory.resolve(initial.name).writeText(initial.sql)
        directory.resolve(addNotes.name).writeText(addNotes.sql)
        directory.resolve("0003_not_in_the_index.sql").writeText("SELECT 1;")
        root.resolve(MigrationSource.INDEX).writeText(MigrationSource.writeIndex(listOf(initial, addNotes)))

        val loaded = withClasspath(root) { MigrationSource.Classpath.load() }

        assertEquals(listOf("0001_initial.sql", "0002_add_notes.sql"), loaded.map { it.name })
        assertEquals(listOf(initial.sql, addNotes.sql), loaded.map { it.sql })
    }

    @Test
    fun `the classpath source refuses a file that doesn't match the index`() {
        val root = Files.createTempDirectory("jetlin-db-classpath")
        val directory = root.resolve(MigrationSource.RESOURCE_DIRECTORY).createDirectories()
        directory.resolve(initial.name).writeText(initial.sql + "\n-- changed after packaging\n")
        root.resolve(MigrationSource.INDEX).writeText(MigrationSource.writeIndex(listOf(initial)))

        val failure = assertFailsWith<IllegalStateException> { withClasspath(root) { MigrationSource.Classpath.load() } }

        assertContains(failure.message.orEmpty(), "does not match the hash")
    }

    @Test
    fun `without an index there are no migrations`() {
        val loaded = withClasspath(Files.createTempDirectory("jetlin-db-classpath")) { MigrationSource.Classpath.load() }

        assertEquals(emptyList(), loaded)
    }
}

// ---- Fixtures -----------------------------------------------------------------------------------------

private fun file(): Path = Files.createTempDirectory("jetlin-db-migrate").resolve("test.db")

private fun execute(database: Path, vararg sql: String) {
    DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
        connection.createStatement().use { statement -> sql.forEach { statement.execute(it) } }
    }
}

private fun rows(database: Path, sql: String): List<String> =
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

private fun history(database: Path): List<String> = rows(database, "SELECT name FROM jetlin_migrations ORDER BY name")

private fun hasHistory(database: Path): Boolean =
    rows(database, "SELECT name FROM sqlite_schema WHERE name = 'jetlin_migrations'").isNotEmpty()

private fun columns(database: Path, table: String): List<String> =
    rows(database, "SELECT name FROM pragma_table_info('$table')")

private fun backups(database: Path): List<Path> =
    database.parent.listDirectoryEntries("${database.name}.before-*").sortedBy { it.name }

/** Runs [block] with [root] as the context class loader's only extra classpath entry. */
private fun <T> withClasspath(root: Path, block: () -> T): T {
    val thread = Thread.currentThread()
    val previous = thread.contextClassLoader
    URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
        thread.contextClassLoader = loader
        try {
            return block()
        } finally {
            thread.contextClassLoader = previous
        }
    }
}
