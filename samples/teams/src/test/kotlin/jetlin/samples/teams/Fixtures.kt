package jetlin.samples.teams

import jetlin.db.Db
import jetlin.db.authenticate
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively

/** A newly seeded database for each test, so tests don't share state. */
@OptIn(ExperimentalPathApi::class)
internal fun withSample(block: (Db) -> Unit) {
    val directory = createTempDirectory("jetlin-teams-test")
    try {
        openSeeded(directory.resolve("teams.db")).use(block)
    } finally {
        directory.deleteRecursively()
    }
}

internal fun Db.user(email: String): User =
    checkNotNull(authenticate(User::class) { it.email == email }) { "no seeded user $email" }
