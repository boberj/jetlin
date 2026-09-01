# Jetlin

Interactive web UI written as Kotlin `@Composable` functions that run **on the server**. The browser
gets HTML plus a 7.0 kB runtime that applies the DOM changes the server sends and reports events
back.

Inspired by [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view) and
[Livewire](https://livewire.laravel.com/).

```kotlin
@Composable
fun TodoDetail() {
    val todo = TodoStore.find(pathParam("id").toInt()) ?: return
    val navigator = LocalNavigator.current
    val title = rememberField(todo.title) {
        if (it.isBlank()) "A title is required" else null
    }

    Div({ classes("card") }) {
        Input({ classes("input"); bind(title) })
        title.error?.let { P({ classes("error") }) { Text(it) } }
        Button({
            disabled(!title.isValid)
            onClick { todo.title = title.value; navigator.push("/") }
        }) { Text("Save") }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080) {
        jetlin {
            view("/", title = "Todos") { TodoList() }
            view("/todo/{id}", title = "Edit") { TodoDetail() }
        }
    }.start(wait = true)
}
```

Typing sends one debounced event and receives the instructions to update the error message and the
button's disabled state. Saving navigates without reloading the page. There is no template language,
no client-side state, and no REST layer in between — the validation rule and the code that acts on
it are the same Kotlin, in the same place.

## How it works

A composition runs on the server for as long as the session lasts.

1. An event arrives naming a node and an event type. The server calls the handler lambda it holds
   for that pair.
2. State changes. Compose knows which composables read which state, so it knows which are now stale.
3. Only those composables re-run.
4. As the runtime edits its node tree, each insert, remove, move and attribute or text write is
   recorded. Jetlin's node tree is a virtual DOM, so those recorded edits are the wire protocol.

Nothing compares two versions of the page — the runtime tracked the reads, so it already knows what
changed. Reordering a keyed list moves the existing nodes rather than rebuilding them.

The same path runs when the browser did nothing at all. A coroutine that writes state invalidates
the composables that read it, and an update follows:

```kotlin
var ticks by remember { mutableStateOf(0) }
LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }
```

Because UI state lives on the server, there is no client cache to invalidate and no serialization
boundary to design — a handler closes over the objects it needs and calls straight into your code.

Not everything deserves a round trip, though. The server has no opinion about whether a panel is
open, so it is not asked:

```kotlin
Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
```

A closed set of verbs — toggle, add and remove a class, focus, blur — not a script, because
arbitrary client code would be a second application to keep in step with the first. They travel in
the markup, so the button works before a socket exists and keeps working while one is down.

## Status

**Early.** The core is built and tested end to end in a browser, with routing, request context,
live navigation, forms and hibernation on top of it. [`docs/architecture.md`](docs/architecture.md)
has the full design: the update path, the protocol, sessions, input handling, design decisions, and
what is designed but not yet built (multi-node, enclaves, uploads).

Session state lives on the server, so per-session cost sets how many users a node can carry:

| | per session | 1000 sessions |
|---|---|---|
| live | 136 kB | 133 MB |
| hibernated | 364 bytes | 356 kB |

A session whose socket has gone stays live briefly — most disconnections are a tunnel or a sleeping
laptop — then hibernates: whatever was declared `rememberSaved` is stored and the composition is
destroyed. `remember` is scratch space and is deliberately not kept, which is what holds the saved
payload down.

```kotlin
val draft = rememberSavedField("", key = "draft")   // survives; the user typed it
val expanded = remember { mutableStateOf(false) }   // does not; recomputing costs nothing
```

## Try it

```bash
./gradlew :samples:demo:run          # http://localhost:8080
```

A small app: a keyed todo list, a detail page with server-side validation reached by a real
`<a href>` that navigates without reloading, a clock driven from the server, and a page of markup
shapes that are awkward to hand back to a browser. The store is shared across sessions, so opening
two windows shows edits in one appearing in the other — and "Reset demo data" puts it back, in every
open window at once.

## Testing your own views

`jetlin-testing` runs a view with no browser, no server and no socket, so an application's tests
describe what a user does and sees rather than how the framework carries it:

```kotlin
@Composable
fun TodoDetail() {
    Input({ testTag("title"); bind(title) })      // names the node for tests, and nothing else
    Button({ testTag("save"); disabled(!title.isValid) }) { Text("Save") }
}

@Test
fun `clearing the title blocks the save`(): Unit = runViewTest(url = "/todo/1") {
    setContent(route = "/todo/{id}") { TodoDetail() }

    onNode(hasTestTag("title")).type("")

    onNode(hasTestTag("title-error")).assertText("A title is required")
    onNode(hasTestTag("save")).assertDisabled()
}
```

A `testTag` is held on the server's node, not written into the page, so it costs the browser
nothing — unlike a `data-test` attribute, which ships to every user forever. Browser tests are the
exception, since Playwright can only select on what is really in the DOM; `jetlin { exposeTestTags
= true }` writes them out as `data-test` as well, for use outside production.

Queries can be confined to part of the page, which is how to say *where* something is rather than
what it is:

```kotlin
within(onAll(hasTestTag("todo"))[2]) { onNode(hasText("up")).click() }
```

Because state lives on the server, a test can also ask questions a client-side one cannot. Which
nodes an interaction actually changed:

```kotlin
val update = recordUpdate {
    within(onAll(hasTestTag("todo"))[0]) { onNode(hasTag("input")).check() }
}
// The row that was ticked and the counter that depends on it — and nothing else.
update.assertOnlyWithin(hasTestTag("todo"), hasTestTag("remaining"))
```

That catches a defect nothing else can see. Keying a list by a value that changes renders exactly
the same page and re-sends the whole list on every edit; break `key(todo.id)` in the sample and
fifteen of its sixteen tests still pass — the one that fails is this one.

And whether the right state was declared saveable:

```kotlin
onNode(hasTestTag("draft")).type("half-typed")
hibernateAndRestore()
onNode(hasTestTag("draft")).assertValue("half-typed")
```

The module depends on no test framework — assertions throw `AssertionError` — so it works with
whichever runner you already use.

## Test

```bash
./gradlew test                       # 151 unit tests, asserting exact op streams
./gradlew :samples:demo:benchmark    # retained heap, live vs hibernated

cd e2e && npm install && npx playwright test    # 21 browser tests (server must be running)
```

The framework's own tests assert on exact op lists rather than `contains`, so an update that touches
more of the page than it needs to fails the build. The sample's tests are written against
`jetlin-testing` instead, and are the worked example of what an application's tests look like. Browser tests cover first paint with JavaScript blocked, deep
links rendering server-side, targeted patching, keyed list reordering, server-originated updates,
typing while the server sends unrelated updates, navigation without a page load, back and forward,
validation gating a submit, reconnection with state preserved, that the server-rendered DOM is
kept rather than rebuilt on connect, and that a `clientOnly` disclosure still opens with the socket
deliberately disconnected. Each one resets the demo's shared
store first, so they assert exact counts and contents rather than working around whatever the
previous test left. Hibernating and waking a session is covered at the integration level instead,
driving a real socket, because a browser reconnects on its own too quickly to sit out a grace
period.

## Modules

| Module | Contents |
|---|---|
| `jetlin-runtime` | `CompositionHost`, `FramePolicy`, `GlobalSnapshotManager` — running a Compose composition headlessly on the JVM |
| `jetlin-protocol` | Ops and messages (kotlinx.serialization) |
| `jetlin-html` | `LiveView`, `HtmlApplier`, the virtual DOM, element composables, routing, forms, HTML serializer |
| `jetlin-server-ktor` | HTTP + WebSocket endpoints, session registry |
| `jetlin-client` | TypeScript browser runtime (`npm run build` → checked-in `jetlin.js`) |
| `jetlin-testing` | Driving a view headlessly, for testing an application's own UI logic |
| `samples/demo` | Runnable three-page demo and the memory benchmark |
| `conventions` | Repo-wide rules the compiler cannot express, checked as tests |

## CI

The pipeline lives in [`ci/github-actions.yml`](ci/github-actions.yml) and is **not active yet** —
move it to `.github/workflows/ci.yml` to enable it. See [`ci/README.md`](ci/README.md) for why it is
parked there and what it checks.

## Building the client

The bundled `jetlin.js` is checked in, so the Gradle build needs no npm:

```bash
npm --prefix jetlin-client install && npm --prefix jetlin-client run build
```

## Note on dependency versions

Compose Multiplatform is pinned to **1.5.12**, the newest release that resolves entirely from Maven
Central; 1.6 and later pull androidx artifacts published only to Google's Maven repo, which the
development environment could not reach. Nothing here depends on anything newer — `Applier`,
`Composition`, `Recomposer` and the snapshot system are stable across all of these — so it is a
one-line bump.
