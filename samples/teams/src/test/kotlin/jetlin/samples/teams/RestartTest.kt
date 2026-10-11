package jetlin.samples.teams

import java.nio.file.Path
import java.sql.DriverManager
import jetlin.db.migrate.Migration
import jetlin.db.migrate.MigrationSource
import jetlin.db.migrate.migrate
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Tests that the sample's database survives restarts, including one after a schema change.
 *
 * Its migrations come from the classpath, where the `jetlin.db` plugin packaged them, exactly as in
 * the deployed JAR.
 */
class RestartTest {

    @Test
    fun `the packaged migrations are on the classpath`() {
        assertEquals(listOf("0001_initial_schema.sql"), MigrationSource.Classpath.load().map { it.name })
    }

    @Test
    fun `a restart keeps the data, and doesn't seed again`(): Unit = withFile { file ->
        val seeded = openSeeded(file).use { it.resident.recordCount }

        openSeeded(file).use { db -> assertEquals(seeded, db.resident.recordCount) }
    }

    @Test
    fun `a restart after a schema change migrates the data`(): Unit = withFile { file ->
        // The previous version of the sample, before todos could be archived, has run and stored a todo.
        val current = MigrationSource.Classpath.load().single()
        val previous = Migration(current.name, current.sql.replace("  archived INTEGER NOT NULL,\n", ""))
        migrate(file, listOf(previous))
        execute(
            file,
            "INSERT INTO users (id, admin, email, name) VALUES (1, 0, 'alice@example.com', 'Alice')",
            "INSERT INTO todos (id, done, owner, title) VALUES (1, 0, 1, 'Written by the previous version')",
        )

        // This version adds the column, with a migration, and restarts.
        val addArchived = Migration("0002_archive_todos.sql", "ALTER TABLE todos ADD COLUMN archived INTEGER NOT NULL DEFAULT 0;")
        openSeeded(file, MigrationSource { listOf(previous, addArchived) }).use { db ->
            val todo = with(db.user("alice@example.com")) { Todos.all(db).single() }
            assertEquals("Written by the previous version", todo.title)
            assertFalse(todo.archived)
        }
    }
}

@OptIn(ExperimentalPathApi::class)
private fun withFile(block: (Path) -> Unit) {
    val directory = createTempDirectory("jetlin-teams-restart")
    try {
        block(directory.resolve("app.db"))
    } finally {
        directory.deleteRecursively()
    }
}

private fun execute(file: Path, vararg sql: String) {
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { statement -> sql.forEach { statement.execute(it) } }
    }
}
