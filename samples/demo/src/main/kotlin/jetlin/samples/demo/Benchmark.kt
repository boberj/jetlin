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
 * Measures retained heap per live session.
 *
 * Holding UI state on the server means memory scales with the number of connected users, so this
 * number sets the practical ceiling on how many sessions a node can carry. It works by creating
 * many sessions, keeping them all reachable, and comparing heap usage before and after.
 *
 * Run with: ./gradlew :samples:demo:benchmark
 */
fun main() = runBlocking {
    val sessionCount = System.getenv("SESSIONS")?.toInt() ?: 1000

    // Warm up the runtime so class loading and JIT are not counted as session cost.
    repeat(20) { LiveView(content = { _ -> BenchmarkView() }).also { it.start() }.close() }

    val before = usedHeap()
    val views = ArrayList<LiveView>(sessionCount)
    repeat(sessionCount) {
        val view = LiveView(content = { _ -> BenchmarkView() })
        view.start()
        views += view
    }
    val after = usedHeap()

    val perSession = (after - before) / sessionCount
    val nodes = countNodes(views.first().owner.snapshotChildren())

    // Now hibernate every one of them and measure what an idle session actually costs. This is the
    // whole argument for hibernation: a session nobody is looking at should stop costing what a
    // live one costs.
    val snapshots = views.map { it.hibernate() }
    // Dropping the references is part of what hibernation is: in production the registry forgets
    // the session and only the snapshot stays reachable. Measuring with the closed views still held
    // would report the cost of the composition we just destroyed.
    views.clear()
    val hibernated = usedHeap()
    val perHibernated = (hibernated - before) / sessionCount

    println("sessions:           $sessionCount")
    println("nodes per session:  $nodes")
    println("heap before:        ${before / 1024 / 1024} MB")
    println("live:               ${perSession / 1024} kB per session (${(after - before) / 1024 / 1024} MB total)")
    println("hibernated:         $perHibernated bytes per session (${(hibernated - before) / 1024} kB total)")
    println("ratio:              ${perSession / perHibernated.coerceAtLeast(1)}x cheaper idle")
    println("saved keys:         ${snapshots.first().keys}")

    // Keep them reachable until after the measurement.
    check(snapshots.size == sessionCount)
}

private fun countNodes(specs: List<jetlin.protocol.NodeSpec>): Int = specs.sumOf { spec ->
    1 + when (spec) {
        is jetlin.protocol.NodeSpec.Element -> countNodes(spec.children)
        is jetlin.protocol.NodeSpec.Text -> 0
    }
}

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
    // One saved value, as a realistic page would have; the rest is recomputable.
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
