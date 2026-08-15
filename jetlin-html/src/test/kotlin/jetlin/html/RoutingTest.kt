package jetlin.html

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoutePatternTest {

    @Test
    fun `a literal path matches itself and nothing else`() {
        val pattern = RoutePattern("/about")
        assertEquals(emptyMap(), pattern.match("/about"))
        assertNull(pattern.match("/abouts"))
        assertNull(pattern.match("/about/extra"))
        assertNull(pattern.match("/"))
    }

    @Test
    fun `the root path matches`() {
        assertEquals(emptyMap(), RoutePattern("/").match("/"))
        assertNull(RoutePattern("/").match("/todo"))
    }

    @Test
    fun `a parameter captures one segment`() {
        assertEquals(mapOf("id" to "42"), RoutePattern("/todo/{id}").match("/todo/42"))
    }

    @Test
    fun `a parameter does not span a slash`() {
        assertNull(RoutePattern("/todo/{id}").match("/todo/42/edit"))
    }

    @Test
    fun `multiple parameters are captured by name`() {
        assertEquals(
            mapOf("list" to "work", "id" to "7"),
            RoutePattern("/list/{list}/item/{id}").match("/list/work/item/7"),
        )
    }

    @Test
    fun `trailing slashes are not significant`() {
        assertEquals(mapOf("id" to "42"), RoutePattern("/todo/{id}/").match("/todo/42"))
        assertEquals(mapOf("id" to "42"), RoutePattern("/todo/{id}").match("/todo/42/"))
    }
}

class RouterTest {

    private val router = Router(
        listOf(
            RoutePattern("/todo/{id}") to "detail",
            RoutePattern("/") to "list",
            RoutePattern("/todo/new") to "create",
        ),
    )

    @Test
    fun `resolves each route to its value`() {
        assertEquals("list", router.resolve("/")?.value)
        assertEquals("detail", router.resolve("/todo/9")?.value)
    }

    @Test
    fun `a literal segment wins over a parameter regardless of declaration order`() {
        // /todo/new was registered last but must not be swallowed by /todo/{id}.
        assertEquals("create", router.resolve("/todo/new")?.value)
    }

    @Test
    fun `exposes the captured parameters`() {
        assertEquals(mapOf("id" to "9"), router.resolve("/todo/9")?.pathParams)
    }

    @Test
    fun `an unregistered path resolves to nothing`() {
        assertNull(router.resolve("/nope"))
    }
}

class RequestContextTest {

    @Test
    fun `url recombines path and query`() {
        val request = RequestContext(path = "/search", queryParams = mapOf("q" to listOf("kotlin")))
        assertEquals("/search?q=kotlin", request.url)
        assertEquals("/search", RequestContext(path = "/search").url)
    }

    @Test
    fun `forUrl parses the query string and drops stale path params`() {
        val before = RequestContext(path = "/todo/1", pathParams = mapOf("id" to "1"))
        val after = before.forUrl("/search?q=kotlin&tag=web")

        assertEquals("/search", after.path)
        assertEquals(emptyMap(), after.pathParams)
        assertEquals(listOf("kotlin"), after.queryParams["q"])
        assertEquals(listOf("web"), after.queryParams["tag"])
    }

    @Test
    fun `attributes survive navigation because they belong to the session`() {
        val user = AttributeKey<String>("user")
        val before = RequestContext(path = "/", attributes = mapOf(user to "ada"))
        assertEquals("ada", before.forUrl("/about")[user])
    }

    @Test
    fun `an unset attribute reads as null rather than failing`() {
        assertNull(RequestContext(path = "/")[AttributeKey<String>("absent")])
    }
}
