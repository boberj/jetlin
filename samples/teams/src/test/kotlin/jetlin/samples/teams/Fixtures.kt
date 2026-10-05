package jetlin.samples.teams

import jetlin.db.Db
import jetlin.db.authenticate
import jetlin.server.auth.SessionControls
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/** Runs [block] against a newly seeded database, so tests don't share state. */
@OptIn(ExperimentalPathApi::class)
internal fun withSample(block: (Db) -> Unit) {
    val directory = createTempDirectory("jetlin-teams-test")
    try {
        openSeeded(directory.resolve("teams.db")).use(block)
    } finally {
        directory.deleteRecursively()
    }
}

/** Returns the seeded user with [email], without a policy check. Tests use it to pick a principal. */
internal fun Db.user(email: String): User =
    checkNotNull(authenticate(User::class) { it.email == email }) { "no seeded user $email" }

/**
 * Records sign-ins and sign-outs instead of performing them.
 *
 * Signing in needs a real page load and a cookie, which a headless view test has neither of. What
 * the application decides is who to sign in and where to go next, and that's what this records.
 */
internal class RecordingControls : SessionControls<TeamsSession> {
    /** Every call, in order, as `signIn(<email>, <next>)` or `signOut(<next>)`. */
    val calls: MutableList<String> = mutableListOf()

    override fun signIn(session: TeamsSession, next: String) {
        calls += "signIn(${session.email}, $next)"
    }

    override fun signOut(next: String) {
        calls += "signOut($next)"
    }
}
