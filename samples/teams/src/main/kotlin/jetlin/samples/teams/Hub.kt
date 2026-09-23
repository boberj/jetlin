package jetlin.samples.teams

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.Application
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.util.concurrent.ConcurrentHashMap
import jetlin.runtime.Fetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * A client for an external system (the "hub"), showing how an application uses data it doesn't own.
 *
 * The rest of this sample works with stored data: records in SQLite, held in memory and checked
 * against policies. The hub's data is different. It belongs to another system, arrives over HTTP after
 * a delay, and sometimes doesn't arrive. This file shows that the framework needs very little extra to
 * support it. A [Fetch] is snapshot state updated by a coroutine, so when a value arrives, every
 * session that read it recomposes, the same way they do after a committed transaction.
 *
 * ## Why there is no policy here
 *
 * Stored records have to go through the gate because the identity map holds one shared object per row,
 * visible to every session. This cache is different. Each profile is fetched with the credentials of
 * the principal who requested it, and cached under that principal's key, so there is no way for
 * another principal to reach it. The access question is answered by how the cache is keyed, which is
 * why this sample needs only one authorization model.
 *
 * That only holds as long as the cache is keyed by principal. A cache shared between principals, for
 * example one object for `acme/private` fetched with the credentials of whoever asked first, would
 * give Bob data fetched with Alice's access. It would need policy checks and an authority component in
 * the cache key. `docs/db-framework-plan.md` §11 describes that design; it hasn't been built because
 * nothing needs it yet.
 */
class Hub(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(CIO),
    /**
     * How often an open page refreshes the announcement.
     *
     * It is short so you can watch it work. Run `curl -X POST -d 'text' -H 'Authorization: Bearer
     * teams-application' localhost:8081/hub/announcement`, and every open window updates within this
     * interval. The application pushes nothing, and all the windows share one request per interval.
     */
    val refreshEvery: Duration = 15.seconds,
    /**
     * The scope fetches run on. It must not be a session's dispatcher.
     *
     * Each session composes on a single confined thread, so a network request on that thread would
     * stall the whole session. This scope also outlives individual sessions, which is what allows
     * several sessions to share one fetch.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    /**
     * The announcement, which is the same for everyone and is fetched with the application's own
     * credential.
     *
     * There is a single [Fetch] for the whole process, so a hundred sessions cost one request a minute.
     * This is what §11.5 of the plan calls the application-token case, and it is the only case where a
     * cache shared between principals is safe.
     */
    val announcement: Fetch<String> = Fetch(scope, ttl = 1.minutes) {
        val body = client.get("$baseUrl/hub/announcement") { bearer(APPLICATION_TOKEN) }.orRefuse()
        Json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
    }

    private val profiles = ConcurrentHashMap<String, Fetch<Profile>>()

    /**
     * This principal's profile, fetched with this principal's credential.
     *
     * The cache is keyed by principal, and that is all the access control this needs. When Bob reads
     * his profile, it creates a [Fetch] that uses Bob's token, and he has no way to refer to Alice's.
     */
    fun profile(of: User): Fetch<Profile> = profiles.computeIfAbsent(of.email) { email ->
        Fetch(scope, ttl = 30.seconds) {
            val body = client.get("$baseUrl/hub/me") { bearer(email) }.orRefuse()
            val json = Json.parseToJsonElement(body).jsonObject
            Profile(
                status = json.getValue("status").jsonPrimitive.content,
                updates = json.getValue("updates").jsonPrimitive.content.toInt(),
            )
        }
    }

    /**
     * Sets this principal's status on the hub, then refetches their profile.
     *
     * This is a suspending function, not a property assignment, so it can't be called inside
     * `db.transact { }`. That is intended: a transaction's block can't suspend because a rollback can't
     * undo an HTTP request.
     *
     * It calls `refresh` instead of `invalidate` because the user is known to be looking: they just
     * pressed the button. `invalidate` would happen to work on this page too, since the action's state
     * change recomposes the page and causes a re-read. But that depends on how this particular page is
     * built, and a command shouldn't rely on it.
     */
    suspend fun setStatus(of: User, text: String) {
        client.post("$baseUrl/hub/me/status") {
            bearer(of.email)
            setBody(text)
        }.orRefuse()
        profile(of).refresh()
    }

    override fun close() {
        scope.cancel()
        client.close()
    }
}

