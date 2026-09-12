package jetlin.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What the processor generated for the entities in this module.
 *
 * The persistence tests already prove the generated tables store and load correctly — they were written
 * against a hand-written schema and run unchanged against this one. What is left to check is the part
 * the round trip cannot see: that a policy can name a column, and that a draft writes the record.
 */
class GeneratedSchemaTest {

    @Test
    fun `a generated column is the object the table flushes`(): Unit {
        // Identity matters: a policy writes `when (column) { Tasks.archived -> … }`, and a second equal
        // looking Column would silently never match.
        assertSame(Tasks.title, Tasks.table.column("title"))
        assertSame(Tasks.owner, Tasks.table.column("owner"))
    }

    @Test
    fun `the generated columns are exactly the entity's stored fields`(): Unit {
        assertEquals(
            listOf("owner", "title", "done", "archived", "project"),
            Tasks.table.columns.map { it.name },
            "declaration order is the column order, because migrations read it",
        )
        assertEquals(listOf("name", "admin"), Users.table.columns.map { it.name })
        assertEquals(listOf("owner", "name", "shared"), Projects.table.columns.map { it.name })
    }

    @Test
    fun `a reference column knows what it points at and whether it may be absent`(): Unit {
        assertEquals(User::class, Tasks.owner.references)
        assertFalse(Tasks.owner.nullable, "an @Owner reference is not optional")

        assertEquals(Project::class, Tasks.project.references)
        assertTrue(Tasks.project.nullable)

        assertEquals(null, Tasks.title.references)
    }

    @Test
    fun `the table name follows the class name`(): Unit {
        assertEquals("tasks", Tasks.table.name)
        assertEquals("users", Users.table.name)
        assertEquals("projects", Projects.table.name)
    }

    @Test
    fun `the generated schema loads a table after the tables it references`(): Unit {
        val names = JetlinSchema.tables.map { it.name }

        assertTrue(
            names.indexOf("users") < names.indexOf("tasks"),
            "a task's owner resolves against what is already resident, so users load first; got $names",
        )
        assertTrue(names.indexOf("projects") < names.indexOf("tasks"), "got $names")
        assertEquals(3, names.size)
    }

    @Test
    fun `a column that does not exist is refused by name`(): Unit {
        val failure = assertFailsWith<IllegalStateException> { Tasks.table.column("titl") }

        assertEquals("Table 'tasks' has no column 'titl'", failure.message)
    }

    @Test
    fun `a generated draft writes through to the record`(): Unit {
        val alice = User("Alice")
        val task = Task(alice, "Read the plan")

        TaskDraft(task, alice).apply {
            title = "Read it twice"
            done = true
        }

        assertEquals("Read it twice", task.title)
        assertTrue(task.done)
    }

    @Test
    fun `a draft exposes the settable columns and nothing else`(): Unit {
        // `owner` is a constructor property: written once by the insert, with nothing a draft could do
        // with it. Asserted by construction — this would not compile if a draft had an owner setter.
        val names = TaskDraft::class.java.methods
            .filter { it.name.startsWith("set") }
            // An internal setter carries the module name: `setTitle$jetlin_jetlin_db_test`.
            .map { it.name.removePrefix("set").substringBefore('$').replaceFirstChar(Char::lowercaseChar) }
            .toSet()

        assertEquals(setOf("title", "done", "archived", "project"), names)
    }
}
