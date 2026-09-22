# Jetlin

Jetlin is a framework for writing interactive web UIs as Kotlin `@Composable` functions that run **on
the server**. The browser receives HTML and an 8.6 kB runtime. The runtime applies the DOM changes the
server sends and reports user events back to it.

The approach is modeled on [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view) and
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

When the user types in the field, the browser sends one debounced event. The server replies with the
DOM changes needed to update the error message and the button's `disabled` state. Clicking Save
navigates without a page reload. There is no template language, no client-side state and no REST API
in between: the validation rule and the code that uses it are the same Kotlin code in the same file.

## How it works

Each session has a composition that runs on the server for as long as the session lasts. An update
works like this:

1. The browser sends an event that names a node and an event type. The server calls the handler lambda
   it holds for that node and event.
2. The handler changes some state. Compose tracks which composables read which state, so it knows which
   ones are now out of date.
3. Only those composables run again.
4. While the runtime updates its node tree, Jetlin records each insert, remove, move, attribute change
   and text change. The node tree is a virtual DOM, so these recorded changes are exactly what gets sent
   to the browser.

Jetlin never compares an old and a new version of the page. The runtime already knows what changed,
because it tracked which state each composable read. Reordering a keyed list moves the existing DOM
nodes instead of recreating them.

Updates don't have to start in the browser. When a coroutine writes state, the composables that read
it are invalidated and an update is sent the same way:

```kotlin
var ticks by remember { mutableStateOf(0) }
LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }
```

Because UI state lives on the server, there is no client-side cache to keep in sync and no
serialization format to design. A handler can capture the objects it needs and call your code
directly.

Some interactions don't need the server at all. Opening and closing a panel, for example, changes
nothing the server cares about, so it can be handled entirely in the browser:

```kotlin
Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
```

`clientOnly` supports a fixed set of commands: toggling, adding and removing a class, and focusing and
blurring an element. It doesn't accept arbitrary scripts, because client-side code would be a second
application that has to be kept consistent with the first. The commands are embedded in the HTML, so
the button works before the WebSocket connects and keeps working if the connection drops.

Some things can't be drawn from a server-side tree at all, such as a map or a rich-text editor.
`ClientComponent` creates an element and hands it to a JavaScript implementation that the application
registered:

```kotlin
ClientComponent(
    name = "editor",
    props = buildJsonObject { put("content", body.value) },
    onEvent = { event, payload -> if (event == "changed") body.edit(payload.html()) },
)
```

Props are sent down, events are sent up, and the component's DOM can be thrown away at any time. After
a reconnect, Jetlin rebuilds the component from the props the server still has. So don't keep anything
the user created only inside the component: send it to the server and store it in a `rememberSaved`
field, where it survives reconnects and hibernation.

### How long state lives

The composition stays up for the whole session, and navigating replaces the current view inside it.
How long a value lasts depends on where it is declared:

| Declared with | Lasts until |
|---|---|
| `remember` in a view | the user navigates away from the view |
| `rememberSaved` in a view | the user navigates away, but it is restored when they come back and when the session wakes from hibernation |
| `remember` in `app { }` | the session ends |
| `rememberSaved` in `app { }` | the session ends, and survives hibernation |

`app { }` is a composable that wraps every view. It is composed once per session, and it is the only
place where `remember` survives navigation:

```kotlin
jetlin {
    app { route ->
        val filter = remember { mutableStateOf("") }
        CompositionLocalProvider(LocalFilter provides filter) {
            Shell { route() }
        }
    }
    view("/") { TodoListPage() }
    view("/todo/{id}") { TodoDetailPage() }
}
```

Views read that state through a `CompositionLocal` that the application defines. The framework
provides a place to keep session-wide state but doesn't prescribe what it looks like.

Shared page chrome, such as a navigation bar, also belongs in `app { }`. A nav bar composed inside each
view is removed and re-inserted on every navigation. A nav bar composed in `app { }` recomposes to the
same markup and produces no DOM changes.

## Status

**Early.** The core is built and has end-to-end browser tests. Routing, request context, navigation
without reloads, forms and hibernation are built on top of it.

- [`docs/architecture.md`](docs/architecture.md) describes the design: the update path, the protocol,
  sessions, input handling and the reasoning behind the main decisions. §13 lists what is missing. The
  most important gaps: nothing is published to Maven, there are no file uploads, and it only runs on a
  single node.
- [`docs/db.md`](docs/db.md) does the same for `jetlin-db`: entities, policies, route guards and
  migrations. Its §8 lists what is missing: holding a record reference grants access to it, visibility
  changes through relations aren't propagated on write, there is no second layer of enforcement in the
  database, and there are no indexes.
- [`docs/comparison.md`](docs/comparison.md) compares Jetlin with Phoenix LiveView, Livewire, Blazor
  Server and others, including where Jetlin falls short.

