package jetlin.samples.demo

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.ktor.server.engine.embeddedServer
import io.ktor.http.ContentType
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import jetlin.html.Button
import jetlin.html.Circle
import jetlin.html.Div
import jetlin.html.ForeignObject
import jetlin.html.H1
import jetlin.html.H2
import jetlin.html.Input
import jetlin.html.Li
import jetlin.html.Link
import jetlin.html.LocalNavigator
import jetlin.html.Nav
import jetlin.html.Option
import jetlin.html.P
import jetlin.html.Polyline
import jetlin.html.Select
import jetlin.html.Span
import jetlin.html.Svg
import jetlin.html.SvgTitle
import jetlin.html.Text
import jetlin.html.TextArea
import jetlin.html.Ul
import jetlin.html.ClientComponent
import jetlin.html.bind
import jetlin.html.closest
import jetlin.html.pathParam
import jetlin.html.rememberField
import jetlin.html.rememberSavedField
import jetlin.server.jetlin
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun main() {
    val port = System.getenv("PORT")?.toInt() ?: 8080
    embeddedServer(Netty, port = port) {
        routing {
            for (name in listOf("sparkline.js", "errors.js")) {
                get("/demo/$name") {
                    val script = checkNotNull(object {}.javaClass.getResource("/demo/$name")).readText()
                    call.respondText(script, ContentType.Text.JavaScript)
                }
            }
        }
        jetlin {
            // This app exists to be tested, so its test tags are written into the markup for
            // Playwright to select on. A real application would set this only outside production,
            // where the default keeps them off the wire entirely.
            exposeTestTags = true
            head = STYLES
            // Registered before the session connects, so the sparkline's implementation is there
            // when the runtime takes up the markup it was served.
            clientSetup = """
                <script src="/demo/sparkline.js"></script>
                <script src="/demo/errors.js"></script>
            """.trimIndent()
            // Where a real application would forward this to its error reporting. Printing it is
            // the demo's version of that; the point is that the exception reaches the application
            // rather than only a log line nobody reads.
            onError = { throwable ->
                println("[demo] a session reported: ${throwable::class.simpleName}: ${throwable.message}")
            }
            view("/", title = "Todos · Jetlin") { TodoListPage() }
            view("/todo/{id}", title = "Edit · Jetlin") { TodoDetailPage() }
            view("/about", title = "About · Jetlin") { AboutPage() }
            view("/shapes", title = "Shapes · Jetlin") { ShapesPage() }
            view("/errors", title = "Errors · Jetlin") { ErrorsPage() }
        }
    }.start(wait = true)
}

@Composable
internal fun Shell(content: @Composable () -> Unit) {
    Div({ classes("page") }) {
        Nav({ classes("nav") }) {
            Link("/", { classes("brand") }) { Text("Jetlin") }
            Link("/") { Text("Todos") }
            Link("/about") { Text("About") }
            Link("/shapes") { Text("Shapes") }
            Link("/errors") { Text("Errors") }
            Button({
                classes("link")
                testTag("reset")
                onClick { TodoStore.reset() }
            }) { Text("Reset demo data") }
        }
        content()
    }
}

@Composable
internal fun TodoListPage() = Shell {
    // Saved rather than remembered: a half-typed todo is worth carrying across a dropped
    // connection or a deploy, and it is the one piece of state on this page the user authored.
    val draft = rememberSavedField("", key = "draft") {
        if (it.isBlank()) "Enter something to do" else null
    }

    Div({ classes("card") }) {
        H1 { Text("Todos") }
        Div({ classes("row") }) {
            Input({
                classes("input")
                testTag("draft")
                attr("placeholder", "What needs doing?")
                bind(draft)
            })
            Button({
                classes("btn")
                testTag("add")
                disabled(!draft.isValid)
                onClick {
                    TodoStore.add(draft.value.trim())
                    draft.reset("")
                }
            }) { Text("Add") }
        }
        draft.error?.let { message ->
            P({ classes("error"); testTag("draft-error") }) { Text(message) }
        }

        Ul({ classes("todos") }) {
            TodoStore.todos.forEach { todo ->
                // Keyed by identity, so reordering moves the existing nodes rather than rewriting
                // every row's text.
                key(todo.id) { TodoRow(todo) }
            }
        }
        P({ classes("hint"); testTag("remaining") }) {
            Text(
                "${TodoStore.todos.count { !it.done }} left. The list is shared across sessions, " +
                    "and what you type above survives a disconnect.",
            )
        }
    }
    ServerClock()
}

