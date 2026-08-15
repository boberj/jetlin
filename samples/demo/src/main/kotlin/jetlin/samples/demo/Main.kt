package jetlin.samples.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import jetlin.html.Link
import jetlin.html.LocalNavigator
import jetlin.html.Nav
import jetlin.html.P
import jetlin.html.Span
import jetlin.html.Text
import jetlin.html.TextArea
import jetlin.html.Ul
import jetlin.html.bind
import jetlin.html.pathParam
import jetlin.html.rememberField
import jetlin.server.jetlin
import kotlinx.coroutines.delay

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
        jetlin {
            head = STYLES
            view("/", title = "Todos · Jetlin") { TodoListPage() }
            view("/todo/{id}", title = "Edit · Jetlin") { TodoDetailPage() }
            view("/about", title = "About · Jetlin") { AboutPage() }
        }
    }.start(wait = true)
}

@Composable
private fun Shell(content: @Composable () -> Unit) {
    Div({ classes("page") }) {
        Nav({ classes("nav") }) {
            Link("/", { classes("brand") }) { Text("Jetlin") }
            Link("/") { Text("Todos") }
            Link("/about") { Text("About") }
        }
        content()
    }
}

@Composable
private fun TodoListPage() = Shell {
    val draft = rememberField("") { if (it.isBlank()) "Enter something to do" else null }

    Div({ classes("card") }) {
        H1 { Text("Todos") }
        Div({ classes("row") }) {
            Input({
                classes("input")
                attr("data-test", "draft")
                attr("placeholder", "What needs doing?")
                bind(draft)
            })
            Button({
                classes("btn")
                attr("data-test", "add")
                disabled(!draft.isValid)
                onClick {
                    TodoStore.add(draft.value.trim())
                    draft.reset("")
                }
            }) { Text("Add") }
        }
        draft.error?.let { message ->
            P({ classes("error"); attr("data-test", "draft-error") }) { Text(message) }
        }

        Ul({ classes("todos") }) {
            TodoStore.todos.forEach { todo ->
                // Keyed by identity, so reordering moves the existing nodes rather than rewriting
                // every row's text.
                key(todo.id) { TodoRow(todo) }
            }
        }
        P({ classes("hint") }) {
            Text("${TodoStore.todos.count { !it.done }} left. This list is shared: open a second window.")
        }
    }
    ServerClock()
}

@Composable
private fun TodoRow(todo: Todo) {
    Li({ attr("data-test", "todo") }) {
        Input({
            type("checkbox")
            checked(todo.done)
            onChecked { todo.done = it }
        })
        Link("/todo/${todo.id}", { classes(if (todo.done) "todo-text done" else "todo-text") }) {
            Text(todo.title)
        }
        Button({ classes("link"); onClick { TodoStore.move(todo, -1) } }) { Text("up") }
        Button({ classes("link"); onClick { TodoStore.remove(todo) } }) { Text("remove") }
    }
}

@Composable
private fun TodoDetailPage() = Shell {
    val id = pathParam("id").toIntOrNull()
    val todo = id?.let { TodoStore.find(it) }
    val navigator = LocalNavigator.current

    if (todo == null) {
        Div({ classes("card") }) {
            H1 { Text("No such todo") }
            P { Text("Item $id is not in the list.") }
            Link("/", { classes("btn") }) { Text("Back to the list") }
        }
        return@Shell
    }

    // Keyed on the item, so moving between /todo/1 and /todo/2 refills the fields rather than
    // carrying one item's edits over to the next.
    key(todo.id) {
        val title = rememberField(todo.title) {
            when {
                it.isBlank() -> "A title is required"
                it.length > 60 -> "Keep it under 60 characters"
                else -> null
            }
        }
        val notes = rememberField(todo.notes)

        Div({ classes("card") }) {
            H1 { Text("Edit") }
            Div({ classes("field") }) {
                Span({ classes("label") }) { Text("Title") }
                Input({
                    classes(if (title.error != null) "input invalid" else "input")
                    attr("data-test", "title")
                    bind(title)
                })
                title.error?.let { message ->
                    P({ classes("error"); attr("data-test", "title-error") }) { Text(message) }
                }
            }
            Div({ classes("field") }) {
                Span({ classes("label") }) { Text("Notes") }
                TextArea({
                    classes("input")
                    attr("data-test", "notes")
                    attr("rows", "4")
                    bind(notes)
                })
            }
            Div({ classes("row") }) {
                Button({
                    classes("btn")
                    attr("data-test", "save")
                    disabled(!title.isValid)
                    onClick {
                        if (title.isValid) {
                            todo.title = title.value.trim()
                            todo.notes = notes.value
                            navigator.push("/")
                        }
                    }
                }) { Text("Save") }
                Link("/", { classes("link") }) { Text("Cancel") }
            }
            P({ classes("hint") }) {
                Text("Validation runs on the server. The Save button is disabled from there too.")
            }
        }
    }
}