Session state is kept in server memory, so the memory cost of each session determines how many users
one node can handle. With `jetlin-db`, stored records are also kept in memory, in the same heap:

| | Each | Per 1,000 |
|---|---|---|
| Live session | 136 kB | 133 MB |
| Hibernated session | 364 bytes | 356 kB |
| Stored record (`jetlin-db`) | 1.1 kB | 1 MB |

A session's memory cost is roughly proportional to the size of its page, at about 1.5 kB per node. A
page that renders a large collection costs more than one that doesn't; storing a large collection that
pages don't render in full is comparatively cheap.

When a session's WebSocket disconnects, the session stays live for a short grace period, because most
disconnects are brief (a tunnel, a laptop going to sleep). After that, the session hibernates: values
declared with `rememberSaved` are stored and the composition is destroyed. Values declared with
`remember` are discarded, which keeps the stored state small.

```kotlin
val draft = rememberSavedField("", key = "draft")   // survives; the user typed it
val expanded = remember { mutableStateOf(false) }   // does not; recomputing costs nothing
```

## Try it

```bash
./gradlew :samples:demo:run          # http://localhost:8080
./gradlew :samples:teams:run         # http://localhost:8081
```

`samples/demo` is a small app with:

- a keyed todo list
- a detail page with server-side validation, linked with a real `<a href>` that navigates without a
  page reload
- a clock updated from the server
- a panel that opens without contacting the server
- a chart drawn by JavaScript from numbers the server holds
- a page of markup that is tricky to hand back to a browser
- an `/errors` page where you can make a handler throw, then make the whole session fail, and compare
  what happens

The todo store is shared by all sessions. If you open two windows, edits in one appear in the other,
and "Reset demo data" resets every open window at once. The store is an in-memory list rather than
`jetlin-db`, because this sample is about the view layer. `samples/teams` demonstrates stored data.

## Testing your own views

`jetlin-testing` runs a view without a browser, server or WebSocket. Application tests can then
describe what the user does and sees, without dealing with how the framework delivers it:

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

A `testTag` is stored on the server-side node and is not written into the HTML, so it costs the
browser nothing. A `data-test` attribute, by comparison, is sent to every user. Browser tests are the
exception, since Playwright can only select elements by what is in the DOM. For those, set
`jetlin { exposeTestTags = true }` to also write test tags as `data-test` attributes. Don't enable it
in production.

You can limit a query to part of the page, to identify a node by its location as well as its content:

```kotlin
within(onAll(hasTestTag("todo"))[2]) { onNode(hasText("up")).click() }
```

Because the state lives on the server, a test can check things that a client-side test can't. For
example, which nodes an interaction changed:

```kotlin
val update = recordUpdate {
    within(onAll(hasTestTag("todo"))[0]) { onNode(hasTag("input")).check() }
}
// Only the row that was checked and the counter that depends on it changed.
update.assertOnlyWithin(hasTestTag("todo"), hasTestTag("remaining"))
```

This catches a bug that nothing else detects. If a list is keyed by a value that changes, the page
looks exactly the same, but every edit re-sends the entire list. If you break `key(todo.id)` in the
sample, 15 of its 16 tests still pass. The one that fails is this one.

You can also check that the right state was declared as saved:

```kotlin
onNode(hasTestTag("draft")).type("half-typed")
hibernateAndRestore()
onNode(hasTestTag("draft")).assertValue("half-typed")
```

The module doesn't depend on any test framework. Failed assertions throw `AssertionError`, so it works
with any test runner.

## Storing data

`jetlin-db` stores records as ordinary Kotlin objects. Reading a field subscribes the composable that
read it. Writing a field commits the change to SQLite first, and only then recomposes the sessions that
read it, so no page ever shows a value the database rejected.

```kotlin
@Entity
class Todo(@Owner val owner: User, title: String, done: Boolean = false) : Record() {
    var title: String by column(title)
    var done: Boolean by column(done)
    var team: Team? by reference()

    companion object : Policy<Todo, User> {
        // Readable by the owner and by the owner's team. Plain Kotlin over live objects; no query language.
        override fun canRead(record: Todo, principal: User) =
            record.owner == principal || (record.team != null && record.team == principal.team)
        override fun canWrite(record: Todo, principal: User) = record.owner == principal
    }
}

// `update` takes the principal as a context parameter: a write without one in scope doesn't compile.
todo.update { done = !done }
```

Access control is reactive. Suppose Alice unshares a todo from her team:

```kotlin
with(alice) { todo.update { team = null } }
```

Bob has his todo list open in another session. His list iterated a policy-filtered collection, so
Alice's write invalidates the compositions that read the state the policy depends on, and the todo
disappears from Bob's page. The application needs no subscriptions, broadcasts or invalidation code.
Route guards work the same way: if someone's admin role is revoked while they are on `/admin/users`,
they are moved off the page.