@Composable
internal fun TodoRow(todo: Todo) {
    Li({ testTag("todo") }) {
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
internal fun TodoDetailPage() = Shell {
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
                    testTag("title")
                    bind(title)
                })
                title.error?.let { message ->
                    P({ classes("error"); testTag("title-error") }) { Text(message) }
                }
            }
            Div({ classes("field") }) {
                Span({ classes("label") }) { Text("Notes") }
                TextArea({
                    classes("input")
                    testTag("notes")
                    attr("rows", "4")
                    bind(notes)
                })
            }
            Div({ classes("row") }) {
                Button({
                    classes("btn")
                    testTag("save")
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

/**
 * What happens when the server-side code goes wrong.
 *
 * Two failures that look identical from the outside — an exception reaching the transport — and are
 * not the same thing at all. A handler that throws leaves the composition untouched, so one
 * interaction did not happen and everything else still works. A composable that throws stops the
 * recomposer for good, so the session can only be abandoned.
 *
 * Telling them apart is worth the trouble: treating every failure as fatal throws away sessions that
 * were fine, and treating none of them as fatal leaves a page that looks live and can never change
 * again.
 */
@Composable
internal fun ErrorsPage() = Shell {
    var clicks by remember { mutableStateOf(0) }
    var broken by remember { mutableStateOf(false) }

    Div({ classes("card") }) {
        H1 { Text("When things go wrong") }
        P {
            Text(
                "Nothing here reaches the browser except a fixed sentence. The exception itself is " +
                    "logged and handed to the application, because its text carries paths and " +
                    "identifiers that are nobody's business in a page.",
            )
        }

        Div({ classes("field") }) {
            H2 { Text("A handler that fails") }
            P({ classes("hint") }) {
                Text("One interaction is lost. The session is untouched — the counter still counts.")
            }
            Div({ classes("row") }) {
                Button({
                    classes("btn")
                    testTag("fail-handler")
                    onClick { error("this handler was always going to do this") }
                }) { Text("Throw in a handler") }
                Button({
                    classes("btn")
                    testTag("still-works")
                    onClick { clicks++ }
                }) { Text("Still works") }
                Span({ classes("count"); testTag("clicks") }) { Text("$clicks") }
            }
        }

        Div({ classes("field") }) {
            H2 { Text("A view that fails") }
            P({ classes("hint") }) {
                Text(
                    "The recomposer stops, so this session is over. The browser is told so and " +
                        "reloads into a new one — which is why the counter above goes back to zero.",
                )
            }
            Div({ classes("row") }) {
                Button({
                    classes("btn")
                    testTag("fail-view")
                    onClick { broken = true }
                }) { Text("Throw in the view (reloads the page)") }
                Link("/errors?handle", { classes("link"); testTag("handle-link") }) {
                    Text("…or handle it in the page instead")
                }
            }
            P({ classes("hint") }) {
                Text(
                    "The event Jetlin raises is cancelable. A page that calls preventDefault on a " +
                        "fatal one is saying it will deal with this, and the reload does not " +
                        "happen — which leaves a page that cannot change until someone starts a " +
                        "new session. Listening alone is not enough, so an application forwarding " +
                        "errors to its telemetry still gets the default.",
                )
            }
        }
    }

    // Composed fine the first time and fatal on the next pass, which is exactly the shape of a real
    // one: a view that was correct until some state made it not.
    if (broken) error("this view cannot render in this state")
}

/**
 * Markup that is awkward to hand back to a browser and take up again.
 *
 * Every shape here is one the HTML parser would blur: two text nodes it would merge into one, a text
 * node with nothing in it to produce, text sitting either side of an element, markup that is not the
 * composition's to touch, and a subtree it hands to another language entirely. The page exists so
 * those cases are exercised rather than reasoned about, since a mistake in any of them shows up not
 * on load but several interactions later.
 */
@Composable
internal fun ShapesPage() = Shell {
    var first by remember { mutableStateOf("alpha") }
    var second by remember { mutableStateOf("beta") }
    var middle by remember { mutableStateOf("") }
    var markup by remember { mutableStateOf("<b>bold</b>") }

    Div({ classes("card") }) {
        H1 { Text("Markup shapes") }

        Div({ classes("field") }) {
            Span({ classes("label") }) { Text("Two text nodes side by side") }
            P({ testTag("adjacent") }) {
                Text(first)
                Text(second)
            }
            Div({ classes("row") }) {
                Button({
                    classes("btn"); testTag("edit-first")
                    onClick { first = "ALPHA" }
                }) { Text("Edit the first") }
                Button({
                    classes("btn"); testTag("edit-second")
                    onClick { second = "BETA" }
                }) { Text("Edit the second") }
            }
        }

        Div({ classes("field") }) {
            Span({ classes("label") }) { Text("A text node that starts with nothing in it") }
            P({ testTag("empty") }) {
                Text("[")
                Text(middle)
                Text("]")
            }
            Button({
                classes("btn"); testTag("fill")
                onClick { middle = "filled" }
            }) { Text("Fill it") }
        }

        Div({ classes("field") }) {
            Span({ classes("label") }) { Text("Text either side of an element") }
            P({ testTag("interleaved") }) {
                Text("before ")
                Span({ classes("count") }) { Text(first) }
                Text(" after")
            }
        }

        Div({ classes("field") }) {
            Span({ classes("label") }) { Text("Markup the composition does not own") }
            Div({ testTag("raw"); unsafeInnerHtml(markup) })
            Button({
                classes("btn"); testTag("swap-raw")
                onClick { markup = "<i>italic</i>" }
            }) { Text("Swap it") }
        }
    }
    Chart()
}

/** The readings each series starts with. Two of the same length, so switching only moves the line. */
private val SERIES = mapOf(
    "speed" to listOf(6, 9, 5, 12, 8),
    "fuel" to listOf(11, 7, 13, 6, 9),
)

/**
 * A chart the server draws, in the other language a browser understands.
 *
 * Nothing here is a picture the server produced — it is a tree of elements like any other, patched
 * the same way. What makes it worth a page of its own is that the browser will not accept those
 * elements from the usual door: a `<circle>` created as HTML is a real element that occupies no
 * space and reports no error, so getting this wrong shows up as an empty rectangle rather than as
 * anything anybody could debug. The composition says which language it meant, and each node carries
 * that to the browser on both paths — the markup served for first paint, and the ops that patch it
 * afterwards.
 *
 * The caption sits in a `<foreignObject>`, which hands the browser back to HTML in the middle of the
 * drawing, so text wraps the way text does.
 */
@Composable
internal fun Chart() {
    var series by remember { mutableStateOf("speed") }
    var added by remember { mutableStateOf(emptyList<Int>()) }
    val readings = SERIES.getValue(series) + added

    val peak = readings.max().coerceAtLeast(1)
    val step = if (readings.size > 1) (RIGHT - LEFT) / (readings.size - 1) else 0.0
    val points = readings.mapIndexed { index, value ->
        val x = (LEFT + index * step).roundToInt()
        val y = (BOTTOM - (BOTTOM - TOP) * value / peak).roundToInt()
        x to y
    }

    Div({ classes("card") }) {
        H2 { Text("Drawn on the server") }

        Div({ classes("row") }) {
            Span({ classes("label") }) { Text("Series") }
            Select({
                classes("input"); testTag("chart-series")
                // A dropdown commits a choice rather than reporting a keystroke, so it listens for
                // "change" and not "input".
                onChange { series = it }
            }) {
                for (name in SERIES.keys) {
                    Option({ value(name); selected(name == series) }) {
                        Text(if (name == "speed") "Speed over ground" else "Fuel burn")
                    }
                }
            }
        }

        Svg({
            classes("chart"); testTag("chart")
            // Mixed case, and it has to survive the serializer, the HTML parser and setAttribute:
            // "viewbox" is silently not the same attribute at all.
            attr("viewBox", "0 0 $VIEW_WIDTH $VIEW_HEIGHT")
            attr("role", "img")
        }) {
            SvgTitle { Text("${readings.size} readings, newest last") }
            Polyline({
                testTag("chart-line")
                attr("points", points.joinToString(" ") { (x, y) -> "$x,$y" })
                attr("fill", "none")
                attr("stroke", "currentColor")
                attr("stroke-width", "1.5")
            })
            points.forEach { (x, y) ->
                Circle({
                    classes("point")
                    attr("cx", "$x"); attr("cy", "$y"); attr("r", "2")
                })
            }
            ForeignObject({
                attr("x", "${LEFT.roundToInt()}"); attr("y", "0")
                attr("width", "${(RIGHT - LEFT).roundToInt()}"); attr("height", "14")
            }) {
                P({ classes("chart-caption"); testTag("chart-caption") }) {
                    Text("Peak $peak — this line is HTML again")
                }
            }
        }

        Div({ classes("row") }) {
            Button({
                classes("btn"); testTag("chart-add")
                // Appends a shape to a subtree the browser has already parsed, which is the other
                // path into the DOM: an insert op, built by the client rather than by the parser.
                onClick { added = added + ((added.size * 5 + 4) % 11 + 3) }
            }) { Text("Another reading") }
            Span({ classes("count"); testTag("chart-values") }) { Text(readings.joinToString(",")) }
        }
    }
}

private const val VIEW_WIDTH = 220
private const val VIEW_HEIGHT = 80
private const val LEFT = 10.0
private const val RIGHT = 210.0
private const val TOP = 20.0
private const val BOTTOM = 72.0

@Composable
internal fun AboutPage() = Shell {
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
    Disclosure()
    Sparkline()
}

/**
 * A canvas the server cannot draw, fed by state the server owns.
 *
 * Everything else on these pages is markup a composition can produce. A chart is not: it is pixels,
 * drawn by code that has to run where the canvas is. So the composition renders an empty element,
 * names an implementation for it, and passes the numbers down as props.
 *
 * Nothing is preserved if the connection drops long enough to need the tree resent — the canvas is
 * rebuilt and redrawn from these same props, which is exactly why they live here and not in the
 * browser.
 */
@Composable
internal fun Sparkline() {
    var points by remember { mutableStateOf(listOf(3, 7, 4, 9, 6)) }

    Div({ classes("card") }) {
        H2 { Text("Drawn in the browser") }
        ClientComponent(
            name = "sparkline",
            props = buildJsonObject {
                put("points", buildJsonArray { points.forEach { add(JsonPrimitive(it)) } })
            },
            attrs = { classes("sparkline"); testTag("sparkline") },
            onEvent = { event, payload ->
                // The chart reports which bar was clicked; the server decides what that means.
                if (event == "picked") {
                    val index = payload["index"]!!.jsonPrimitive.int
                    points = points.toMutableList().also { it[index] = (it[index] % 9) + 1 }
                }
            },
        )
        P({ classes("hint"); testTag("sparkline-values") }) { Text(points.joinToString(",")) }
        Button({
            classes("btn")
            testTag("sparkline-shuffle")
            onClick { points = points.map { (it % 9) + 1 } }
        }) { Text("New numbers") }
    }
}

/**
 * A panel that opens without asking the server.
 *
 * Everything else on these pages is a round trip, which is right when the server has an opinion —
 * it owns the todos, the validation and the routing. It has no opinion about whether a panel is
 * open, so paying a network hop to find out would be latency spent on nothing. The button declares
 * what the browser should do and the browser does it, with the socket idle or even disconnected.
 */
@Composable
internal fun Disclosure() {
    Div({ classes("card disclosure"); testTag("disclosure") }) {
        H2 { Text("No round trip") }
        Button({
            classes("btn")
            testTag("disclosure-toggle")
            clientOnly { toggleClass("open", on = closest("disclosure")) }
        }) { Text("How this one works") }
        Div({ classes("panel"); testTag("disclosure-panel") }) {
            P {
                Text(
                    "This panel is shown by a class the browser toggles for itself. The server " +
                        "was not asked, and does not know. Disconnect the socket and it still works.",
                )
            }
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
internal fun ServerClock() {
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
            Span({ classes("count"); testTag("ticks") }) { Text("$ticks") }
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
      .toast { position: fixed; left: 50%; bottom: 1.5rem; transform: translateX(-50%);
               background: #14161a; color: #fff; padding: .6rem 1rem; border-radius: 8px;
               font-size: .9rem; box-shadow: 0 6px 20px rgba(0,0,0,.25); }
      .toast-fatal { background: #b91c1c; }
      .banner { position: fixed; inset: auto 0 0 0; background: #b91c1c; color: #fff;
                padding: .8rem 1rem; display: flex; gap: .8rem; align-items: center;
                justify-content: center; font-size: .9rem; }
      .banner .btn { background: transparent; color: #fff; border-color: rgba(255,255,255,.6); }
      .jl-dead .page { opacity: .45; pointer-events: none; }
      .chart { display: block; width: 100%; height: auto; aspect-ratio: 220 / 80; color: #2563eb; }
      .chart .point { fill: #2563eb; }
      .chart-caption { margin: 0; font-size: 5px; font-weight: 500; color: #6b7280; }
      .sparkline { display: flex; align-items: flex-end; gap: .35rem; height: 4rem; }
      .sparkline .bar { flex: 1; background: #2563eb; border-radius: 3px 3px 0 0; cursor: pointer; }
      .sparkline .bar:hover { background: #1d4ed8; }
      .disclosure .panel { display: none; }
      .disclosure.open .panel { display: block; margin-top: .9rem; }
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
