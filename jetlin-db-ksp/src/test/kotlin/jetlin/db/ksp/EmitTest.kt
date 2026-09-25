package jetlin.db.ksp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the source code that the processor generates.
 *
 * Generated code can compile and still be wrong. For example, a loader that assigns a column after
 * construction, instead of passing it to the constructor, still compiles, but can't restore a
 * `val`. These tests check the generated code itself, not only that it compiles.
 */
class EmitTest {

    @Test
    fun `a column is read back with the accessor its type needs`() {
        val generated = emitTable(
            entity(
                "Todo",
                columns = listOf(
                    column("owner", reference = "app.User", nullable = false, settable = false),
                    column("title"),
                    column("done", kind = SqlKind.Bool, baseType = "kotlin.Boolean"),
                    column("rank", kind = SqlKind.Integer, baseType = "kotlin.Int", nullable = true),
                    column("weight", kind = SqlKind.Real, baseType = "kotlin.Float"),
                    column("notes", nullable = true),
                ),
            ),
        )

        assertContains(generated, "owner = row.reference(\"owner\", app.User::class)")
        assertContains(generated, "title = row.string(\"title\")")
        assertContains(generated, "done = row.boolean(\"done\")")
        assertContains(generated, "rank = row.intOrNull(\"rank\")")
        assertContains(generated, "weight = row.double(\"weight\").toFloat()")
        assertContains(generated, "notes = row.stringOrNull(\"notes\")")
    }

    @Test
    fun `a column the constructor takes goes through the constructor, not through a setter`() {
        val generated = emitTable(entity("Todo", columns = listOf(column("title", constructorParameter = true))))

        assertContains(generated, "title = row.string(\"title\")")
        assertFalse(
            "entity.title = " in generated,
            "a constructor parameter must not also be assigned afterwards:\n$generated",
        )
    }

    @Test
    fun `a column the constructor does not take is assigned after construction`() {
        val generated = emitTable(
            entity("Todo", columns = listOf(column("title"), column("done", constructorParameter = false))),
        )

        assertContains(generated, "entity.done = row.string(\"done\")")
    }

    @Test
    fun `only settable columns reach the draft`() {
        val generated = emitTable(
            entity(
                "Todo",
                columns = listOf(
                    column("owner", reference = "app.User", nullable = false, settable = false),
                    column("title"),
                ),
            ),
        )

        val draft = generated.substringAfter("class TodoDraft")
        assertContains(draft, "var title: kotlin.String")
        assertFalse("var owner" in draft, "an immutable column has nothing a draft can do with it:\n$draft")
    }

    @Test
    fun `a nullable reference keeps its nullability in the draft`() {
        val generated = emitTable(
            entity("Todo", columns = listOf(column("project", reference = "app.Project", nullable = true))),
        )

        assertContains(generated.substringAfter("class TodoDraft"), "var project: app.Project?")
    }

    @Test
    fun `the columns exposed are the ones the table declares`() {
        val generated = emitTable(entity("Todo", columns = listOf(column("title"), column("done"))))

        assertContains(generated, "val title: jetlin.db.Column<app.Todo> =\n        table.column(\"title\")")
        assertContains(generated, "val done: jetlin.db.Column<app.Todo> =\n        table.column(\"done\")")
    }

    @Test
    fun `an internal entity generates internal declarations`() {
        val generated = emitTable(entity("Todo", isInternal = true))

        assertContains(generated, "internal object Todos")
        assertContains(generated, "internal class TodoDraft")
        assertFalse("public " in generated, "nothing public may expose an internal entity:\n$generated")
    }

    @Test
    fun `the questions a page asks go through the same gate as the writes they predict`() {
        val generated = emitTable(entity("Todo", columns = listOf(column("title"))))

        assertContains(
            generated,
            "fun app.Todo.canUpdate(): Boolean =\n    jetlin.db.Gate.canUpdate(this, app.Todos.policy, principal)",
        )
        assertContains(
            generated,
            "fun app.Todo.canUpdate(column: jetlin.db.Column<app.Todo>): Boolean =\n" +
                "    jetlin.db.Gate.canUpdate(this, column, app.Todos.policy, principal)",
        )
        assertContains(
            generated,
            "fun app.Todo.canDelete(): Boolean =\n    jetlin.db.Gate.canDelete(this, app.Todos.policy, principal)",
        )
    }

    @Test
    fun `an owner that can change hands gets transfer functions`() {
        val generated = emitTable(
            entity("Doc", columns = listOf(column("owner", reference = "app.User", owner = true), column("text"))),
        )

        assertContains(
            generated,
            "fun app.Doc.transferTo(to: app.User): Unit =\n" +
                "    jetlin.db.Gate.transfer(this, to, app.Docs.policy, principal) { owner = it }",
        )
        assertContains(
            generated,
            "fun app.Doc.canTransferTo(to: app.User): Boolean =\n" +
                "    jetlin.db.Gate.canTransfer(this, to, app.Docs.policy, principal)",
        )
    }

    @Test
    fun `an owner that can't change hands, or isn't a principal, gets none`() {
        val fixed = column("owner", reference = "app.User", owner = true, settable = false)
        val team = column("owner", reference = "app.Team", owner = true)

        for (owner in listOf(fixed, team)) {
            val generated = emitTable(entity("Doc", columns = listOf(owner, column("text"))))
            assertFalse("transferTo" in generated, "no transfer for $owner:\n$generated")
        }
        assertFalse("transferTo" in emitTable(entity("Doc", columns = listOf(column("text")))))
    }

    @Test
    fun `the schema lists tables in the order it was given`() {
        val generated = emitSchema(listOf(entity("User"), entity("Todo")), "app", "AppSchema")

        assertContains(generated, "package app")
        assertEquals(
            listOf("Users.table,", "Todos.table,"),
            generated.lines().map { it.trim() }.filter { it.endsWith(".table,") },
        )
    }

    @Test
    fun `an entity in another package is qualified in the schema`() {
        val generated = emitSchema(listOf(entity("User")), "app.schema", "AppSchema")

        assertContains(generated, "app.Users.table,")
    }

    @Test
    fun `the snapshot records the id column, foreign keys and the owner`() {
        val snapshot = emitSnapshot(
            listOf(
                entity("User"),
                entity(
                    "Todo",
                    columns = listOf(
                        column("owner", reference = "app.User", nullable = false, owner = true),
                        column("title"),
                    ),
                ),
            ),
        )

        assertContains(snapshot, "\"name\": \"todos\"")
        assertContains(snapshot, "{ \"name\": \"id\", \"type\": \"INTEGER\", \"nullable\": false, \"primaryKey\": true },")
        assertContains(snapshot, "\"references\": \"users\"")
        assertContains(snapshot, "\"owner\": true")
        // Sorted, so reordering entity declarations doesn't change the schema file.
        assertTrue(snapshot.indexOf("\"todos\"") < snapshot.indexOf("\"users\""), snapshot)
    }
}
