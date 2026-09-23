package jetlin.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests the code that the processor generated for this module's entities.
 *
 * The persistence tests already show that the generated tables store and load correctly. They were
 * written against a hand-written schema, and pass unchanged against the generated one. This file
 * covers what a round trip can't show: that a policy can refer to a column, and that a draft writes
 * to the record.
 */
class GeneratedSchemaTest {

    @Test
    fun `a generated column is the object the table flushes`(): Unit {
        // It must be the same instance. A policy uses `when (column) { Tasks.archived -> … }`, and a
        // different Column object with the same name would never match.
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
        // `owner` is a constructor property, written once on insert, so the draft has no setter for
        // it. The compiler checks this: the code below wouldn't compile if a setter existed.
        val names = TaskDraft::class.java.methods
            .filter { it.name.startsWith("set") }
            // An internal setter's JVM name includes the module name, as in `setTitle$jetlin_jetlin_db_test`.
            .map { it.name.removePrefix("set").substringBefore('$').replaceFirstChar(Char::lowercaseChar) }
            .toSet()

        assertEquals(setOf("title", "done", "archived", "project"), names)
    }
}
