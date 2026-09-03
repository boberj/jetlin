package jetlin.samples.keyed

import androidx.compose.runtime.remember
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import jetlin.server.jetlin

/**
 * Serves the benchmark page, so the operations can be watched in a real browser.
 *
 * The measurements come from [main] in `Runner.kt`, which needs no server at all. This exists for
 * the other half of the question: whether the patches this framework produces actually land, and
 * what a table of ten thousand server-held rows feels like to click on.
 *
 * Run with: `./gradlew :samples:keyed-benchmark:run` and open http://localhost:8080
 */
fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
        jetlin {
            head = BOOTSTRAP
            view("/", title = "Jetlin-keyed") {
                // Remembered in the view, so the rows belong to the session looking at them rather
                // than to the process. Two open windows are two independent tables.
                val store = remember { RowStore(seed = System.nanoTime().toInt()) }
                BenchmarkPage(store)
            }
        }
    }.start(wait = true)
}

/**
 * The stylesheet the benchmark's rules require.
 *
 * Linked rather than inlined, and to the same version the reference implementations use, because
 * the row markup is written to match theirs and the classes have to mean the same thing.
 */
private val BOOTSTRAP = """
    <link href="https://cdn.jsdelivr.net/npm/bootstrap@3.4.1/dist/css/bootstrap.min.css" rel="stylesheet">
""".trimIndent()
