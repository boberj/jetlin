package jetlin.samples.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H2
import jetlin.html.Li
import jetlin.html.LiveView
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.Ul
import jetlin.runtime.rememberSaved
import kotlinx.coroutines.runBlocking

/**
 * Measures the retained heap of each live session, and of each hibernated one.
 *
 * With UI state on the server, memory grows with the number of connected users, so this number sets
 * the practical limit on how many sessions a node can hold. The benchmark creates many sessions,
 * keeps them all reachable, and compares heap usage before and after.
 *
 * Set the environment variable `SESSIONS` to change the number of sessions, which defaults to 1,000.
 * Set `PAGE=real` to measure the application's todo list page instead of the synthetic page below.
 * The two results show that a session's cost depends mostly on the size of its page: the synthetic
 * page has 113 nodes and costs about 130 kB, and the todo list has 42 nodes and costs about 65 kB.
 * That's roughly 1.5 kB per node in both cases.
 *
 * To run it, use `./gradlew :samples:demo:benchmark`.
 */
fun main() = runBlocking {
    val sessionCount = System.getenv("SESSIONS")?.toInt() ?: 1000
    val real = System.getenv("PAGE") == "real"
    val page: @Composable () -> Unit = if (real) ({ TodoListPage() }) else ({ BenchmarkView() })

    // Warm up the runtime, so class loading and JIT compilation don't count as session cost.
    repeat(20) { LiveView(content = { _ -> page() }).also { it.start() }.close() }

    val before = usedHeap()
    val views = ArrayList<LiveView>(sessionCount)
    repeat(sessionCount) {
        val view = LiveView(content = { _ -> page() })
        view.start()
        views += view
    }
    val after = usedHeap()

    val perSession = (after - before) / sessionCount
    val nodes = countNodes(views.first().owner.snapshotChildren())

    // Now hibernate every session and measure what an idle session costs. This is the whole case
    // for hibernation: a session that nobody is looking at shouldn't cost what a live one costs.
    val snapshots = views.map { it.hibernate() }
    // Dropping the references is part of hibernation. In production, the registry forgets the
    // session, and only the snapshot stays reachable. Measuring with the closed views still held
    // would report the cost of the compositions that were just destroyed.
    views.clear()
    val hibernated = usedHeap()
    val perHibernated = (hibernated - before) / sessionCount

    println("page:               ${if (real) "the application's todo list" else "synthetic"}")
    println("sessions:           $sessionCount")
    println("nodes per session:  $nodes")
    println("heap before:        ${before / 1024 / 1024} MB")
    println("live:               ${perSession / 1024} kB per session (${(after - before) / 1024 / 1024} MB total)")
    println("hibernated:         $perHibernated bytes per session (${(hibernated - before) / 1024} kB total)")
    println("ratio:              ${perSession / perHibernated.coerceAtLeast(1)}x cheaper idle")
    println("saved keys:         ${snapshots.first().keys}")

    // Keep the snapshots reachable until after the measurement.
    check(snapshots.size == sessionCount)
}

/** Returns the number of nodes in [specs] and their subtrees. */
private fun countNodes(specs: List<jetlin.protocol.NodeSpec>): Int = specs.sumOf { spec ->
    1 + when (spec) {
        is jetlin.protocol.NodeSpec.Element -> countNodes(spec.children)
        is jetlin.protocol.NodeSpec.Text -> 0
    }
}

/** Returns the heap in use after several rounds of garbage collection. */
private fun usedHeap(): Long {
    val runtime = Runtime.getRuntime()
    repeat(4) {
        System.gc()
        Thread.sleep(120)
    }
    return runtime.totalMemory() - runtime.freeMemory()
}

/** A deliberately unremarkable page: a header, some state, and a 20-row list. */
@Composable
private fun BenchmarkView() {
    // Save one value, as a realistic page would. Everything else can be recomputed.
    val draft = rememberSaved(key = "draft") { "a half-typed line of user input" }
    var count by remember { mutableStateOf(0) }
    val rows = remember { mutableStateListOf(*Array(20) { "Row $it" }) }

    Div({ classes("page") }) {
        H2 { Text("Session") }
        Div({ classes("row") }) {
            Span { Text(draft.value) }
            Button({ onClick { count-- } }) { Text("−") }
            Span { Text("$count") }
            Button({ onClick { count++ } }) { Text("+") }
        }
        Ul {
            rows.forEach { row ->
                key(row) {
                    Li {
                        Span({ classes("todo-text") }) { Text(row) }
                        Button({ classes("link"); onClick { rows.remove(row) } }) { Text("remove") }
                    }
                }
            }
        }
    }
}
