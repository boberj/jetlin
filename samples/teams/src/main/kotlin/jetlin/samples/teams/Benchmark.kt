package jetlin.samples.teams

import jetlin.db.authenticate
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import kotlin.io.path.createTempDirectory

/**
 * Measures the retained heap per record held in memory, using a table large enough to give a stable
 * figure.
 *
 * Keeping every record in memory makes reads cheap and relations a field access, but it costs memory,
 * which is shared with the compositions of all connected users. This benchmark measures the records'
 * share. The per-session share is measured by `samples/demo:benchmark`.
 *
 * Run with: ./gradlew :samples:teams:benchmark
 */
fun main() {
    val recordCount = System.getenv("RECORDS")?.toInt() ?: 20_000

    val directory = createTempDirectory("jetlin-teams-benchmark")
    val db = openSeeded(directory.resolve("benchmark.db"))
    val alice = checkNotNull(db.authenticate(User::class) { it.email == "alice@example.com" })

    // Warm up first, so class loading, JIT compilation and SQLite's internal structures aren't counted.
    unsafe("benchmark warm-up") {
        db.transact { repeat(200) { db.insertUnchecked(Todo(alice, "warm up $it")) } }
    }

    val before = usedHeap()
    unsafe("benchmark records") {
        // Insert everything in one transaction, so the result measures the objects and not whatever
        // SQLite retains per commit. A real application commits once per user action, but the point here
        // is the cost of keeping records in memory, not the cost of commits.
        db.transact {
            val bulk = db.insertUnchecked(User("Bulk", "bulk@example.com"))
            repeat(recordCount) { index ->
                db.insertUnchecked(Todo(bulk, "Record $index of a benchmark, with a title of ordinary length"))
            }
        }
    }
    val after = usedHeap()

    println("records:            $recordCount resident")
    println(
        "graph:              ${(after - before) / recordCount} bytes per record " +
            "(${(after - before) / 1024 / 1024} MB total)",
    )
    println()
    println("Should barely move as RECORDS changes. If it does, something is being counted that is not")
    println("the records — SQLite's cache, or garbage the measurement itself made.")

    // Keeps the graph reachable until after the measurement; otherwise it could be collected first.
    check(db.resident.recordCount > recordCount)
    db.close()
}

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    repeat(4) {
        System.gc()
        Thread.sleep(120)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}
