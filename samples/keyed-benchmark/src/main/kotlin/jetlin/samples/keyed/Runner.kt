package jetlin.samples.keyed

import java.util.Locale
import jetlin.protocol.Op
import kotlinx.coroutines.runBlocking

/**
 * Runs the keyed js-framework-benchmark operations against Jetlin and prints what they cost.
 *
 * Run with: `./gradlew :samples:keyed-benchmark:keyedBenchmark`
 *
 * ## What this measures, and what it cannot
 *
 * The published benchmark times a click through to the browser having painted. Jetlin only owns
 * the first half of that: the event arrives, the composition recomposes, the applier writes down
 * what changed, and the patch is serialized and handed to a socket. The browser's share — parsing
 * the frame and applying the edits — happens in code that is not on this JVM, and no honest number
 * for it can be produced from here. So these are *not* comparable to the numbers on
 * krausest.github.io, and reading them as though a framework had been beaten would be wrong.
 *
 * What they are comparable to is each other, and that is the useful part. Every operation below is
 * driven exactly as the reference harness drives it, with the same preconditions and the same
 * warmups, so the shape of the results says what work each operation causes a server-side
 * framework to do. Alongside the time, each row reports the two quantities a client-side framework
 * has no equivalent of and which decide what the interaction costs a user on a slow link: how many
 * DOM operations crossed the wire, and how many bytes they took.
 *
 * The op count is where "keyed" is visible. Swapping two rows of a thousand emits two moves, not
 * two thousand text writes; updating every tenth row emits a hundred; selecting one emits two. A
 * non-keyed implementation, or a keyed one whose key changes with its data, produces the same page
 * and a patch proportional to the size of the table.
 */
fun main(): Unit = runBlocking {
    val iterations = System.getenv("ITERATIONS")?.toInt() ?: 10
    val warmupPasses = System.getenv("WARMUP_PASSES")?.toInt() ?: 5
    // Which sections to run, for when only one of them is the question. The memory benchmarks and
    // the scaling sweep together take longer than everything else, and neither is affected by a
    // change to how the CPU section is warmed up.
    val sections = System.getenv("SECTIONS")?.split(",")?.map { it.trim() } ?: listOf("all")
    fun runs(section: String) = "all" in sections || section in sections

    println(header(iterations, warmupPasses))

    if (runs("cpu")) {
        // The prescribed warmups exercise the operation but not the JVM: the first pass through this
        // suite is still interpreting bytecode in places. Discarding whole passes is the JVM's
        // version of the browser's page reload, and without it the first benchmark in the list wears
        // the cost of compiling code that every later one gets for free.
        repeat(warmupPasses) { pass ->
            print("warming up (pass ${pass + 1} of $warmupPasses)".padEnd(60) + "\r")
            CPU_BENCHMARKS.forEach { it.sample(iterations = 1) }
        }

        val cpu = CPU_BENCHMARKS.map { benchmark ->
            print("running ${benchmark.id}".padEnd(60) + "\r")
            benchmark to benchmark.sample(iterations)
        }
        print("".padEnd(60) + "\r")
        println(cpuTable(cpu))
    }

    if (runs("memory")) {
        val memory = MEMORY_BENCHMARKS.map { benchmark ->
            print("running ${benchmark.id}".padEnd(60) + "\r")
            benchmark to benchmark.sample()
        }
        print("".padEnd(60) + "\r")
        println(memoryTable(memory))
    }

    if (runs("paint")) {
        println(FIRST_PAINT_HEADING)
        println(firstPaintTable(measureFirstPaint()))
    }

    if (runs("scaling")) {
        print("running the scaling sweep".padEnd(60) + "\r")
        // The memory benchmarks leave several gigabytes of garbage behind them, and a sweep that
        // spends its first measurements paying for that would report a curve with a step in it.
        usedHeap()
        val scaling = measureScaling(iterations = maxOf(3, iterations / 3))
        print("".padEnd(60) + "\r")
        println(scalingTable(scaling))
    }
}

