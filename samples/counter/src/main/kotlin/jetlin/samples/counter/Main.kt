package jetlin.samples.counter

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import jetlin.html.Button
import jetlin.html.Div
import jetlin.html.H1
import jetlin.html.H2
import jetlin.html.Input
import jetlin.html.Li
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.Ul
import jetlin.server.jetlin
import kotlinx.coroutines.delay

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
        jetlin {
            view("/", title = "Jetlin Demo", head = STYLES) { Demo() }
        }
    }.start(wait = true)
}

@Composable
private fun Demo() {
    Div({ classes("page") }) {
        H1 { Text("Jetlin") }
        P({ classes("sub") }) {
            Text("Compose runs on the server. The browser only applies the mutations it is sent.")
        }
        Counter()
        Todos()
        ServerClock()
    }
}

@Composable
private fun Counter() {
    var count by remember { mutableStateOf(0) }

    Div({ classes("card") }) {
        H2 { Text("Counter") }
        Div({ classes("row") }) {
            Button({ classes("btn"); onClick { count-- } }) { Text("−") }
            Span({ classes("count"); attr("data-test", "count") }) { Text("$count") }
            Button({ classes("btn"); onClick { count++ } }) { Text("+") }
        }
        P({ classes("hint") }) {
            Text("One click sends one event and receives one SetText op.")
        }
    }
}

@Composable
private fun Todos() {
    val todos = remember { mutableStateListOf("Read the architecture doc", "Run the tests") }
    var draft by remember { mutableStateOf("") }

    Div({ classes("card") }) {
        H2 { Text("Todos") }
        Div({ classes("row") }) {
            Input({
                classes("input")
                attr("data-test", "draft")
                attr("placeholder", "What needs doing?")
                value(draft)
                onInput { draft = it }
            })
            Button({
                classes("btn")
                attr("data-test", "add")
                onClick {
                    if (draft.isNotBlank()) {
                        todos.add(draft.trim())
                        draft = ""
                    }
                }
            }) { Text("Add") }
        }
        Ul({ classes("todos") }) {
            todos.forEachIndexed { index, todo ->
                // Keys let the runtime move existing nodes instead of rebuilding them, so a
                // reorder ships Move ops rather than a rewritten list.
                key(todo) {
                    Li {
                        Span({ classes("todo-text") }) { Text(todo) }
                        Button({
                            classes("link")
                            disabled(index == 0)
                            onClick {
                                if (index > 0) {
                                    val item = todos.removeAt(index)
                                    todos.add(index - 1, item)
                                }
                            }
                        }) { Text("up") }
                        Button({ classes("link"); onClick { todos.remove(todo) } }) { Text("remove") }
                    }
                }
            }
        }
        P({ classes("hint") }) {
            Text("${todos.size} item(s). Typing here is debounced; the server holds the truth.")
        }
    }
}

/**
 * Server-driven updates with no extra API surface.
 *
 * There is no equivalent of LiveView's `handle_info` or a Livewire polling directive: a coroutine
 * writes state, the composables that read it recompose, and patches follow. Livewire's stateless
 * model structurally cannot do this at all.
 */
@Composable
private fun ServerClock() {
    var ticks by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            ticks++
        }
    }

    Div({ classes("card") }) {
        H2 { Text("Server push") }
        P {
            Text("Uptime: ")
            Span({ classes("count"); attr("data-test", "ticks") }) { Text("$ticks") }
            Text("s")
        }
        P({ classes("hint") }) {
            Text("A LaunchedEffect on the server ticks state; the patch arrives unprompted.")
        }
    }
}

private val STYLES = """
    <style>
      :root { color-scheme: light dark; }
      body {
        margin: 0; padding: 2.5rem 1.25rem;
        font: 16px/1.55 ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif;
        background: #f6f7f9; color: #14161a;
      }
      .page { max-width: 44rem; margin: 0 auto; }
      h1 { font-size: 2rem; margin: 0 0 .25rem; letter-spacing: -.02em; }
      h2 { font-size: 1rem; margin: 0 0 .9rem; text-transform: uppercase;
           letter-spacing: .08em; color: #6b7280; }
      .sub { margin: 0 0 2rem; color: #6b7280; }
      .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
              padding: 1.25rem; margin-bottom: 1.25rem; }
      .row { display: flex; align-items: center; gap: .6rem; }
      .btn { font: inherit; padding: .45rem .9rem; border-radius: 8px;
             border: 1px solid #d1d5db; background: #fff; cursor: pointer; }
      .btn:hover { background: #f3f4f6; }
      .link { font: inherit; font-size: .85rem; background: none; border: 0;
              color: #2563eb; cursor: pointer; padding: 0 .35rem; }
      .link[disabled] { color: #9ca3af; cursor: default; }
      .count { font-variant-numeric: tabular-nums; font-weight: 600; min-width: 2.5rem;
               text-align: center; display: inline-block; }
      .input { font: inherit; flex: 1; padding: .45rem .7rem; border-radius: 8px;
               border: 1px solid #d1d5db; }
      .todos { list-style: none; margin: 1rem 0 0; padding: 0; }
      .todos li { display: flex; align-items: center; gap: .5rem;
                  padding: .5rem 0; border-top: 1px solid #f0f1f3; }
      .todo-text { flex: 1; }
      .hint { margin: .9rem 0 0; font-size: .85rem; color: #9ca3af; }
      body.jl-disconnected { opacity: .6; }
      @media (prefers-color-scheme: dark) {
        body { background: #0f1115; color: #e5e7eb; }
        .card { background: #151821; border-color: #262b36; }
        .btn, .input { background: #1b1f2a; border-color: #2f3542; color: inherit; }
        .btn:hover { background: #222735; }
        .todos li { border-color: #262b36; }
      }
    </style>
""".trimIndent()
