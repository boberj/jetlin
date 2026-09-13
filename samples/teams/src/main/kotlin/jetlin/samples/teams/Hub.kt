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
 * An external system, and how an application reaches one.
 *
 * Everything above this line in the sample is stored: records in SQLite, resident, gated by a policy.
 * This is the other kind of data — someone else's, arriving over HTTP, late, and sometimes not at all —
 * and the point of the file is that it needs almost nothing from the framework. A [Fetch] is snapshot
 * state with a coroutine behind it, so an arrival recomposes whoever read it, in every session, by the
 * same mechanism a committed transaction does.
 *
 * ## Why there is no policy here
 *
 * A stored record is reached through the gate, because one identity map holds one object per row and
 * everybody shares it. Nothing of the sort happens here: a profile is fetched with the credential of the
 * principal who asked for it, and the cache is keyed by that principal, so there is no object for the
 * wrong principal to reach. The question a policy would answer is answered by construction instead —
 * which is the honest reason this sample has one authorization model and not two.
 *
 * That argument holds exactly as far as the key does. A cache shared across principals — one object for
 * `acme/private` fetched with whoever asked first — would put Alice's authority in Bob's hands, and it
 * would need the gate and an authority on the key. That is the design in §11 of the plan, and it is not
 * built, because nothing here needs it yet.
 */
class Hub(
    private val baseUrl: String,
    private val client: HttpClient = HttpClient(CIO),
    /**
     * Where fetches run: never a session's dispatcher.
     *
     * A session composes on one confined thread, and a request on it would stall everything that session
     * is doing. This scope also outlives any one session, which is what lets two of them share a fetch.
     */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    /**
     * The one thing everybody sees the same way, fetched with the application's own credential.
     *
     * One [Fetch] for the whole process, so a hundred sessions cost one request a minute — the shape
     * §11.5 calls the application-token case, and the only one where sharing a cache is sound.
     */
    val announcement: Fetch<String> = Fetch(scope, ttl = 1.minutes) {
        val body = client.get("$baseUrl/hub/announcement") { bearer(APPLICATION_TOKEN) }.orRefuse()
        Json.parseToJsonElement(body).jsonObject.getValue("text").jsonPrimitive.content
    }

    private val profiles = ConcurrentHashMap<String, Fetch<Profile>>()

    /**
     * This principal's profile, fetched with this principal's credential.
     *
     * Keyed by the principal, which is the whole of the access control: Bob's read creates Bob's [Fetch]
     * with Bob's token, and Alice's copy is not something he can name.
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
     * Sets this principal's status, and marks their profile as needing a fresh copy.
     *
     * A command rather than an assignment, and suspending, which is what keeps it out of `db.transact { }`:
     * a transaction takes a non-suspending block precisely because a rollback cannot un-send a request.
     *
     * `refresh` rather than `invalidate`, because somebody demonstrably is looking: they pressed the
     * button. Marking it stale would work here too — the action's own state change recomposes the page,
     * which re-reads — but that is a coincidence of this page's markup rather than a property, and a
     * command should not depend on one.
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

/** What the hub knows about someone. One request fills all of it, so it arrives as one object. */
data class Profile(val status: String, val updates: Int)

/** What the hub says when it will not do something. Shown on the page; never thrown at the session. */
class HubRefused(message: String) : RuntimeException(message)

/** The credential the application uses as itself, for the things that are the same for everyone. */
const val APPLICATION_TOKEN: String = "teams-application"

/**
 * The external system, in process.
 *
 * A stub, and it says so: it is a fixture with a real HTTP boundary rather than a third-party API, which
 * is the only kind of external system a test can depend on. It is mounted on the same Ktor instance that
 * serves the sample, so there is one port and no network.
 *
 * Its "authentication" is a bearer token that is the user's email address, which is exactly as much
 * authentication as this sample's cookie — and for the same reason: the interesting half is what happens
 * to the data, not how someone proved who they are.
 */
fun Application.hubService(data: HubData = HubData()) {
    routing {
        get("/hub/announcement") {
            if (call.token() != APPLICATION_TOKEN) return@get call.refuse("the application token is required")
            data.calls += "announcement"
            call.respondJson { put("text", data.announcement) }
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
                // The failure path, and a real one: a page has to be able to show a refusal that the
                // application could not have predicted.
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

/** What the stub remembers between requests. Per instance, so a test starts from a known state. */
class HubData(var announcement: String = "Deploy freeze on Friday") {
    private val statuses = ConcurrentHashMap<String, String>()
    private val updates = ConcurrentHashMap<String, Int>()

    /**
     * How many times each endpoint has been asked, and by whom.
     *
     * A fixture that can be asked what it was asked. Worth having because the interesting claims about
     * caching — one announcement for everybody, one profile per principal — are claims about requests
     * that did *not* happen, and nothing else can see those.
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

/** The body, or the hub's own words about why not. */
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