// ---------------------------------------------------------------------------------------------
// The nine CPU benchmarks, with the reference harness's init/run split preserved exactly.
// ---------------------------------------------------------------------------------------------

/**
 * One operation, as `benchmarksPuppeteer.ts` defines it.
 *
 * [prepare] puts the page into the state the operation is measured from and is not timed;
 * [operation] is the single click that is. Splitting them this way is not a detail: "clear rows" measured from an
 * empty table would measure nothing, and "create rows" measured without the preceding clears would
 * be measuring a replace.
 */
private class CpuBenchmark(
    val id: String,
    val label: String,
    val description: String,
    val warmup: Int,
    val prepare: suspend Driver.(warmup: Int) -> Unit,
    val operation: suspend Driver.() -> Patch,
) {
    /**
     * Takes [iterations] samples, each on a session that has never been used before.
     *
     * A fresh session per iteration mirrors the harness reloading the page, and it matters more
     * here than it does there: a composition that has already built and torn down ten thousand rows
     * has a slot table sized for them, and reusing it would quietly measure the second run of an
     * operation while calling it the first.
     */
    suspend fun sample(iterations: Int): Sample {
        val timings = ArrayList<Long>(iterations)
        var last: Patch? = null
        repeat(iterations) {
            Driver.open().use { driver ->
                driver.prepare(warmup)
                val patch = driver.operation()
                timings += patch.totalNanos
                last = patch
            }
        }
        return Sample(timings.sorted(), checkNotNull(last))
    }
}

private val CPU_BENCHMARKS = listOf(
    CpuBenchmark(
        id = "01_run1k",
        label = "create rows",
        description = "creating 1,000 rows",
        warmup = 5,
        prepare = { warmup -> repeat(warmup) { click("run"); click("clear") } },
        operation = { click("run") },
    ),
    CpuBenchmark(
        id = "02_replace1k",
        label = "replace all rows",
        description = "updating all 1,000 rows",
        warmup = 5,
        prepare = { warmup -> repeat(warmup) { click("run") } },
        operation = { click("run") },
    ),
    CpuBenchmark(
        id = "03_update10th1k",
        label = "partial update",
        description = "updating every 10th row for 1,000 rows",
        warmup = 3,
        prepare = { warmup ->
            click("run")
            repeat(warmup) { click("update") }
        },
        operation = { click("update") },
    ),
    CpuBenchmark(
        id = "04_select1k",
        label = "select row",
        description = "highlighting a selected row",
        warmup = 5,
        prepare = {
            click("run")
            // nth-of-type(5), which is index 4. The harness selects one row before measuring, so
            // what is timed is moving a highlight rather than placing the first one.
            selectRow(4)
        },
        operation = { selectRow(1) },
    ),
    CpuBenchmark(
        id = "05_swap1k",
        label = "swap rows",
        description = "swap 2 rows for a table with 1,000 rows",
        warmup = 5,
        prepare = { warmup ->
            click("run")
            // One more than the warmup count, which is what the harness does: an odd number of
            // swaps leaves the two rows exchanged, and the measured swap puts them back.
            repeat(warmup + 1) { click("swaprows") }
        },
        operation = { click("swaprows") },
    ),
    CpuBenchmark(
        id = "06_remove-one-1k",
        label = "remove row",
        description = "removing one row",
        warmup = 5,
        prepare = { warmup ->
            click("run")
            // The harness deletes rows 9, 8, 7, 6 and 5 (nth-of-type), then row 6 again, so that the
            // measured deletion happens in a table that has already been edited.
            for (i in 0 until warmup) removeRow(warmup - i + ROWS_TO_SKIP - 1)
            removeRow(ROWS_TO_SKIP + 1)
        },
        operation = { removeRow(ROWS_TO_SKIP - 1) },
    ),
    CpuBenchmark(
        id = "07_create10k",
        label = "create many rows",
        description = "creating 10,000 rows",
        warmup = 5,
        prepare = { warmup -> repeat(warmup) { click("run"); click("clear") } },
        operation = { click("runlots") },
    ),
    CpuBenchmark(
        id = "08_create1k-after1k",
        label = "append rows to large table",
        description = "appending 1,000 to a table of 1,000 rows",
        warmup = 5,
        prepare = { warmup ->
            repeat(warmup) { click("run"); click("clear") }
            click("run")
        },
        operation = { click("add") },
    ),
    CpuBenchmark(
        id = "09_clear1k",
        label = "clear rows",
        description = "clearing a table with 1,000 rows",
        warmup = 5,
        prepare = { warmup ->
            repeat(warmup) { click("run"); click("clear") }
            click("run")
        },
        operation = { click("clear") },
    ),
)

