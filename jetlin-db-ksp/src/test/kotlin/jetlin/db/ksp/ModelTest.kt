package jetlin.db.ksp

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests the processor's validation rules directly.
 *
 * Compiling a file that should fail would show only that the build failed, not that it failed for
 * the right reason or with a helpful message. Users see the messages, so these tests assert on their
 * wording.
 */
class ModelTest {

    @Test
    fun `an entity with no policy is rejected, by name, with the fix`() {
        val errors = validate(entity("Todo", hasPolicy = false))

        assertEquals(1, errors.size, "expected exactly one complaint, got $errors")
        val message = errors.single()
        assertContains(message, "Todo")
        assertContains(message, "declares no policy")
        // The message must explain the fix, because whoever sees it usually meets the rule for the
        // first time.
        assertContains(message, "Policy<Todo, YourUser>")
        assertContains(message, "owned(Todo::owner)")
    }

    @Test
    fun `a policy copied from another entity is rejected`() {
        val errors = validate(entity("Task", policySubject = "app.Todo"))

        assertEquals(1, errors.size, "expected exactly one complaint, got $errors")
        assertContains(errors.single(), "has a policy for app.Todo, not for itself")
    }

    @Test
    fun `a policy for the entity itself is accepted`() {
        assertEquals(emptyList(), validate(entity("Task", policySubject = "app.Task")))
    }

    @Test
    fun `a column that is neither settable nor a constructor parameter cannot be loaded`() {
        val errors = validate(
            entity("Todo", columns = listOf(column("title", settable = false, constructorParameter = false))),
        )

        assertEquals(1, errors.size, "expected exactly one complaint, got $errors")
        assertContains(errors.single(), "Todo.title can never be loaded")
    }

    @Test
    fun `a required constructor parameter that is not stored cannot be loaded`() {
        val errors = validate(
            entity("Todo", parameters = listOf(ParameterModel("clock", hasDefault = false))),
        )

        assertEquals(1, errors.size, "expected exactly one complaint, got $errors")
        assertContains(errors.single(), "requires 'clock'")
    }

    @Test
    fun `a constructor parameter with a default needs no column`() {
        assertEquals(
            emptyList(),
            validate(entity("Todo", parameters = listOf(ParameterModel("clock", hasDefault = true)))),
        )
    }

    @Test
    fun `an entity with no columns is rejected`() {
        val errors = validate(entity("Todo", columns = emptyList()))

        assertEquals(1, errors.size, "expected exactly one complaint, got $errors")
        assertContains(errors.single(), "no stored columns")
    }

    @Test
    fun `tables are ordered after the tables their non-null references point at`() {
        val project = entity("Project")
        val user = entity("User")
        val todo = entity(
            "Todo",
            columns = listOf(
                column("owner", reference = "app.User", nullable = false),
                column("project", reference = "app.Project", nullable = true),
            ),
        )

        val ordered = assertIs<OrderResult.Ordered>(orderForLoad(listOf(todo, project, user)))

        val names = ordered.entities.map { it.simpleName }
        assertTrue(names.indexOf("User") < names.indexOf("Todo"), "got $names")
        assertEquals(3, names.size)
    }

    @Test
    fun `a nullable reference does not constrain the order`() {
        // A null reference needs nothing loaded to resolve, so a cycle through a nullable reference
        // can still be loaded, and doesn't count as a cycle.
        val a = entity("A", columns = listOf(column("b", reference = "app.B", nullable = true)))
        val b = entity("B", columns = listOf(column("a", reference = "app.A", nullable = true)))

        assertIs<OrderResult.Ordered>(orderForLoad(listOf(a, b)))
    }

    @Test
    fun `a cycle of non-null references is reported with both entities named`() {
        val a = entity("A", columns = listOf(column("b", reference = "app.B", nullable = false)))
        val b = entity("B", columns = listOf(column("a", reference = "app.A", nullable = false)))

        val cycle = assertIs<OrderResult.Cycle>(orderForLoad(listOf(a, b)))

        assertEquals(listOf("A", "B"), cycle.entities)
    }

    @Test
    fun `a self reference does not count as a cycle`() {
        val node = entity("Node", columns = listOf(column("parent", reference = "app.Node", nullable = false)))

        assertIs<OrderResult.Ordered>(orderForLoad(listOf(node)))
    }

    @Test
    fun `table names follow the class name, and say so when they cannot`() {
        assertEquals("todos", plural(snakeCase("Todo")))
        assertEquals("my_things", plural(snakeCase("MyThing")))
        assertEquals("statuses", plural(snakeCase("Status")))
        assertEquals("stories", plural(snakeCase("Story")))
        // The rule doesn't handle every word: `Person` becomes `persons`. That's why @Entity
        // accepts a table name.
        assertEquals("persons", plural(snakeCase("Person")))
    }
}

internal fun entity(
    name: String,
    hasPolicy: Boolean = true,
    policySubject: String? = null,
    columns: List<ColumnModel> = listOf(column("title")),
    parameters: List<ParameterModel> = emptyList(),
    isInternal: Boolean = false,
): EntityModel = EntityModel(
    packageName = "app",
    simpleName = name,
    tableName = plural(snakeCase(name)),
    isInternal = isInternal,
    hasPolicy = hasPolicy,
    principalType = if (hasPolicy) "app.User" else null,
    policySubject = policySubject,
    columns = columns,
    constructorParameters = parameters + columns.filter { it.constructorParameter }
        .map { ParameterModel(it.name, hasDefault = false) },
)

internal fun column(
    name: String,
    kind: SqlKind = SqlKind.Text,
    baseType: String = "kotlin.String",
    nullable: Boolean = false,
    reference: String? = null,
    settable: Boolean = true,
    owner: Boolean = false,
    constructorParameter: Boolean = true,
): ColumnModel = ColumnModel(
    name = name,
    kind = if (reference != null) SqlKind.Integer else kind,
    baseType = reference ?: baseType,
    nullable = nullable,
    reference = reference,
    settable = settable,
    owner = owner,
    constructorParameter = constructorParameter,
)
