package jetlin.samples.teams

import jetlin.db.Db
import jetlin.db.authenticate
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
