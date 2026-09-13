package jetlin.samples.teams

import jetlin.db.authenticate
import jetlin.db.insertUnchecked
import jetlin.db.unsafe
import kotlin.io.path.createTempDirectory

/**
 * Measures retained heap per resident record, over a table big enough for the figure to mean something.
 *
 * Residency is what makes every read cheap and every traversal a pointer dereference, and the bill for it
 * is memory — shared with the compositions of everyone connected. This measures the graph half of that
 * bill. The session half belongs to `samples/demo:benchmark`, which has the harness for it and reports a
 * graph figure of its own, so the two can be read together.
 *
 * Run with: ./gradlew :samples:teams:benchmark
 */
fun main() {
    val recordCount = System.getenv("RECORDS")?.toInt() ?: 20_000

    val directory = createTempDirectory("jetlin-teams-benchmark")
    val db = openSeeded(directory.resolve("benchmark.db"))
    val alice = checkNotNull(db.authenticate(User::class) { it.email == "alice@example.com" })

    // Warm up: class loading, the JIT and SQLite's own structures are not what is being measured.
    unsafe("benchmark warm-up") {
        db.transact { repeat(200) { db.insertUnchecked(Todo(alice, "warm up $it")) } }
    }

    val before = usedHeap()
    unsafe("benchmark records") {
        // One transaction for all of them, so the figure is the cost of the objects rather than of
        // SQLite's per-commit residue. An application inserts one record per user action; what is wanted here
        // is what residency costs, not what a commit costs.
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

    // Keep the graph reachable until after the measurement, or this measures garbage collection instead.
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
