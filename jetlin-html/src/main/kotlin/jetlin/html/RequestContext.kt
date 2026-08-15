package jetlin.html

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Typed key for a value the application attaches to a session.
 *
 * Jetlin knows about paths and query strings but nothing about authentication, tenancy or locale.
 * Rather than push a type parameter through the whole configuration DSL, an application declares its
 * own keys and reads them back with the right type:
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
 * Identity, not [name], distinguishes keys; the name only makes debugging readable.
 */
public class AttributeKey<T>(public val name: String) {
    override fun toString(): String = name
}

/**
 * What the browser asked for, and whatever the application chose to attach to the session.
 *
 * Resolved once when the session is created and updated on navigation, so a composable can read the
 * current path or a path parameter at any depth without it being threaded through as arguments.
 */
public class RequestContext(
    public val path: String,
    public val pathParams: Map<String, String> = emptyMap(),
    public val queryParams: Map<String, List<String>> = emptyMap(),
    public val headers: Map<String, List<String>> = emptyMap(),
    private val attributes: Map<AttributeKey<*>, Any?> = emptyMap(),
) {
    @Suppress("UNCHECKED_CAST")
    public operator fun <T> get(key: AttributeKey<T>): T? = attributes[key] as T?

    /** Path plus query string, as it should appear in the address bar. */
    public val url: String
        get() = if (queryParams.isEmpty()) path else "$path?" + queryParams.entries
            .flatMap { (name, values) -> values.map { "$name=$it" } }
            .joinToString("&")

    /** Copy carrying the parameters a route match extracted. Attributes and query are preserved. */
    public fun withPathParams(params: Map<String, String>): RequestContext =
        RequestContext(path, params, queryParams, headers, attributes)

    /** Copy for a new location, keeping the attributes that belong to the session rather than the request. */
    public fun forUrl(url: String): RequestContext {
        val path = url.substringBefore('?')
        val query = url.substringAfter('?', missingDelimiterValue = "")
        return RequestContext(path, emptyMap(), parseQuery(query), headers, attributes)
    }
}

internal fun parseQuery(query: String): Map<String, List<String>> {
    if (query.isEmpty()) return emptyMap()
    return query.split('&')
        .filter { it.isNotEmpty() }
        .map { it.substringBefore('=') to it.substringAfter('=', missingDelimiterValue = "") }
        .groupBy({ it.first }, { it.second })
}

/**
 * The current request. Dynamic rather than static, so that a navigation invalidates only the
 * composables that actually read it.
 */
public val LocalRequest: ProvidableCompositionLocal<RequestContext> =
    compositionLocalOf { error("No RequestContext in composition; content must be hosted by a LiveView") }

/** Value of a path parameter declared by the matched route, e.g. `id` for `/todo/{id}`. */
@Composable
public fun pathParam(name: String): String =
    LocalRequest.current.pathParams[name]
        ?: error("No path parameter '$name' in route '${LocalRequest.current.path}'")

/** First value of a query parameter, or null when absent. */
@Composable
public fun queryParam(name: String): String? = LocalRequest.current.queryParams[name]?.firstOrNull()