/**
 * The row the "remove row" benchmark leaves alone at the top of the table.
 *
 * The harness's `rowsToSkip`, as a 1-based `nth-of-type`. Deletions during setup happen below it
 * and the measured one lands on it, so the row being removed is never the first or the last.
 */
private const val ROWS_TO_SKIP = 4

/** The timings for one operation, sorted, and the patch the last of them produced. */
private class Sample(private val sortedNanos: List<Long>, val patch: Patch) {
    val min: Double get() = sortedNanos.first() / 1_000_000.0
    val median: Double get() = sortedNanos[sortedNanos.size / 2] / 1_000_000.0
    val mean: Double get() = sortedNanos.average() / 1_000_000.0
    val composeShare: Double get() = patch.composeNanos.toDouble() / patch.totalNanos
}

// ---------------------------------------------------------------------------------------------
// The memory benchmarks.
// ---------------------------------------------------------------------------------------------

/**
 * Retained heap for a session sitting in a given state.
 *
 * The published benchmark reads the browser's heap after the operation. The equivalent question for
 * a server-side framework is what one connected user costs, which is the number that decides how
 * many of them fit on a node — so that is what this reports, per session rather than per page.
 *
 * Measured across [sessions] of them because a single composition's footprint is not much larger
 * than the noise in a JVM heap reading. More sessions is a steadier number; a table of ten thousand
 * rows needs few of them to be well clear of the noise, and holding twenty would need more heap
 * than this is worth.
 */
private class MemoryBenchmark(
    val id: String,
    val label: String,
    val description: String,
    val sessions: Int,
    val prepare: suspend Driver.() -> Unit,
) {
    suspend fun sample(): MemorySample {
        // One throwaway session first, so class loading is not billed to the measurement.
        Driver.open().use { warm -> warm.prepare() }

        val before = usedHeap()
        val drivers = ArrayList<Driver>(sessions)
        repeat(sessions) { drivers += Driver.open().also { driver -> driver.prepare() } }
        val after = usedHeap()

        val rows = drivers.first().rowCount()
        // Closing after the reading, not before: the sessions have to still be reachable while the
        // heap is measured, or this reports the cost of a composition that has already gone.
        drivers.forEach { it.close() }
        return MemorySample(bytesPerSession = (after - before) / sessions, rows = rows)
    }
}

private val MEMORY_BENCHMARKS = listOf(
    MemoryBenchmark(
        id = "21_ready-memory",
        label = "ready memory",
        description = "a session with the page composed and no rows",
        sessions = 200,
        prepare = { },
    ),
    MemoryBenchmark(
        id = "22_run-memory",
        label = "run memory",
        description = "after adding 1,000 rows",
        sessions = 10,
        prepare = { click("run") },
    ),
    MemoryBenchmark(
        id = "23_update5-memory",
        label = "update memory",
        description = "after clicking update 5 times on 1,000 rows",
        sessions = 10,
        prepare = {
            click("run")
            repeat(5) { click("update") }
        },
    ),
    MemoryBenchmark(
        id = "25_run-clear-memory",
        label = "replace memory",
        description = "after creating and clearing 1,000 rows 5 times",
        sessions = 10,
        prepare = { repeat(5) { click("run"); click("clear") } },
    ),
    MemoryBenchmark(
        id = "26_run10k-memory",
        label = "run memory (10k)",
        description = "after adding 10,000 rows",
        sessions = 2,
        prepare = { click("runlots") },
    ),
)

