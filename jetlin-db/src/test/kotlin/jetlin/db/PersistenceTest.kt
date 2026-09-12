package jetlin.db

import androidx.compose.runtime.key
import jetlin.html.Div
import jetlin.html.Span
import jetlin.html.Text
import jetlin.protocol.Op
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Storage, and the property that justifies the whole design: a write the database refused was never
 * visible to anyone.
 */
class PersistenceTest {

    @Test
    fun `a committed write survives a restart`(): Unit = runTest {
        withStore { file ->
            val taskId: Long
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val task = db.transact { db.insert(Task(alice, "Read the plan")) }
                db.transact { task.done = true }
                taskId = task.id
            }

            Db.open(file, schema()).use { db ->
                val task = db.resident.find(Task::class, Id(taskId))
                assertEquals("Read the plan", task?.title)
                assertEquals(true, task?.done)
                // Not a copy of the owner: the same resident User the users table was loaded into.
                assertEquals("Alice", task?.owner?.name)
                assertEquals(db.resident.find(User::class, Id(task!!.owner.id)), task.owner)
            }
        }
    }

    @Test
    fun `a new record gets a fresh id after a restart`(): Unit = runTest {
        withStore { file ->
            val first: Long
            Db.open(file, schema()).use { db ->
                first = db.transact { db.insert(User("Alice")) }.id
            }

            Db.open(file, schema()).use { db ->
                val second = db.transact { db.insert(User("Bob")) }
                assertTrue(second.id > first, "ids must not be reused across a restart")
                assertEquals(2, db.resident.rowCount)
            }
        }
    }

    @Test
    fun `a reference survives a restart and resolves to the same object`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val inbox = db.transact { db.insert(Project(alice, "Inbox")) }
                db.transact { db.insert(Task(alice, "filed")).also { it.project = inbox } }
                db.transact { db.insert(Task(alice, "unfiled")) }
            }

            Db.open(file, schema()).use { db ->
                val inbox = db.resident.all(Project::class).single()
                val (filed, unfiled) = db.resident.all(Task::class).sortedBy { it.title }
                assertEquals(inbox, filed.project)
                assertNull(unfiled.project)
            }
        }
    }

    @Test
    fun `a transaction that throws leaves the database and every session untouched`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val task = db.transact { db.insert(Task(alice, "Read the plan")) }

                harness { Div { Span { Text(task.title) } } }.use { h ->
                    val failure = assertFailsWith<IllegalStateException> {
                        db.transact {
                            task.title = "Half written"
                            db.insert(Task(alice, "Never stored"))
                            error("the handler failed")
                        }
                    }
                    assertEquals("the handler failed", failure.message)
                    h.settle()

                    // The whole point of committing before applying: nothing reached the browser...
                    assertEquals(emptyList<Op>(), h.drain())
                    // ...nothing reached the resident graph...
                    assertEquals("Read the plan", task.title)
                    assertEquals(1, db.resident.all(Task::class).size)
                    // ...and nothing reached disk.
                    assertEquals(1, rowsIn(file, "tasks"))
                    assertEquals("Read the plan", singleValue(file, "SELECT title FROM tasks"))
                }
            }
        }
    }

    @Test
    fun `two sessions reading one record both recompose on a single write`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val task = db.transact { db.insert(Task(alice, "Read the plan")) }

                harness { Div { Span { Text(task.title) } } }.use { first ->
                    harness { Div { Span { Text(task.title) } } }.use { second ->
                        db.transact { task.title = "Read it twice" }
                        first.settle()
                        second.settle()

                        // Nothing subscribed either session to the record and nothing broadcast the
                        // change: both read the same cell, so the apply invalidated both.
                        assertEquals(listOf(Op.SetText(3, "Read it twice")), first.drain())
                        assertEquals(listOf(Op.SetText(3, "Read it twice")), second.drain())
                    }
                }
            }
        }
    }

    @Test
    fun `a session sees a row another session inserted`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }

                harness {
                    Div {
                        db.resident.all(Task::class).forEach { task ->
                            key(task.id) { Span { Text(task.title) } }
                        }
                    }
                }.use { h ->
                    db.transact { db.insert(Task(alice, "arrived")) }
                    h.settle()

                    val ops = h.drain()
                    assertEquals(1, ops.size, "expected a single Insert, got $ops")
                    assertIs<Op.Insert>(ops.single())
                }
            }
        }
    }

    @Test
    fun `writing stored state outside a transaction is refused`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }

                val failure = assertFailsWith<IllegalStateException> { alice.name = "Alice again" }

                assertTrue(
                    "transact" in failure.message.orEmpty(),
                    "the error should say where the write belongs, was: ${failure.message}",
                )
                assertEquals("Alice", alice.name)
            }
        }
    }

    @Test
    fun `a record inserted and deleted in one transaction is never written`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                db.transact {
                    val doomed = db.insert(User("Ghost"))
                    db.delete(doomed)
                }

                assertEquals(0, rowsIn(file, "users"))
                assertEquals(0, db.resident.rowCount)
            }
        }
    }

    @Test
    fun `deleting a record removes it from disk and from the graph`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val task = db.transact { db.insert(Task(alice, "Read the plan")) }

                db.transact { db.delete(task) }

                assertEquals(0, rowsIn(file, "tasks"))
                assertEquals(0, db.resident.all(Task::class).size)
            }

            // And it stays gone.
            Db.open(file, schema()).use { db -> assertEquals(0, db.resident.all(Task::class).size) }
        }
    }

    @Test
    fun `a write touches only the columns that changed`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val task = db.transact { db.insert(Task(alice, "Read the plan")) }

                db.transact { task.done = true }

                assertEquals(1, singleValue(file, "SELECT done FROM tasks"))
                assertEquals("Read the plan", singleValue(file, "SELECT title FROM tasks"))
            }
        }
    }

    @Test
    fun `a row can be deleted and its id reused in the same transaction`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                val alice = db.transact { db.insert(User("Alice")) }
                val first = unsafe("test fixture") { db.insertUnchecked(Task(alice, "first"), id = 7) }

                // What re-seeding a fixture looks like: the old row goes and a new one takes its id. The
                // flush has to order the delete before the insert, because a primary key is not deferrable
                // — unlike the foreign keys, which are checked at commit so that one transaction can both
                // create a row and point another at it.
                db.transact {
                    db.delete(first)
                    unsafe("test fixture") { db.insertUnchecked(Task(alice, "second"), id = 7) }
                }

                assertEquals("second", singleValue(file, "SELECT title FROM tasks"))
                assertEquals(1, rowsIn(file, "tasks"))
                assertEquals("second", db.resident.find(Task::class, Id(7))?.title)
            }
        }
    }

    @Test
    fun `one transaction can create a row and the row that points at it`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                // Inserted in the order the application happens to write them, which is not necessarily
                // the order the foreign keys would need if each statement were checked on its own.
                val task = db.transact {
                    val alice = db.insert(User("Alice"))
                    db.insert(Task(alice, "Read the plan"))
                }

                assertEquals(1, rowsIn(file, "tasks"))
                assertEquals("Alice", task.owner.name)
            }
        }
    }

    @Test
    fun `a write by another connection is refused rather than silently diverging`(): Unit = runTest {
        withStore { file ->
            Db.open(file, schema()).use { db ->
                db.transact { db.insert(User("Alice")) }

                DriverManager.getConnection("jdbc:sqlite:$file").use { outside ->
                    outside.createStatement().use {
                        it.execute("INSERT INTO users (id, name, admin) VALUES (9001, 'Outsider', 0)")
                    }
                }

                val failure = assertFailsWith<IllegalStateException> {
                    db.transact { db.insert(User("Bob")) }
                }

                assertTrue(
                    "data_version" in failure.message.orEmpty(),
                    "the error should name the detector, was: ${failure.message}",
                )
                // The refused transaction left nothing behind, in memory or on disk.
                assertEquals(1, db.resident.all(User::class).size)
                assertEquals(2, rowsIn(file, "users"))
            }
        }
    }
}

/** Runs [block] against a fresh database file, deleted afterwards however it ends. */
@OptIn(ExperimentalPathApi::class)
private suspend fun withStore(block: suspend (Path) -> Unit) {
    val directory = createTempDirectory("jetlin-db")
    try {
        block(directory.resolve("test.db"))
    } finally {
        directory.deleteRecursively()
    }
}

/** Reads the file with a second connection, which is what a backup tool does. */
private fun rowsIn(file: Path, table: String): Int =
    (singleValue(file, "SELECT count(*) FROM $table") as Number).toInt()

private fun singleValue(file: Path, sql: String): Any? =
    DriverManager.getConnection("jdbc:sqlite:$file").use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { results ->
                results.next()
                results.getObject(1)
            }
        }
    }