[`docs/db.md`](docs/db.md) describes the full design, including migrations, memory limits and what is
missing.

## Data from other systems

Some data on a page comes from systems you don't control. A value from an HTTP API is held in a
`Fetch`, which is snapshot state updated by a coroutine. Reading it subscribes the composable. When
the value arrives, every session that read it recomposes. Composition never waits for the request.

```kotlin
when (val profile = hub.profile(principal).value) {
    is Fetched.Loading -> Span { Text("…") }
    is Fetched.Failed -> Span { Text("unavailable") }
    is Fetched.Ready -> Span { Text(profile.value.status) }
}

val save = rememberAction { hub.setStatus(principal, draft.value) }
Button({ disabled(save.state is Run.Running); onClick { save() } }) { Text("Save") }
```

Writes to another system are commands, written as `suspend` functions, not property assignments.
Because they suspend, they can't be called inside `db.transact { }`, which is intentional: rolling back
a transaction can't undo an HTTP request. When a value is older than its TTL, the next read triggers a
refresh, and the old value stays on screen until the new one arrives. `fresh(every = …)` keeps a value
refreshed while a page shows it. The polling is shared by everyone showing the same value, so a hundred
viewers cause one request per interval, and it stops when the last of them leaves.
[`docs/architecture.md`](docs/architecture.md) §8 covers the details, and `samples/teams` has a working
example.

## Tests

```bash
./gradlew test                       # unit tests, asserting exact op streams
./gradlew check                      # the above, plus the conventions and the migration tooling
./gradlew :samples:demo:benchmark    # retained heap, live vs hibernated
./gradlew :samples:teams:benchmark   # retained heap per resident record

cd e2e && npm install && npx playwright test    # browser tests (server must be running)
```

The framework's tests compare exact lists of DOM operations rather than checking that a list contains
something. An update that changes more of the page than necessary therefore fails the build. The
sample's tests use `jetlin-testing` instead, and show what an application's own tests look like.

The browser tests cover:

- first paint with JavaScript blocked
- deep links rendered on the server
- targeted DOM patches and keyed list reordering
- updates that start on the server
- typing while the server sends unrelated updates
- navigation without a page load, and the back and forward buttons
- validation that blocks a submit
- reconnecting with state preserved, and keeping the server-rendered DOM on connect instead of
  rebuilding it
- a `clientOnly` panel that opens while the WebSocket is deliberately disconnected
- a client component that mounts, receives new props, sends events and is removed, with mounts and
  unmounts balanced
- state in the chrome and a view's saved state both being restored by the back button
- SVG that renders correctly whether the browser parsed it or the client built it from an operation
- a failing handler costing one interaction, while a failing view ends the session and reloads the
  page, unless the page cancels the error event to handle it itself (merely listening for the event
  doesn't count)

Each browser test resets the demo's shared store first, so tests can assert exact counts and contents
regardless of what earlier tests did. Hibernation is tested at the integration level with a real
WebSocket instead, because a browser reconnects too quickly to let the grace period expire.

## Modules

| Module | Contents |
|---|---|
| `jetlin-runtime` | `CompositionHost`, `FramePolicy`, `GlobalSnapshotManager`, `Fetch`, `Action`: running a Compose composition headlessly on the JVM |
| `jetlin-protocol` | Ops and messages (kotlinx.serialization) |
| `jetlin-html` | `LiveView`, `HtmlApplier`, the virtual DOM, element composables, routing, route guards, forms, HTML serializer |
| `jetlin-server-ktor` | HTTP and WebSocket endpoints, session registry |
| `jetlin-client` | TypeScript browser runtime (`npm run build` produces the committed `jetlin.js`) |
| `jetlin-testing` | Runs views headlessly, for testing an application's own UI logic |
| `jetlin-db` | Records, cells, the identity map, policies, the gate, snapshot transactions, SQLite |
| `jetlin-db-ksp` | KSP processor: tables, column objects, drafts, policy-checked accessors, schema snapshot |
| `jetlin-db-gradle` | `dbDiff`, `dbMigrate`, `dbVerify` and the migration engine behind them |
| `samples/demo` | Runnable five-page demo and the session memory benchmark |
| `samples/teams` | Two-user sample with owner-only records, team-shared records and an admin-only column |
| `conventions` | Repository-wide rules the compiler can't check, written as tests |

## CI

The pipeline is defined in [`ci/github-actions.yml`](ci/github-actions.yml) and **is not enabled
yet**. Move it to `.github/workflows/ci.yml` to enable it. [`ci/README.md`](ci/README.md) explains
why it is kept there and what it checks.

## Building the client

The bundled `jetlin.js` is committed, so the Gradle build doesn't need npm. To rebuild it:

```bash
npm --prefix jetlin-client install && npm --prefix jetlin-client run build
```
