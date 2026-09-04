package jetlin.samples.keyed

import kotlinx.coroutines.runBlocking

/**
 * Repeats one operation for long enough that a sampling profiler can see where it goes.
 *
 * Not a measurement — nothing here is timed, and the loop deliberately keeps the table near a
 * thousand rows rather than measuring any one removal. It exists because "the cost is in Compose's
 * reconciliation" is a conclusion drawn from black-box timings, and the next question after it is
 * which frames, which only a profiler can answer.
 */
fun main(): Unit = runBlocking {
    val op = System.getenv("OP") ?: "remove"
    val seconds = System.getenv("SECONDS")?.toLong() ?: 30
    val size = System.getenv("ROWS")?.toInt() ?: ROWS
    val chunk = System.getenv("CHUNK")?.toInt() ?: FLAT

    Driver.open(chunk = chunk).use { driver ->
        driver.mutate { run(size) }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var iterations = 0
        while (System.nanoTime() < deadline) {
            when (op) {
                // Down to half the table and then back, so most of the time is spent removing from
                // a list of roughly a thousand rather than from a list that has dwindled to nothing.
                "remove" -> {
                    driver.mutate { remove(rows[3]) }
                    if (driver.rowCount() <= size / 2) driver.mutate { run(size) }
                }
                "swap" -> driver.mutate { swap(1, size - 2) }
                // Cleared in its own transaction first, so the create that follows starts from an
                // empty table as the benchmark's does. Calling run() on a populated table would be a
                // replace, and would put ten thousand removals into the samples.
                "create" -> {
                    driver.mutate { clear() }
                    driver.mutate { run(size) }
                }
                else -> error("Unknown OP '$op'")
            }
            iterations++
        }
        println("$op at $size rows, chunk $chunk: $iterations iterations in ${seconds}s")
    }
}