@Composable
private fun AboutPage() = Shell {
    Div({ classes("card") }) {
        H1 { Text("About") }
        P {
            Text(
                "Every page here is a Kotlin @Composable running on the server. Moving between " +
                    "pages does not reload anything: the composition swaps the view and sends the " +
                    "difference.",
            )
        }
        P({ classes("hint") }) {
            Text("Try the back button, then reload — a reload starts a fresh session on this path.")
        }
    }
}

/**
 * An update the client never asked for.
 *
 * The effect runs on the server for as long as the session lives. Each tick writes state this
 * composable reads, which recomposes it, which produces a patch.
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
            Text("Session uptime: ")
            Span({ classes("count"); attr("data-test", "ticks") }) { Text("$ticks") }
            Text("s")
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
      .nav { display: flex; gap: 1.1rem; align-items: baseline; margin-bottom: 1.75rem; }
      .nav a { color: #2563eb; text-decoration: none; }
      .nav a:hover { text-decoration: underline; }
      .nav .brand { font-weight: 700; color: inherit; margin-right: auto; letter-spacing: -.01em; }
      h1 { font-size: 1.5rem; margin: 0 0 1rem; letter-spacing: -.02em; }
      h2 { font-size: .8rem; margin: 0 0 .8rem; text-transform: uppercase;
           letter-spacing: .08em; color: #6b7280; }
      .card { background: #fff; border: 1px solid #e5e7eb; border-radius: 12px;
              padding: 1.25rem; margin-bottom: 1.25rem; }
      .row { display: flex; align-items: center; gap: .6rem; }
      .field { margin-bottom: 1rem; }
      .label { display: block; font-size: .8rem; color: #6b7280; margin-bottom: .3rem; }
      .btn { font: inherit; padding: .45rem .9rem; border-radius: 8px; border: 1px solid #d1d5db;
             background: #fff; cursor: pointer; text-decoration: none; color: inherit; }
      .btn:hover { background: #f3f4f6; }
      .btn[disabled] { opacity: .5; cursor: not-allowed; }
      .link { font: inherit; font-size: .85rem; background: none; border: 0;
              color: #2563eb; cursor: pointer; padding: 0 .35rem; text-decoration: none; }
      .count { font-variant-numeric: tabular-nums; font-weight: 600; }
      .input { font: inherit; width: 100%; box-sizing: border-box; padding: .45rem .7rem;
               border-radius: 8px; border: 1px solid #d1d5db; background: #fff; color: inherit; }
      .input.invalid { border-color: #dc2626; }
      .error { color: #dc2626; font-size: .85rem; margin: .4rem 0 0; }
      .todos { list-style: none; margin: 1rem 0 0; padding: 0; }
      .todos li { display: flex; align-items: center; gap: .5rem;
                  padding: .5rem 0; border-top: 1px solid #f0f1f3; }
      .todo-text { flex: 1; color: inherit; text-decoration: none; }
      .todo-text:hover { text-decoration: underline; }
      .todo-text.done { color: #9ca3af; text-decoration: line-through; }
      .hint { margin: .9rem 0 0; font-size: .85rem; color: #9ca3af; }
      body.jl-disconnected { opacity: .6; }
      @media (prefers-color-scheme: dark) {
        body { background: #0f1115; color: #e5e7eb; }
        .card { background: #151821; border-color: #262b36; }
        .btn, .input { background: #1b1f2a; border-color: #2f3542; }
        .btn:hover { background: #222735; }
        .todos li { border-color: #262b36; }
      }
    </style>
""".trimIndent()
