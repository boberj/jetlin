package jetlin.db

import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests opening a database whose schema doesn't match the entities.
 *
 * Without the check, this would fail silently. `CREATE TABLE IF NOT EXISTS` ignores a table that exists
 * with a different structure, so an unapplied migration would only show up as a failed insert during a
 * deploy, or as a column that is never read.
 */
class SchemaVerificationTest {

    @Test
    fun `a database the entities describe opens`(): Unit = withStore { file ->
        Db.open(file, schema()).use { db ->
            val alice = db.transact { db.insert(User("Alice")) }
            db.transact { db.insert(Task(alice, "Read the plan")) }
        }

        Db.open(file, schema()).use { db ->
            assertEquals(1, db.resident.all(Task::class).size)
        }
    }

    @Test
    fun `a missing column refuses to boot, naming it`(): Unit = withStore { file ->
        Db.open(file, schema()).use { }
        // This is what an unapplied migration looks like in the database.
        execute(file, "ALTER TABLE tasks DROP COLUMN archived")

        val failure = assertFailsWith<IllegalStateException> { Db.open(file, schema()).close() }

        assertContains(failure.message.orEmpty(), "'tasks.archived' is missing")
        assertContains(failure.message.orEmpty(), "dbMigrate")
    }

    @Test
    fun `a column stored with the wrong type refuses to boot`(): Unit = withStore { file ->
        execute(
            file,
            "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, admin TEXT NOT NULL)",
        )

        val failure = assertFailsWith<IllegalStateException> { Db.open(file, schema()).close() }

        assertContains(failure.message.orEmpty(), "'users.admin' is TEXT, declared INTEGER")
    }

    @Test
    fun `a column that changed from required to optional refuses to boot`(): Unit = withStore { file ->
        execute(file, "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT, admin INTEGER NOT NULL)")

        val failure = assertFailsWith<IllegalStateException> { Db.open(file, schema()).close() }

        assertContains(failure.message.orEmpty(), "'users.name' is optional, declared the other way")
    }

    @Test
    fun `a stored column no entity declares refuses to boot`(): Unit = withStore { file ->
        Db.open(file, schema()).use { }
        execute(file, "ALTER TABLE tasks ADD COLUMN legacy_notes TEXT")

        val failure = assertFailsWith<IllegalStateException> { Db.open(file, schema()).close() }

        // An extra column fails startup instead of being ignored. It means either a partly applied
        // migration or a column removed from an entity without a migration, and both need a decision.
        assertContains(failure.message.orEmpty(), "'tasks.legacy_notes' is stored but no entity declares it")
    }

    @Test
    fun `a missing foreign key refuses to boot`(): Unit = withStore { file ->
        execute(file, "CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL, admin INTEGER NOT NULL)")
        execute(file, "CREATE TABLE projects (id INTEGER PRIMARY KEY, owner INTEGER NOT NULL, name TEXT NOT NULL, shared INTEGER NOT NULL)")

        val failure = assertFailsWith<IllegalStateException> { Db.open(file, schema()).close() }

        assertContains(failure.message.orEmpty(), "'projects.owner' does not reference 'users'")
    }
}

@OptIn(ExperimentalPathApi::class)
private fun withStore(block: (Path) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        block(directory.resolve("test.db"))
    } finally {
        directory.deleteRecursively()
    }
}

private fun execute(file: Path, sql: String) {
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { it.execute(sql) }
    }
}
