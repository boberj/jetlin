package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * A typed key for a value that the application attaches to a session.
 *
 * Jetlin knows about paths and query strings, but nothing about authentication, tenancy, or locale.
 * Instead of a type parameter on the whole configuration DSL, an application declares its own keys
 * and reads the values back with the right type:
 *
 * ```kotlin
 * val CurrentUser = AttributeKey<User>("user")
 *
 * jetlin {
 *     attributes { call -> mapOf(CurrentUser to call.principal<User>()) }
 * }
 *
 * @Composable fun Header() {
 *     val user = LocalRequest.current[CurrentUser]
 * }
 * ```
 *
 * Keys are compared by identity, not by [name]. The name only makes debugging easier.
 *
 * @property name a name for debugging output.
 */
public class AttributeKey<T>(public val name: String) {
    override fun toString(): String = name
}

/**
 * What the browser asked for, and whatever the application attached to the session.
 *
 * It's created with the session and updated on navigation, so a composable at any depth can read
 * the current path or a path parameter without it being passed down as an argument.
 *
 * @property path the path, without the query string.
 * @property pathParams the parameters that the matched route extracted from [path], by name.
 * @property queryParams the query parameters, by name. A name can appear more than once.
 * @property headers the headers of the HTTP request that created the session.
 * @param attributes the values that the application attached, by key.
 */
public class RequestContext(
    public val path: String,
    public val pathParams: Map<String, String> = emptyMap(),
    public val queryParams: Map<String, List<String>> = emptyMap(),
    public val headers: Map<String, List<String>> = emptyMap(),
    private val attributes: Map<AttributeKey<*>, Any?> = emptyMap(),
) {
    /** Returns the value attached under [key], or `null` if there isn't one. */
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(key: AttributeKey<T>): T? = attributes[key] as T?

    /** The path and the query string, as the address bar shows them. */
    public val url: String
        get() = if (queryParams.isEmpty()) path else "$path?" + queryParams.entries
            .flatMap { (name, values) -> values.map { "$name=$it" } }
            .joinToString("&")

    /**
     * Returns a copy with one attribute set.
     *
     * Tests use this to supply a principal. Applications can use it for a value that's known only
     * after the context was created.
     */
    public fun <T> with(key: AttributeKey<T>, value: T?): RequestContext =
        RequestContext(path, pathParams, queryParams, headers, attributes + (key to value))

    /** Returns a copy with the path parameters [params]. It keeps the attributes and the query. */
    public fun withPathParams(params: Map<String, String>): RequestContext =
        RequestContext(path, params, queryParams, headers, attributes)

    /**
     * Returns a copy for the location [url].
     *
     * It keeps the headers and attributes, which belong to the session, and replaces the path and
     * the query. The path parameters are cleared until the router matches the new path.
     */
    public fun forUrl(url: String): RequestContext {
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', missingDelimiterValue = "")
        return RequestContext(path, emptyMap(), parseQuery(query), headers, attributes)
    }
}

/** Parses a query string, without the leading `?`, into values by name. It doesn't decode them. */
internal fun parseQuery(query: String): Map<String, List<String>> {
    if (query.isEmpty()) return emptyMap()
    return query.split('&')
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
        .groupBy({ it.first }, { it.second })
}

/**
 * The current request.
 *
 * It's a dynamic local, not a static one, so a navigation invalidates only the composables that
 * read it.
 */
public val LocalRequest: ProvidableCompositionLocal<RequestContext> =
    compositionLocalOf { error("No RequestContext in composition; content must be hosted by a LiveView") }

/**
 * Returns the value of a path parameter of the matched route, such as `id` for `/todo/{id}`.
 *
 * @throws IllegalStateException if the route has no parameter called [name].
 */
@Composable
public fun pathParam(name: String): String =
    LocalRequest.current.pathParams[name]
        ?: error("No path parameter '$name' in route '${LocalRequest.current.path}'")

/** Returns the first value of the query parameter [name], or `null` if it's missing. */
@Composable
public fun queryParam(name: String): String? = LocalRequest.current.queryParams[name]?.firstOrNull()