/** A user's profile on the hub. One request returns all of it, so it arrives as a single object. */
data class Profile(val status: String, val updates: Int)

/** The hub's reason for refusing a request. It is shown on the page and never ends the session. */
class HubRefused(message: String) : RuntimeException(message)

/** The application's own credential, used for data that is the same for every user. */
const val APPLICATION_TOKEN: String = "teams-application"

/**
 * A stub of the external system, running in the same process.
 *
 * This is not a real third-party API. It is a test fixture with a real HTTP boundary, which is
 * something tests can depend on. It is mounted on the same Ktor server as the sample, so it uses the
 * same port and needs no network access.
 *
 * Its authentication is a bearer token containing the user's email address. That is no weaker than
 * the sample's own sign-in cookie, and for the same reason: the sample is about what happens to the
 * data, not about how users prove their identity.
 */
fun Application.hubService(data: HubData = HubData()) {
    routing {
        get("/hub/announcement") {
            if (call.token() != APPLICATION_TOKEN) return@get call.refuse("the application token is required")
            data.calls += "announcement"
            call.respondJson { put("text", data.announcement) }
        }
        // Lets you see the polling work. Change the announcement from a terminal and every open window
        // updates, even though the application was never told about the change.
        post("/hub/announcement") {
            if (call.token() != APPLICATION_TOKEN) return@post call.refuse("the application token is required")
            data.announcement = call.receiveText().trim()
            call.respondText("", status = HttpStatusCode.NoContent)
        }
        get("/hub/me") {
            val who = call.token() ?: return@get call.refuse("a user token is required")
            data.calls += "me:$who"
            call.respondJson {
                put("status", data.statusOf(who))
                put("updates", data.updatesBy(who))
            }
        }
        post("/hub/me/status") {
            val who = call.token() ?: return@post call.refuse("a user token is required")
            val text = call.receiveText().trim()
            if (text.length > STATUS_LIMIT) {
                // A real failure path: the page has to be able to show a refusal that the application
                // had no way to anticipate.
                call.respondText(
                    "a status has to fit in $STATUS_LIMIT characters",
                    status = HttpStatusCode.UnprocessableEntity,
                )
            } else {
                data.setStatus(who, text)
                call.respondText("", status = HttpStatusCode.NoContent)
            }
        }
    }
}

/** The stub's state between requests. Each test can create its own instance to start from a known state. */
class HubData(var announcement: String = "Deploy freeze on Friday") {
    private val statuses = ConcurrentHashMap<String, String>()
    private val updates = ConcurrentHashMap<String, Int>()

    /**
     * A log of the requests each endpoint received, and from whom.
     *
     * The important caching properties (one announcement fetch for everyone, one profile fetch per
     * principal) are about requests that were *not* made. Only the server can observe that, so the
     * stub records every call for tests to check.
     */
    val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    fun statusOf(email: String): String = statuses[email] ?: "no status"

    fun updatesBy(email: String): Int = updates[email] ?: 0

    fun setStatus(email: String, text: String) {
        statuses[email] = text
        updates.merge(email, 1, Int::plus)
    }
}

const val STATUS_LIMIT: Int = 40

private fun io.ktor.client.request.HttpRequestBuilder.bearer(token: String) {
    header(HttpHeaders.Authorization, "Bearer $token")
}

/** Returns the response body, or throws [HubRefused] with the hub's error message. */
private suspend fun HttpResponse.orRefuse(): String {
    if (!status.isSuccess()) throw HubRefused(bodyAsText().ifBlank { status.description })
    return bodyAsText()
}

private fun io.ktor.server.application.ApplicationCall.token(): String? =
    request.headers[HttpHeaders.Authorization]?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotBlank() }

private suspend fun io.ktor.server.application.ApplicationCall.refuse(why: String) {
    respondText(why, status = HttpStatusCode.Unauthorized)
}

private suspend fun io.ktor.server.application.ApplicationCall.respondJson(
    build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
) {
    respondText(buildJsonObject(build).toString(), ContentType.Application.Json)
}