private class MemorySample(val bytesPerSession: Long, val rows: Int)

private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    repeat(4) {
        System.gc()
        Thread.sleep(120)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}

// ---------------------------------------------------------------------------------------------
// First paint, which is the closest thing here to the published startup metrics.
// ---------------------------------------------------------------------------------------------

/**
 * How long the server takes to produce the first page, and how big it is.
 *
 * The published startup metrics — script bootup, main thread work, total byte weight — are about
 * the JavaScript a framework ships. Jetlin ships one 8.6 kB runtime and no application code at all,
 * so those numbers are a constant and measuring them per benchmark would say nothing. What varies,
 * and what a user waits for, is the HTML: this is the time to render it and its size, for an empty
 * table and for one already holding a thousand rows.
 */
private suspend fun measureFirstPaint(): List<Triple<String, Long, Int>> {
    val cases = listOf<Pair<String, suspend Driver.() -> Unit>>(
        "empty table" to { },
        "1,000 rows" to { click("run") },
    )
    return cases.map { (name, prepare) ->
        // Warm, then measure, for the same reason the CPU benchmarks discard passes.
        repeat(3) { Driver.open().use { warm -> warm.prepare(); warm.html() } }
        Driver.open().use { driver ->
            driver.prepare()
            val start = System.nanoTime()
            val html = driver.html()
            val elapsed = System.nanoTime() - start
            Triple(name, elapsed, html.toByteArray(Charsets.UTF_8).size)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Reporting.
// ---------------------------------------------------------------------------------------------

private fun header(iterations: Int, warmupPasses: Int): String = """
    |Jetlin — js-framework-benchmark (keyed), server side
    |${"=".repeat(78)}
    |
    |Each operation is driven as the reference harness drives it: the same preconditions, the same
    |warmup clicks, one timed click, on a session that has never been used before. Time is the
    |server's whole share of the interaction — dispatch, recomposition, applier, serialization —
    |and stops where the socket would be. The browser's share is not measured and is not included,
    |so these numbers do not compare with the published ones.
    |
    |iterations per benchmark: $iterations (min / median / mean reported)
    |discarded warmup passes:  $warmupPasses
    |jvm:                      ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")}
    |cpus:                     ${Runtime.getRuntime().availableProcessors()}
""".trimMargin()

private fun cpuTable(results: List<Pair<CpuBenchmark, Sample>>): String = buildString {
    append("\nCPU — server time per operation\n")
    val columns = listOf("benchmark", "min ms", "median ms", "mean ms", "ops", "op mix", "wire")
    val rows = results.map { (benchmark, sample) ->
        listOf(
            benchmark.label,
            decimals(sample.min),
            decimals(sample.median),
            decimals(sample.mean),
            sample.patch.ops.size.toString(),
            sample.patch.ops.mix(),
            bytes(sample.patch.bytes),
        )
    }
    append(table(columns, rows))
    append("\nof which recomposition, the rest being serialization:\n")
    results.forEach { (benchmark, sample) ->
        append("  ${benchmark.label.padEnd(28)} ${(sample.composeShare * 100).toInt()}%\n")
    }
}

private fun memoryTable(results: List<Pair<MemoryBenchmark, MemorySample>>): String = buildString {
    append("\nMemory — retained heap per live session\n")
    val rows = results.map { (benchmark, sample) ->
        listOf(
            benchmark.label,
            benchmark.description,
            sample.rows.toString(),
            bytes(sample.bytesPerSession.toInt()),
        )
    }
    append(table(listOf("benchmark", "state", "rows", "per session"), rows))
}

private const val FIRST_PAINT_HEADING = "\nFirst paint — server-rendered HTML"

private fun firstPaintTable(results: List<Triple<String, Long, Int>>): String = table(
    listOf("page", "render ms", "html"),
    results.map { (name, nanos, size) ->
        listOf(name, decimals(nanos / 1_000_000.0), bytes(size))
    },
)

/** A compact census of an op list, e.g. `1000 ins`, or `2 mv`. */
private fun List<Op>.mix(): String = groupingBy { op ->
    when (op) {
        is Op.Insert -> "ins"
        is Op.Remove -> "rm"
        is Op.Move -> "mv"
        is Op.SetAttr -> "attr"
        is Op.SetProp -> "prop"
        is Op.SetText -> "text"
        is Op.Listen -> "on"
        is Op.Unlisten -> "off"
    }
}.eachCount().entries.joinToString(" + ") { "${it.value} ${it.key}" }.ifEmpty { "none" }

/** Two decimal places, in a locale that will not put a comma in the middle of a number. */
private fun decimals(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

private fun bytes(count: Int): String = when {
    count < 1024 -> "$count B"
    count < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f kB", count / 1024.0)
    else -> String.format(Locale.ROOT, "%.1f MB", count / 1024.0 / 1024.0)
}

private fun table(columns: List<String>, rows: List<List<String>>): String {
    val widths = columns.indices.map { column ->
        maxOf(columns[column].length, rows.maxOfOrNull { it[column].length } ?: 0)
    }
    fun line(cells: List<String>) =
        cells.mapIndexed { index, cell -> cell.padEnd(widths[index]) }.joinToString("  ").trimEnd()
    return buildString {
        append(line(columns)).append('\n')
        append(widths.joinToString("  ") { "-".repeat(it) }).append('\n')
        rows.forEach { append(line(it)).append('\n') }
    }
}

// ---------------------------------------------------------------------------------------------
// How the operations scale, which the fixed row counts hide.
// ---------------------------------------------------------------------------------------------

/**
 * The four kinds of change, at five table sizes.
 *
 * Not part of the published benchmark, and here because without it the table above is misleading.
 * The nine operations are all defined at 1,000 rows or 10,000, so a reader has no way to tell a
 * large constant from a curve — and the difference between the two decides whether a number is a
 * fact about this machine or a fact about the framework.
 *
 * The interesting split is between changing what a row *says* and changing which rows there *are*.
 * The first is per-row state and should not care how long the table is; the second goes through
 * Compose's reconciliation of the keyed children, and does.
 */
private suspend fun measureScaling(iterations: Int): List<ScalingRow> {
    val sizes = listOf(250, 500, 1_000, 2_000, 4_000)
    return sizes.map { size ->
        // Building the table is measured on sessions that have never held one, for the same reason
        // the nine benchmarks open a new session per iteration.
        val create = repeated(iterations) {
            Driver.open().use { fresh -> fresh.mutate { run(size) }.totalNanos }
        }
        Driver.open().use { driver ->
            driver.mutate { run(size) }
            // A couple of unmeasured repetitions. Fewer than the harness's warmups on purpose: by
            // the time the sweep runs the JVM has been at this for minutes, and at four thousand
            // rows each extra swap is another thirteen seconds of nothing new.
            repeat(2) { driver.mutate { swap(1, size - 2) } }
            val swap = repeated(iterations) { driver.mutate { swap(1, size - 2) }.totalNanos }
            val update = repeated(iterations) { driver.mutate { update() }.totalNanos }
            val remove = repeated(iterations) { driver.mutate { remove(rows[3]) }.totalNanos }
            ScalingRow(size, create, swap, remove, update)
        }
    }
}

private suspend fun repeated(iterations: Int, block: suspend () -> Long): Double =
    (0 until iterations).map { block() }.sorted()[iterations / 2] / 1_000_000.0

private class ScalingRow(
    val rows: Int,
    val create: Double,
    val swap: Double,
    val remove: Double,
    val update: Double,
)

private fun scalingTable(results: List<ScalingRow>): String = buildString {
    append("\nScaling — median ms, by table size (not part of the published benchmark)\n")
    append(
        table(
            listOf("rows", "create", "swap 2", "remove 1", "update every 10th"),
            results.map {
                listOf(it.rows.toString(), decimals(it.create), decimals(it.swap), decimals(it.remove), decimals(it.update))
            },
        ),
    )
}
