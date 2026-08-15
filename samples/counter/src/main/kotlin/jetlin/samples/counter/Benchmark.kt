package jetlin.samples.counter

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
import kotlinx.coroutines.runBlocking

/**
 * Measures retained heap per live session.
 *
 * Holding UI state on the server means memory scales with the number of connected users, so this
 * number sets the practical ceiling on how many sessions a node can carry. It works by creating
 * many sessions, keeping them all reachable, and comparing heap usage before and after.
 *
 * Run with: ./gradlew :samples:counter:benchmark
 */
fun main() = runBlocking {
    val sessionCount = System.getenv("SESSIONS")?.toInt() ?: 1000

    // Warm up the runtime so class loading and JIT are not counted as session cost.
    repeat(20) { LiveView { BenchmarkView() }.also { it.start() }.close() }

    val before = usedHeap()
    val views = ArrayList<LiveView>(sessionCount)
    repeat(sessionCount) {
        val view = LiveView { BenchmarkView() }
        view.start()
        views += view
    }
    val after = usedHeap()

    val perSession = (after - before) / sessionCount
    println("sessions:           $sessionCount")
    println("nodes per session:  ${views.first().owner.snapshotChildren().let { countNodes(it) }}")
    println("heap before:        ${before / 1024 / 1024} MB")
    println("heap after:         ${after / 1024 / 1024} MB")
    println("per session:        ${perSession / 1024} kB ($perSession bytes)")

    // Keep the views reachable until after the measurement.
    check(views.size == sessionCount)
    views.forEach { it.close() }
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
    var count by remember { mutableStateOf(0) }
    val rows = remember { mutableStateListOf(*Array(20) { "Row $it" }) }

    Div({ classes("page") }) {
        H2 { Text("Session") }
        Div({ classes("row") }) {
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
