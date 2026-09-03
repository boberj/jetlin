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

    Driver.open().use { driver ->
        driver.mutate { run(ROWS) }
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        var iterations = 0
        while (System.nanoTime() < deadline) {
            when (op) {
                // Down to half the table and then back, so most of the time is spent removing from
                // a list of roughly a thousand rather than from a list that has dwindled to nothing.
                "remove" -> {
                    driver.mutate { remove(rows[3]) }
                    if (driver.rowCount() <= ROWS / 2) driver.mutate { run(ROWS) }
                }
                "swap" -> driver.mutate { swap(1, ROWS - 2) }
                "create" -> driver.mutate { run(ROWS) }
                else -> error("Unknown OP '$op'")
            }
            iterations++
        }
        println("$op: $iterations iterations in ${seconds}s")
    }
}
