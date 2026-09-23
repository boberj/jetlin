# Jetlin architecture

Jetlin is a server-driven UI framework for Kotlin. You write an interactive web UI as `@Composable`
functions that run on the server. The browser receives HTML and a small runtime, which applies the DOM
changes that the server sends and reports user events back.

The idea comes from Phoenix LiveView and Livewire.

Jetlin is at an early stage. The core is built, and tested end to end in a real browser. Routing,
request context, navigation, forms, hibernation, and reuse of the server-rendered DOM are built on
top of it. [§13](#13-whats-missing) lists what's missing, in order of how likely each gap is to block
a release.

## 1. How it works

Each user session has a composition that runs on the server for as long as the session lasts. When
state in the composition changes, the Compose runtime recomposes the affected parts and updates its
node tree. Jetlin's node tree is a virtual DOM, and every change the runtime makes to it is recorded
as an operation, or op. Jetlin sends the ops to the browser.

```
 user clicks                                                    DOM updated
      │                                                               ▲
      ▼                                                               │
 event { node, event, seq }                          patch { rev, ack, ops }
      │                                                               │
      └──► handler lambda ──► state changes ──► recomposition ──► applier records ops
```

An update has four steps:

1. An event arrives, naming a node ID and an event type. The server looks up the handler lambda it
   holds for that node and event, and calls it inside a mutable snapshot.
2. State changes. Compose tracks which composables read which state, so it knows exactly which ones
   are now out of date.
3. Recomposition runs only those composables again. Everything else is skipped.
4. The applier records ops. As the runtime changes the node tree, each insert, remove, move,
   attribute change, and text change is added to a buffer. The buffer is emptied once per
   recomposition pass and sent as one patch.

None of these steps compares two versions of the page. The runtime already knows what changed,
because it tracked the reads, so the ops describe the changes directly instead of being computed from
a diff. Incrementing a counter produces one `SetText` op. Reordering a keyed list produces `Move` ops
and leaves the nodes themselves unchanged.

The same steps run when an update doesn't come from the browser. When a coroutine writes state, for
example a timer, a message from another user's session, or a committed database transaction, the
composables that read that state are invalidated, and a patch goes out. Pushing updates to a
connected client needs no separate API. `jetlin-db` relies on this: a stored record is composition
state, so committing a write recomposes every session that read it. See [`db.md`](db.md).

## 2. Modules

```
┌─ Browser ──────────────────────────────────────────────────────────────────┐
│  jetlin.js (8.6 kB minified)                                               │
│  applies ops · delegates events · guards in-flight input · reconnects      │
└───────────────▲──────────────────────────────────────┬─────────────────────┘
                │ patch { rev, ack, ops }              │ event { node, seq }
┌───────────────┴──────────────────────────────────────▼─────────────────────┐
│ jetlin-server-ktor   GET → HTML + token · WS → adopt session               │
├────────────────────────────────────────────────────────────────────────────┤
│ jetlin-html          LiveView · HtmlOwner · HtmlApplier · HTML serializer   │
├────────────────────────────────────────────────────────────────────────────┤
│ jetlin-runtime       CompositionHost · FramePolicy · GlobalSnapshotManager  │
├────────────────────────────────────────────────────────────────────────────┤
│ androidx.compose.runtime   Recomposer · Composition · Applier · snapshots   │
└────────────────────────────────────────────────────────────────────────────┘
```

`jetlin-protocol` defines the ops and messages that the other modules share.

## 3. Running a composition on a server

A UI toolkit normally starts Compose. `CompositionHost` provides the same pieces without one: a
`Recomposer` that schedules work, a `Composition` connected to an `Applier` that builds the node
tree, and a frame clock that paces updates. The runtime doesn't care that the tree is a virtual DOM
instead of a set of views.

Three details are important.

### One thread per session

Each session runs on `Dispatchers.Default.limitedParallelism(1)`. Event handling, recomposition, and
sending patches all run there, so they can't interleave. That prevents a class of race conditions
without locks, and it also applies back-pressure to each session for free.

### Each handler runs in one mutable snapshot

`transact { }` wraps a handler in `Snapshot.withMutableSnapshot`. However many state objects the
handler writes, the runtime gets one apply notification, runs one recomposition pass, and produces one
patch.

### Writes outside a composition need a pump

State written outside a composition goes into the global snapshot, and no recomposer sees it until
something calls `Snapshot.sendApplyNotifications()`. Outside a UI toolkit, nothing calls it, and the
failure is silent: state changed in the background would never cause a recomposition.
`GlobalSnapshotManager` registers a global write observer that calls it. Notifications are
conflated, so a burst of writes wakes the recomposers once.

`FramePolicy` controls how quickly state changes turn into patches. `Immediate` recomposes as soon as
there's work. `Paced(interval)` limits the rate, which helps when a fast server-side source feeds a
session, and the client can't make use of every change. Each frame sends at most one patch message.

### Knowing when a session has finished its work

This doesn't use the recomposer's state at all. `Recomposer.currentState` is a cached value, updated
only at certain points. Two code paths that involve movable content empty the recomposer's work
queues without updating it: composing movable content in the first frame, and removing movable
content. After either, the state reports `PendingWork` indefinitely, even though nothing is pending
and the loop is asleep.

A host that waited for `Idle` would wait until something unrelated happened. In a live session, this
caused a deadlock, because the inbound loop handles one event at a time, and the sender waits before
every patch. The same code is still present in Compose 1.13.0-alpha02.

So `CompositionHost` uses `Recomposer.hasPendingWork`, which is computed when it's read, combined with
a count of the tasks still queued on the session's dispatcher, which `SessionActivity` keeps. The
Compose desktop scene and both Compose test harnesses use the same combination. There are two waits,
both on the session's serial dispatcher:

- `awaitApplied()` waits until everything signaled so far has been recomposed and applied. The server
  calls it for every client event and before every outgoing message, so it waits for nothing more.
- `awaitIdle()` waits until the session has settled: queued effects have run, and writes made outside
  a snapshot have been published. Tests use it, and so do the first render and hibernation. The last
  two have a time limit, so an effect that never settles delays them instead of hanging them.

The task count and the pending-work condition can't be read together atomically. So the check reads
the count before and after the condition, along with a dispatch sequence number, and trusts the
condition only if no task could have run in between. Waiters are woken when the dispatcher's queue
drains, not by polling, because a server might have thousands of mostly idle sessions, and it waits on
every event.

> [!NOTE]
> When a composable throws, the recomposer ends up `Inactive`, not `ShutDown`. So the recomposer's
> state alone can't tell a failed composition apart from one that hasn't started. Jetlin uses the
> completion of the runner job as the signal instead. It wakes every waiter, so waiting on a failed
> composition throws instead of hanging.

## 4. The virtual DOM

`ElementNode` and `TextNode` make up the server-side tree. `HtmlApplier` extends `AbstractApplier`,
and records each change the runtime makes as an op.

Three mechanisms do most of the work.

### Attachment gating

A node emits ops only once it's connected to the root. Compose builds subtrees bottom-up, adding a
node's children before attaching the node to its parent. Without gating, a new subtree would produce
dozens of create, configure, and attach ops for nodes the client can't see yet. With gating, the
whole subtree goes out as one `Insert` when it becomes visible.

### Data and handlers are stored separately

An element's `update` block makes two calls:

```kotlin
set(data)     { applyData(it) }      // attributes, properties, listener specs → compared, may emit ops
set(handlers) { this.handlers = it }  // lambdas → always stored, never transmitted
```

Handler lambdas are new objects on every recomposition. If they were part of the compared value, every
element would look changed, and nothing would ever be skipped. If they weren't stored at all, a
handler would keep capturing values from an old pass. Storing them separately solves both problems:
the comparison sees only data, and the lambdas are replaced on every pass.

### Text is a node, not a string

Values are never inserted into markup as strings, so there's nothing to escape and no way to forget
to escape it. `AttrsScope.unsafeInnerHtml()` is the deliberate exception, for content that's already
trusted HTML. It's sent as an `innerHTML` write through the existing `SetProp` op. An element that
uses it can't also have composable children, because the raw markup and the child nodes would
overwrite each other.

## 5. Protocol

The server sends these ops to the client:

| Op | Meaning |
|---|---|
| `ins(parent, index, node)` | Insert a complete subtree. |
| `rm(parent, index, count)` | Remove children. |
| `mv(parent, from, to, count)` | Reorder children. |
| `attr(id, name, value)` | Set an attribute. `null` removes it. |
| `prop(id, name, value)` | Set a DOM property, such as `value` or `checked`. |
| `text(id, text)` | Set a text node's content. |
| `on(id, event, spec)` and `off(id, event)` | Add or remove an event listener. |

The server sends these messages:

- `patch{rev, ack, ops}`: the changes from one recomposition pass.
- `ready{rev}`: the server accepted the client's request to keep the server-rendered markup. See
  [§7](#reusing-the-markup-the-browser-already-has).
- `reset{rev, children}`: the full tree, sent when a client can't keep its markup.
- `nav{url, replace, title}`: the session moved, and the address bar should follow.
- `error{message, fatal}`: something failed. See [Failures](#failures).

The client sends these messages:

- `hello{token, url, adopt}`: the first message on a connection.
- `event{node, event, seq, payload}`: a DOM event on a node with a server-side handler.
- `nav{url}`: the user pressed the back or forward button.

Navigation messages go out on the same channel as patches, after the patch that rendered the new page,
so the address bar never gets ahead of the content on screen.

A `ListenerSpec` tells the client what to extract from the DOM event (`value`, `checked`, `key`, or
`form`) and how to handle it (`debounceMs`, `throttleMs`, `preventDefault`, and `stopPropagation`).
Handlers stay on the server. The client knows only that a node listens for an event.

An element in an `ins` op also carries `ns` when it isn't HTML, which currently means SVG. The browser
needs the namespace before it creates the node, because `createElement("circle")` creates an
`HTMLUnknownElement` that draws nothing and reports no error. The server sends the namespace
explicitly instead of letting the client infer it from the tag, because some tags, namely `a`,
`title`, `style`, and `script`, exist in both HTML and SVG, so no list of tags on the client could be
right for all of them. Every element states its own namespace, not only the ones where the language
switches, so a subtree means the same on its own as it does in place. The default, HTML, is omitted,
so pages without drawings carry no extra data.

Messages are encoded as JSON with kotlinx.serialization, with a `t` field as the type discriminator.
A compact positional encoding or CBOR could replace it without other changes, but readable messages
are more useful at this stage.

## 6. The browser runtime

The runtime is 8.6 kB minified, with no dependencies. It keeps three things: a map from node ID to DOM
node, its own list of logical children for each element, and the listener specs.

The runtime keeps its own child lists because the DOM's `childNodes` can't be trusted for indexing:
browsers merge adjacent text nodes, and third-party scripts can insert elements. With its own list, an
index in an op always means what the server meant.

`mv` has to follow the server's move semantics exactly, including how the destination index is
adjusted when items move toward the end. Any difference would make the two sides reorder lists
differently, without any error.

One capture-phase listener for each event type on the container handles all events. It uses the
capture phase instead of bubbling, so events that don't bubble, such as `focus` and `blur`, still
reach the listener, and one registration keeps working however much the subtree changes.

Nodes are created with `createElementNS` when the spec gives a namespace, and with `createElement`
otherwise. Nothing else in the runtime treats SVG differently. `setAttribute`, `classList`, and
`closest` work the same on SVG elements, and the runtime doesn't use the properties that behave
differently, mainly `className`.

## 7. Sessions and transport

A `GET` request renders the composition to HTML, and returns it with a session token. The composition
stays alive in the `SessionRegistry`. When the WebSocket connects with that token, it takes over the
existing composition, so each page is composed once per session, not once per request.

### Reusing the markup the browser already has

When the WebSocket connects, the browser keeps the DOM it was served, instead of receiving the tree a
second time. The client walks the markup, indexes it, and tells the server that it did. The server
replies with `Ready` instead of `Reset`. Anything that changed between rendering the HTML and the
socket connecting, such as a `LaunchedEffect` that already ran or a shared store that someone else
edited, arrives as a normal patch. On the demo's shapes page, this reduces the first WebSocket
message from 3,213 bytes to 21 bytes, and the total first-load traffic from 8,520 bytes to 5,328.

The bytes aren't the main benefit. A rebuild would throw away the DOM that the browser already parsed,
laid out, and painted, along with anything that happened to the page in the meantime: focus, a text
selection, the scroll position, or an element that another script added.

Elements identify themselves with a `data-jl` attribute. Text nodes are harder. They have no
attributes, an HTML parser merges adjacent text nodes, and an empty text node produces no DOM node at
all. So the markup includes extra information that the parser would otherwise lose:

| Marker | Meaning |
|---|---|
| `data-jl-t="0:3,2:7"` | Which child indexes are text nodes, and each one's ID. |
| `<!--\|-->` | A boundary between two adjacent text children, so the parser keeps them as two nodes. |
| `<!--0-->` | An empty text child, which the client turns back into an empty text node. |
| `data-jl-raw` | Content from `unsafeInnerHtml`. It isn't part of the composition, so it isn't indexed. |

Only the first WebSocket that connects to the composition that rendered the page reuses the markup.
The server decides this, instead of relying on what the client asks for. A reconnecting browser holds
markup that the server has since changed without recording the changes, because the server stops
recording when the previous socket disconnects. A session woken from hibernation has been composed
again, with node IDs unrelated to the `data-jl` values in the page. Both receive a `Reset`.

The walk is best-effort. If anything in the markup doesn't match, such as an element without an ID
or a text child that the markers don't account for, the client abandons the walk and asks for the
full tree. So a missing marker, or a proxy that rewrote the HTML, costs only the optimization, not
correctness. `Jetlin.connect({ adopt: false })` forces the full-tree path, so one flag tells you
whether markup reuse is involved in a bug.

### Routes

A session is bound to the whole route table, not to a single view. `RouteHost` matches the current
path against the registered patterns and composes the matching view, keyed on the pattern. Navigating
between two different routes rebuilds the view. Navigating between two locations of the same route,
such as `/todo/1` to `/todo/2`, keeps the view and runs it again with the new parameters. Because the
current route is composition state, navigating is only a state change: the view is replaced inside
the live composition, and the applier records the difference. There's no page load and no new
session, and the same WebSocket carries on.

### Origins

The browser's same-origin policy doesn't apply to WebSockets, so any page on any site can open one.
The socket handler checks the `Origin` header against the request's `Host` before it looks up a
session, and refuses the connection if they don't match. `JetlinConfig.allowedOrigins` allows other
origins, for deployments where the page and the socket use different hostnames.

Sessions are cleaned up on a timer. If no WebSocket connects, or the socket disconnects and doesn't
reconnect within the grace period, the session hibernates. See [§9](#hibernation).

### Failures

Three kinds of failure can happen on an open socket, and each is handled differently.

A message that can't be parsed is dropped with a warning. Messages come from the browser, which
might not behave. Ending the session over one would let a client end its own session with a typo, and
would turn a protocol version mismatch into an outage instead of a log entry.

A handler that throws affects only that one interaction. The composition isn't affected, because the
exception came from the event handler, not from recomposition. The page is still correct, and the
session keeps working. The client receives `ServerMessage.Error(fatal = false)` and carries on.

A composable that throws ends the session. The recomposer stops permanently, so nothing the session
does afterward can succeed. The client receives `Error(fatal = true)` and reloads into a new session.
Leaving the session connected would be worse, because the page would look live but could never change
again.

`CompositionHost.isAlive` distinguishes the second case from the third, and it's the only thing that
can, because both reach the transport as an exception from `dispatch`.

The browser gets only a fixed, generic message. Exception messages often include parts of queries,
file paths, and identifiers, and none of that should go to the client. The real exception is logged
and passed to `JetlinConfig.onError`, where an application can forward it to its error reporting
service, because a log line that nobody reads isn't error handling.

On the client, the error is also dispatched as a `jetlin:error` DOM event, so the application can
show a toast or a banner. The framework doesn't decide how errors look, but it does make sure they're
noticeable, because a click that does nothing without explanation is the worst outcome.

The event is cancelable. Calling `preventDefault()` tells Jetlin that the page handles the error
itself, and on a fatal error, Jetlin skips the automatic reload. Registering a listener alone doesn't
have this effect. Many applications add a listener only to send errors to telemetry, and turning off
recovery for them without warning would be a nasty surprise. Taking over is a decision made for each
error, not a side effect of listening.

After cancelling, the page can't change anymore: the composition is gone, and the socket is closed.
That's the state that the fatal-error path normally avoids, entered on purpose. Jetlin adds the
`jl-dead` class to the body, so the page can be dimmed or covered with an overlay, and provides
`jetlin.reload()` to start over. From that point, the code that cancelled the event decides what the
user sees.

If a view throws during its initial composition, no session is created. `create` closes the partly
built view before rethrowing, so a failing page doesn't leak a dispatcher thread on every request, and
the HTTP layer returns its own error. If a session's composition fails later, the reaper closes it the
next time it runs. `hibernate` waits for the session to settle inside its `try` block, because a failed
composition reports its failure from that wait. Before that change, the sessions that most needed
releasing were the only ones that never were.

The demo's `/errors` page shows both cases side by side: a button that throws in its handler next to a
counter that keeps working, and a button that throws in the view and ends the session.

## 8. Writing views

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }

    Div({ classes("card") }) {
        H1 { Text("Count: $count") }
        Button({ classes("btn"); onClick { count++ } }) { Text("+") }
    }
}
```

The API is HTML-first. You use HTML elements, attributes, and CSS directly, so nothing needs to be
translated into another layout model, and existing style sheets work unchanged. A widget layer, such as
`Column`, `Row`, and `Card` with a `Modifier`-like API, could be added later without changing the
core, because widgets would be composables that emit HTML.

State and effects are standard Compose: `remember`, `derivedStateOf`, `LaunchedEffect`, `snapshotFlow`,
`CompositionLocal`, `key`, and coroutines.

### Routes, context, and navigation

You register views for path patterns. `Link` renders a real `<a href>`, and Jetlin intercepts clicks
on it to navigate within the session:

```kotlin
jetlin {
    head = STYLES
    attributes { call -> mapOf(CurrentUser to call.principal<User>()) }
    view("/", title = "Todos") { TodoListPage() }
    view("/todo/{id}", title = "Edit") { TodoDetailPage() }
}

@Composable
fun TodoDetailPage() {
    val todo = TodoStore.find(pathParam("id").toInt())
    val user = LocalRequest.current[CurrentUser]
    val navigator = LocalNavigator.current
    // ...
    Button({ onClick { navigator.push("/") } }) { Text("Done") }
}
```

`RequestContext` holds the path, the path parameters, the query parameters, and the headers. The
application adds its own values, such as a principal, a tenant, or a locale, through `AttributeKey`s
computed from the originating HTTP request. That avoids passing a type parameter through the whole
configuration DSL. Attributes are computed when the page is rendered, and again only when a WebSocket
wakes a hibernated session, where the principal has to be recomputed instead of trusted from a
snapshot. A socket that reconnects to a running composition keeps the existing context.

Because `Link` renders a real anchor, it also works without JavaScript. Middle-click and **Open in new
tab** behave normally, crawlers follow it, and with scripting turned off it falls back to a normal
request that starts a new session on that path.

### What survives navigation

Navigation is a single state write. `LiveView` holds the current `RequestContext` in a
`mutableStateOf`, and navigating replaces it. The composition, the recomposer, and the node tree all
stay. What's rebuilt is the matched view, because the route host wraps it in `key(pattern)`. Two
different routes must not share state, while `/todo/1` to `/todo/2` keeps the view and runs it again
with new parameters.

Because everything an application writes sits under that `key`, the router alone offers no place for
state that should survive navigation. `app { }` is a composable above the `key`:

```kotlin
jetlin {
    app { route ->
        val filter = remember { mutableStateOf("") }
        CompositionLocalProvider(LocalFilter provides filter) { Shell { route() } }
    }
    view("/") { TodoListPage() }
}
```

It's composed once per session, so `remember` in it survives every navigation, and `rememberSaved` in
it also survives hibernation. The `app { }` composable is never disposed, so its providers are still
registered when the session collects state to save. The application decides how views read that
state, usually through a `CompositionLocal` it defines. The framework provides the place to keep the
state, not its structure.

Views get their own saved state back through a `SaveableStateHolder`. Each route is composed with a
child registry, initialized from what that route saved last time, and leaving the route saves it
again. So `rememberSaved` means "survives being torn down," not only "survives hibernation," which is
what the back button needs and what the name suggests. The holder is keyed on the matched pattern, the
same key that `key(pattern)` uses, so a view's registry lives exactly as long as the view. With
different keys, restored state would be composed against an empty registry. The holder keeps the 32
most recently left routes, because the caller chooses the keys, and keeping every one for the life of
a session could leak memory.

A view's saved values are captured when its providers unregister, not only when the holder asks for
them. A view disposing its own effects is normal, and the runtime decides the order that `onDispose`
callbacks run in. Capturing values as the view is disposed means saving doesn't depend on that order.

### Forms

```kotlin
val title = rememberField(todo.title) {
    if (it.isBlank()) "A title is required" else null
}

Input({ classes("input"); bind(title) })
title.error?.let { P({ classes("error") }) { Text(it) } }
Button({ disabled(!title.isValid); onClick { save(title.value) } }) { Text("Save") }
```

The authoritative value is on the server, so `validate` can query a database or another service
without an API in between, and a submit button's `disabled` state is decided in the same place as the
rule that disables it. `touched` keeps a new form from opening full of errors: an untouched field
reports no `error`, even while `isValid` is `false`. `bind` debounces input by default, because sending
every keystroke to the server is the most common way to make this architecture feel slow.

### External data

Not everything a page shows comes from the database. A value from somewhere else, such as an HTTP
API, a queue, or a slow computation, is held in a `Fetch`, which is snapshot state that a coroutine
updates:

```kotlin
class Hub(private val client: HttpClient, private val scope: CoroutineScope) {
    private val profiles = ConcurrentHashMap<String, Fetch<Profile>>()

    fun profile(of: User): Fetch<Profile> = profiles.computeIfAbsent(of.email) { email ->
        Fetch(scope, ttl = 30.seconds) { client.profile(email) }
    }
}

@Composable
fun Status(hub: Hub, of: User) {
    when (val profile = hub.profile(of).value) {
        is Fetched.Loading -> Span { Text("…") }
        is Fetched.Failed -> Span { Text("unavailable") }
        is Fetched.Ready -> Span { Text(profile.value.status) }
    }
}
```

Reading the value subscribes the composable, the arrival recomposes every session that read it, and
composition never waits for it. A committed transaction uses the same mechanism. What `Fetch` adds is
correct handling of three details that fail silently when done wrong:

1. Reading writes no snapshot state. The in-flight marker is a plain atomic. If a pass wrote state
   that it had read, the write would invalidate the reader again on the next pass, indefinitely, and
   the session would never settle.
2. The fetch runs in a scope that the caller owns, not on the session's thread. A session composes
   on a single thread, and a network request there would stall the whole session.
3. The arrival is a single write. One response fills one object, so it causes one recomposition and
   one patch, however many fields it has.

When a value is older than its `ttl`, the next read triggers a refresh, and the old value is shown
until the new one arrives. Replacing data already on screen with a placeholder would be worse than
showing data that's a minute old. If a fetch fails and there's no earlier value, the state becomes
`Fetched.Failed`. If a refresh fails while a good value is available, the good value is kept.
`invalidate()` marks a value as stale, and leaves the fetch to the next reader. `refresh()` fetches
immediately, for callers that know someone is looking at the value.

Nothing happens by itself when a value expires. A `ttl` is the oldest value a read accepts, and a read
happens only when the composable that reads the value recomposes. A page that needs to stay current
while it's open has to ask for it, and the refreshing then lasts exactly as long as the page:

```kotlin
when (val profile = hub.profile(principal).fresh(every = 10.seconds)) { … }
```

`fresh` is Jetlin's equivalent of Android's `collectAsStateWithLifecycle`, with the composition as the
lifecycle. While the page is composed, the value is watched. When the session navigates away, closes,
or hibernates, the composition is disposed, and the watch stops. The value counts its watchers, not
each session, so ten people viewing the same dashboard share one polling loop and one request per
interval, and the requests stop when the last of them closes the tab. If two watchers ask for
different intervals, the shorter one wins.

Writes to an external system are commands, not property assignments: they take arguments, they can
fail, and they can't be batched into a snapshot. A command is an ordinary `suspend fun`, which also
keeps it out of transactions. `Db.transact` takes a block that can't suspend, because a rollback can't
undo an HTTP request, so "write the record and call the API" in one transaction doesn't compile. If
the two really have to happen atomically, record the intent in the transaction, and have a background
worker make the call.

Event handlers aren't suspending either, so a command has to be launched. `rememberAction` tracks the
resulting attempt:

```kotlin
val save = rememberAction { hub.setStatus(principal, draft.value) }

Button({ disabled(save.state is Run.Running); onClick { save() } }) { Text("Save") }
(save.state as? Run.Failed)?.let { P({ classes("error") }) { Text(it.cause.message.orEmpty()) } }
```

`Run` is one of `Idle`, `Running`, `Failed`, or `Done`. With one field instead of three, the page can
never be asked to render an impossible combination. Failures are caught inside the launched coroutine,
because an exception escaping it would cancel the composition's scope and end the session. A refused
command should produce only an error message.

`samples/teams/src/main/kotlin/jetlin/samples/teams/Hub.kt` avoids needing access control. Its profile
cache is keyed by principal, so Bob has no way to refer to a profile fetched with Alice's credential.
The cache's design answers the question that a policy would. A cache shared across principals would
need policy checks and an authority component in its key. §11 of `db-framework-plan.md` works through
that design and what it would cost.

### Drawings

```kotlin
Svg({ attr("viewBox", "0 0 220 80"); classes("chart") }) {
    SvgTitle { Text("$count readings, newest last") }
    Polyline({ attr("points", points); attr("fill", "none"); attr("stroke", "currentColor") })
    ForeignObject({ attr("x", "10"); attr("y", "0"); attr("width", "200"); attr("height", "14") }) {
        P({ classes("caption") }) { Text("HTML again, in the middle of the drawing") }
    }
}
```

A chart is made of elements, so it's updated like anything else: redrawing it is a `SetAttr` on the
`points` attribute, not a full repaint. SVG needs separate treatment because the browser doesn't create
SVG elements the normal way, and gives no error when you try: an element created as HTML instead of
SVG exists, but it's empty and draws nothing. `Svg { }` switches to SVG, and everything inside
inherits it. `ForeignObject { }` switches back to HTML. Each node tells the browser which namespace
it's in.

Watch out for two things:

- SVG is case-sensitive where HTML isn't, so write `attr("viewBox", …)` and
  `Element("linearGradient")`. The browser ignores lowercase spellings without an error.
- Paint and geometry are attributes, not properties, so use `attr("fill", …)`, never `prop`.

`SvgText` and `SvgTitle` have a prefix, because `Text` already emits a text node, and `title` means
something different in HTML. The others, such as `Path`, `Circle`, `Rect`, `Line`, `Polyline`,
`Polygon`, `G`, `Defs`, and `LinearGradient`, are named after their tags. A shape composed outside an
`Svg { }` is rejected, instead of rendering nothing without an error.

### Latency and typing

Every interaction requires a round trip to the server, so two things need care.

Patches set properties and attributes on existing nodes. They never replace a container's
`innerHTML`. DOM that isn't changed stays untouched, which keeps focus, selection, and scroll position
across an update.

For an input the user is typing in, that isn't enough on its own. Each patch carries `ack`, the
highest client event sequence number it includes. The client tracks the last sequence number it sent
from each node. A `value` or `checked` update older than that describes the state before the user's
latest keystroke, so the client drops it instead of applying it. Listeners can also declare debounce
and throttle settings, which the client applies before sending an event.

`value` is set as a DOM property, not an attribute, because the attribute only sets the control's
initial value, and the browser ignores it after the user has interacted with the control.

## 9. Memory, hibernation, and back-pressure

Because UI state is held on the server, memory use grows with the number of connected users, and the
memory cost of each session limits how many sessions one node can hold.

These figures come from `./gradlew :samples:demo:benchmark`, with 1,000 concurrent sessions of a
113-node view:

```
live:               136 kB per session (133 MB total)
hibernated:         364 bytes per session (356 kB total)
```

### Hibernation

A session goes through three states:

- Live: the composition is in memory, and a WebSocket is connected.
- Orphaned: the socket has disconnected, but the composition is kept for a grace period, because most
  disconnections are brief, such as a train going through a tunnel or a laptop going to sleep, and
  reconnecting to a running composition is instant and loses nothing.
- Hibernated: the grace period has expired, so the values declared with `rememberSaved` are written to
  a `SessionStore`, and the composition is destroyed, which releases its slot table, node tree, and
  coroutines.

That's where the difference of roughly 400 times in the figures above comes from: an idle session no
longer costs what a live one does, only what its saved state costs.

The code makes the difference explicit. `remember` is temporary and doesn't survive hibernation.
`rememberSaved` does. Keeping this explicit means the saved state is small by default, and developers
choose what's worth keeping, instead of discovering it later.

```kotlin
val draft = rememberSavedField("", key = "draft")   // Survives, because the user typed it.
val expanded = remember { mutableStateOf(false) }   // Doesn't, because recomputing it costs nothing.
```

Sessions are stored through a `SessionStore`. The default in-memory implementation covers a dropped
connection or a closed laptop lid, but not a server restart.

Waking a session transfers ownership of it, so the interface provides `take`, which removes and returns
the session atomically, instead of separate load and delete operations. Two sockets can present the
same token at the same time, for example a reconnect racing a retry, or two tabs restored from the same
saved page, and exactly one of them must end up with the session. Otherwise, both build a composition
from the same snapshot, and one of them is left live and connected, but invisible to the reaper that
should clean it up. `SessionStoreContract` includes a test that fails for any implementation that
reads and deletes in separate steps.

Two more details:

- Keys: `rememberSaved` derives a key from the composable's position, which tells apart saved values in
  different composables and, since Compose 1.12, two calls next to each other in the same composable.
  A position still isn't an identity in a loop over data that can be reordered, so pass explicit keys
  there. Jetlin detects a collision when it captures state and reports it, instead of letting one
  value overwrite the other without an error.
- Old snapshots: stored state outlives deployments, so a snapshot written by an older version of the
  code is normal. A value that can no longer be deserialized falls back to its initial value, instead
  of failing the session.

When a session wakes, the browser's current URL takes priority over the stored location, because the
user might have pressed the back button while disconnected. If a session has no saved state, nothing
is stored. There's nothing to restore, so the client is told directly, and starts a new session.

### Running on more than one node

Jetlin assumes a single node. A shared session store would be the obvious next step, and would let
sessions survive a restart. But on its own, it wouldn't make Jetlin work across several nodes, which is
worth stating clearly, because it's easy to assume otherwise.

A store only ever holds hibernated sessions. A session exists only on its node during three periods,
wherever snapshots are stored: while a socket is connected, between rendering the page and the socket
connecting, and during the disconnect grace period, when the composition is deliberately kept. A
request that reaches a different node during any of these finds nothing.

Handling those periods requires a policy decision, not more storage. The options are sticky routing;
hibernating immediately on disconnect, and paying for a new render after every brief interruption; or
a handoff protocol, in which the receiving node asks the owning node to release the session. Each has
different trade-offs, and one has to be chosen before a shared store is useful.

### Limits

There are two limits. Both degrade service instead of defending against attacks. The goal is that when
one client hits a limit, everyone else is unaffected.

#### Sessions

`JetlinConfig.maxSessions` limits how many sessions are held at once. Every page render creates a
session, whether or not a WebSocket ever connects to claim it, and unclaimed sessions are removed only
when the handoff timeout expires.

Without a limit, memory settles at roughly `arrival rate × handoff timeout × cost per session`. It
levels off instead of growing forever, because the reaper removes sessions at the same rate. At about
136 kB per session and a 30-second timeout, 100 requests per second levels off at about 400 MB, and
1,000 requests per second at 4 GB, which is enough to crash the process. The limit turns an
unaffordable plateau into refusals the server can afford: over the limit, a page render gets a `503`
response with a `Retry-After` header.

Reconnects are deliberately not limited. A client that's reconnecting already had a session and isn't
the cause of the pressure. Refusing it to make room for new visitors would be the wrong trade-off.

The limit is approximate. Two requests that arrive at the same moment can both see room and both
create a session, so the real maximum is `maxSessions` plus the requests in progress. Making it exact
would require putting every page render behind a lock, to prevent a small, bounded overshoot that does
no harm.

#### Events

Each connection has a token bucket. `eventsPerSecond` sets the sustained rate, and `eventBurst` sets
how far a connection can exceed it briefly. It's a token bucket instead of a fixed window, because real
use comes in bursts: a user fills in a form quickly, then does nothing for ten seconds. A window that
could absorb the burst would have to be so large that it stopped protecting anything.

The bucket is checked before the message is parsed, so a flood costs a clock read per message, not a
JSON decode. Messages over the limit are dropped instead of queued, because queuing them would only
move the flood into memory. The client is notified once per episode with a non-fatal error, because a
page that ignores the first warning will ignore the rest too.

This isn't an attack defense, and shouldn't be described as one. The message loop was never
unbounded: `dispatch` suspends until recomposition is applied, so a client could never queue unlimited
work in the composition, and unread messages back up in socket buffers until TCP flow control stops
the sender. The bucket also doesn't stop a determined attacker. With the session limit at ten
thousand, an attacker can open many sessions and flood each one at the allowed rate. If a
recomposition takes a millisecond, that's still twenty sessions' worth of work per core.

What it does protect against is the common case: a client that has gone wrong, such as application
JavaScript stuck in a loop, a `ClientComponent` that sends an event on every animation frame, a retry
with no backoff, or an `onInput` connected to something that fires continuously. None of these are
attacks, and all of them happen. Without a limit, one user's broken page takes a share of the server
until someone notices a latency graph. The limit also turns "a session costs an unbounded amount" into
a number you can multiply by the expected number of users, which is what makes capacity planning
possible.

Real protection against deliberate abuse belongs in front of the application, in nginx or a load
balancer, which is also where you configure limits for each address.

#### Logging

Both limits are logged, so that someone finds out when they're hit. Reaching the session limit and
throttling a connection are both logged at `WARN`. Both can happen at request rate, so they go through
a `LogThrottle` that writes at most one line per minute, including a count of the events it covers.
The throttling log line names the page and the first eight characters of the session token. The token
is truncated deliberately: it's a bearer credential, and a leaked log shouldn't allow a session
takeover. A connection that dropped messages also logs its total when it closes.
`SessionRegistry.rejectedCount` and `liveCount` expose the same information to monitoring.

### Back-pressure

A composition outlives its socket, so a session with a running timer keeps producing updates whether
or not anyone is connected. Two rules apply:

- When no client is connected, changes aren't recorded at all. The next client to connect receives
  the whole tree anyway, so recording the changes in between would use memory to describe a page that
  nobody will see. The composition keeps running, and only the recording stops.
- When a client is connected but falls too far behind, the buffer is discarded once it exceeds
  `maxBufferedOps`, which is 10,000 by default, and the next message is the full tree instead of a
  patch. Sending the tree again costs more bytes once, but it limits how much memory one slow client
  can make the server hold. The session falls back to coarser updates instead of failing.

## 10. Design decisions

### Sessions are stateful, and hibernate when idle

The composition lives in server memory while the user is connected. That makes fine-grained updates,
long-running effects, and server-initiated updates work naturally, at the cost of memory for each user
and the need to handle disconnects. Hibernation complements this: after a disconnect, the session's
saved state is captured, the composition is dropped, and it's restored on reconnect. A shared session
store would let a session wake on another node, or after a rolling deployment. See
[Running on more than one node](#running-on-more-than-one-node) for what else that needs.

### The API is HTML-first

You use elements and CSS directly, instead of a widget vocabulary that's translated into CSS. This
keeps the API small and predictable, and leaves a higher-level widget layer as an option instead of a
requirement.

### Ktor first, with a portable core

`LiveView` knows nothing about WebSockets or Ktor, and tests can drive it directly without a server.
Adapters for other servers can be added alongside.

### The client is written in TypeScript

It's about 650 lines of DOM code, plus comments, and it ships as 8.6 kB, with no runtime library of
its own.

## 11. Verification

To run the tests, the benchmark, and the demo, use these commands:

```bash
./gradlew test                      # Unit tests, which assert exact op streams.
./gradlew :samples:demo:benchmark   # Retained heap per session.
./gradlew :samples:demo:run         # http://localhost:8080

cd e2e && npm install && npx playwright test
```

The browser tests cover the first paint with JavaScript blocked, deep links rendered on the server,
targeted patching, adding, reordering, and removing keyed list items, updates that start on the server,
typing while the server sends unrelated updates, navigation without a page load, the back and forward
buttons, server-side validation blocking a submit, and reconnecting with state preserved. The README
lists the rest.

The browser tests found two bugs that code review had missed:

- Events fired before the socket opened were dropped. The first paint is interactive HTML that exists
  before the WebSocket finishes connecting, so a fast click can happen in that window. The fix is a
  bounded outbox that's flushed when the socket opens.
- Ops buffered while disconnected were applied on top of a new tree. A composition keeps running with
  no client connected, as the sample's clock does, so by the time the client reconnected, the buffer
  described changes to a tree the new client had never seen. The fix is to clear the buffer when
  sending the full tree.

## 12. Testing application views

`jetlin-testing` runs a view without a browser, a server, or a WebSocket, so an application's tests
can describe behavior instead of protocol details.

```kotlin
@Test
fun `clearing the title blocks the save`(): Unit = runViewTest(url = "/todo/1") {
    setContent(route = "/todo/{id}") { TodoDetailPage() }

    onNode(hasTestTag("title")).type("")

    onNode(hasTestTag("title-error")).assertText("A title is required")
    onNode(hasTestTag("save")).assertDisabled()
}
```

### Naming nodes without adding markup

`AttrsScope.testTag` gives an element a name for tests. The name is stored on the server-side node
and, unlike a `data-` attribute, is never serialized. It doesn't appear in the HTML, isn't included in
`NodeSpec`, and never produces a `SetAttr`. The page that users receive contains nothing that exists
only for tests.

Browser tests are the exception, because Playwright can select elements only by what's in the DOM.
`JetlinConfig.exposeTestTags` also writes test tags as `data-test` attributes. It adds them to the
ordinary attribute map during composition, instead of having the serializer add them. That way,
serialization, `NodeSpec`, attribute comparison, and the client's markup reuse all keep working
unchanged: an exposed tag appears on nodes inserted long after the first paint, and is patched normally
when it changes. Adding the attribute only at render time would have left an attribute in the DOM that
the server didn't know about, and no attribute at all on nodes added later through `Op.Insert`.

### Interactions that the browser handles

Everything so far involves a round trip, which is right whenever the server cares about the result,
because it owns the data, the validation, and the routing. The server doesn't care whether a menu is
open, and a network round trip to find out only adds latency.

`AttrsScope.clientOnly` declares what the browser does when an event fires:

```kotlin
Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
```

The commands are a fixed set: toggle, add, and remove a class, focus, and blur. They aren't scripts,
and that limit is deliberate. Arbitrary client code would be a second application to keep consistent
with the first, which is what this framework avoids. A fixed set of commands can't grow into one.

No new machinery was needed. `ListenerSpec` already goes to the browser, in `data-jl-on` in the first
paint and as `Op.Listen` afterward, so the commands travel with it, and the page is interactive before
a WebSocket exists. `ListenerSpec.notify` says whether the server wants to hear about the event. It's
derived from whether a handler was declared, not set by the developer, so the two can't disagree. An
element with commands and no handler acts locally and sends nothing. An element with both acts
immediately and also notifies the server, which is how a button can show a spinner before the work it
starts has happened.

The browser owns the classes changed this way. If the composition also sets `class` on the same
element, the composition wins, and the next patch overwrites whatever the browser toggled.

A test without a browser can check that the commands were declared, and declared exactly:
`assertClientCommands` checks the list. Only a browser test can check that the class toggles, and
`jetlin-testing` says so: clicking a client-only node fails with an explanation, instead of doing
nothing.

### Elements the composition doesn't manage

Some things can't be produced from a server-side tree, such as a map, a chart, or a rich-text editor.
`ClientComponent` creates the element, and hands it to an implementation that the application
registered in its own JavaScript bundle.

```kotlin
val body = rememberSavedField(note.body, key = "body")

ClientComponent(
    name = "editor",
    props = buildJsonObject { put("content", body.value) },
    onEvent = { event, payload ->
        if (event == "changed") body.edit(payload["html"]!!.jsonPrimitive.content)
    },
)
```

```js
Jetlin.clientComponent("editor", {
  mount(element, props, push) { … },
  update(element, props, handle) { … },
  unmount(element, handle) { … },
});
```

Props go down, events come up, and the component's DOM can be discarded at any time. Nothing inside
it survives a reconnect that required sending the tree again: the element is rebuilt and mounted again
from the props the server still holds. That's the same trade-off `remember` makes, and it's why Jetlin
needs no mechanism for keeping a subtree alive through a rebuild. The design avoids the hard part of
the problem.

One rule makes this safe: nothing the user creates may exist only inside a component. A map's pan and
zoom can be recreated, and nobody minds, but text someone typed can't. So the text goes to the server
and is held in a `rememberSaved` field, which then survives reconnects and hibernation through existing
mechanisms.

The implementation needed very little new code. `data-jl-component` and `data-jl-props` are ordinary
attributes, so they go through the HTML serializer, `NodeSpec`, and `Op.SetAttr` unchanged. New props
are one attribute write, and a component inserted after the first paint arrives complete. The client
hooks into four existing places: `build` and `adoptElement` mount components, `forget` unmounts them,
and `reset` unmounts everything before `replaceChildren`. Events from the component use the ordinary
event path, with their payload in `EventPayload.data`, which the framework doesn't interpret.

There are two deliberate restrictions:

- A component can't have composable children. Its contents belong to the implementation, and Jetlin
  records its logical children as empty and never patches inside it. That also means nothing renders
  there without JavaScript.
- `name` is a key into a registry that the application filled, never code. What's sent over the wire
  can name an implementation, but can never be one.

Unmounting is required. A widget that's never told it's being removed keeps its listeners, timers, and
observers, and a list that re-renders leaks a set of them on every render. `JetlinConfig.clientSetup`
is where an application loads its registrations. It's added after the runtime and before the session
connects, because a component whose implementation isn't registered by the time the markup is
processed renders nothing.

`jetlin-testing` covers the server side of this: `hasClientComponent` finds a component, `assertProps`
checks the props the server sent, and `pushFromClient` sends an event from the component, so you can
test the server's response without a browser. Browser tests cover what the implementation draws,
including a check that mounts and unmounts balance.

### Identifying nodes by location

You can limit queries to a subtree. That lets a test refer to "the up button in this row," instead of
an index into every button on the page:

```kotlin
within(onAll(hasTestTag("todo"))[2]) { onNode(hasText("up")).click() }
```

The scope is a query, not a resolved node, so it's resolved again each time it's used, and scopes can
be nested. `Update.assertOnlyWithin` works the same way: it asserts that every change was inside the
given subtrees.

Matchers find nodes, not IDs, and an interaction names a node and an event. There's no geometry or hit
testing, because that isn't how input reaches a Jetlin view. Events bubble to the nearest ancestor with
a listener, as in a browser, and an interaction on a node with no listener fails, instead of doing
nothing.

`jetlin-testing` supports two kinds of test that client-side test kits have no equivalent for:

- How much of the page changed. `recordUpdate { }` reports which nodes an interaction changed, so a
  test can assert that checking one checkbox patched one row and left the rest of the list alone. This
  catches a class of bug that no other kind of assertion can see: keying a list by a value that
  changes renders exactly the same HTML, but sends the whole list again on every edit. Breaking
  `key(todo.id)` in the sample leaves 15 of its 16 tests passing, and the one that fails is this one.
- Whether the right state was declared as saved. `hibernateAndRestore()` takes the session through
  the hibernation cycle that [§9](#hibernation) describes, so a test can check that a half-typed draft
  survives and that temporary state doesn't.

A view reached through a route declares its pattern, as in `setContent(route = "/todo/{id}")`. Path
parameters are extracted by matching the session's URL against the pattern, so you write the ID only
once. Tests that navigate between views use `setRoutes` instead, which follows the session from view to
view.

The matchers hide places where HTML is inconsistent about where state is stored. `hasValue` reads the
`value` property, which is what `bind` sets, while `isDisabled` reads the `disabled` attribute.
Checking the wrong one is exactly the mistake these matchers prevent.

The module doesn't depend on a test framework. Its assertions throw `AssertionError` directly, so it
works with whatever test runner the project already uses.

## 13. What's missing

The gaps are grouped by what each one prevents, not by how much work it is.

### Blocks a production deployment

- The limits are global, not per client. `maxSessions` keeps the process from running out of memory,
  and the token bucket for each connection keeps a misbehaving page from using a large share of the
  server. Neither defends against a deliberate attacker: one source can fill the session limit so that
  everyone else is refused, and can flood many sessions at the allowed rate. Limits for each address
  require deciding whether to trust `X-Forwarded-For`, which is a security question of its own,
  because behind a proxy, every request otherwise appears to come from the proxy. Until then, rate
  limiting belongs in front of the application, in nginx or a load balancer, which is where limits for
  each address are usually configured anyway.
- Nothing is published. There are no Maven coordinates, no versioning scheme, and no
  binary-compatibility checks. Nothing outside this repository can depend on Jetlin, which matters
  more than any feature below.
- CI isn't running. The pipeline is written and kept in `ci/github-actions.yml`. Enabling it requires
  someone with the `workflow` scope to move it to `.github/workflows/`.
- No telemetry. Nothing reports session counts, patch sizes, recomposition time, hibernation and wake
  rates, or how often a buffer overflowed. All of this is available inside `HtmlOwner` and
  `CompositionHost`, but none of it is exported.

### Blocks a real application

- Storage exists, with known gaps. `jetlin-db` stores entities in SQLite, with access control for
  each record, generated migrations, and reactive revocation. [`db.md`](db.md) §8 lists what it's
  missing. The four most important gaps: holding a record reference grants access to it, visibility
  changes through relations aren't propagated to pages already open, SQLite provides no second layer
  of enforcement below the policies, and there are no indexes.
- File uploads. A WebSocket isn't suited to large binary transfers, so uploads need a separate HTTP
  endpoint, progress reporting, and a way to associate the upload with the session that started it.
- Few event types. There are six handlers (`onClick`, `onInput`, `onChange`, `onChecked`, `onSubmit`,
  and `onKeyDown`) and four kinds of extracted data (value, checked, key, and form). Missing: focus
  and blur, mouse and pointer events, key-up, paste, radio groups, multi-select, and anything that
  reads `dataset` or coordinates. The mechanism is sound, and the vocabulary is only small, so this is
  a matter of adding events, not redesigning. An element can't declare two handlers for the same DOM
  event, which is why `onChange` and `onChecked`, which both listen for `change`, are rejected when
  used together instead of being merged.
- Few elements have composables. There's no `Dl`, `Fieldset`, `Canvas`, `Iframe`, or any media
  element. `Element(tag)` is public, so nothing is impossible, but the convenience functions are
  incomplete. `Dialog` exists, but only opens inline, because `showModal()` is a DOM method, and
  there's no op for calling methods. SVG support covers what a chart needs and no more: there's no
  `symbol` or `use`, no markers, and no animation elements, although `Element(tag)` can create all of
  them inside an `Svg { }`. MathML, the other language that browsers parse, isn't supported. Adding it
  would take one more `Namespace` entry.
- Navigation isn't accessible. A client-side route change replaces the view without moving focus or
  announcing anything to a screen reader, and the scroll position isn't restored on back or forward.
  These are common gaps in this kind of framework, and common complaints about it.

### Blocks scaling beyond one machine

- Running on more than one node. `SessionStore` is designed to allow a shared implementation, and
  `SessionStoreContract` defines its behavior, so a store backed by Redis or a database is mostly a
  matter of passing that test suite. The real obstacle isn't storage, but the policy for the periods
  when a session lives on one node, which [§9](#running-on-more-than-one-node) describes, and which
  has to be decided first. Two changes should come with it: a generation counter on snapshots, so two
  processes can't restore each other's state, and moving `SessionStore` out of `jetlin-server-ktor`,
  so implementations don't have to depend on Ktor.
- The in-memory record graph belongs to one process. An application that uses `jetlin-db` keeps its
  data in memory as live Compose state, so a second node would have its own copy: two graphs accepting
  writes to one file, each recomposing only its own sessions. This is a bigger obstacle to running on
  several nodes than `SessionStore`. A shared session store is an implementation task, but this is a
  design question, with answers such as one writer with followers, or a shared log that every node
  applies. `jetlin-db` detects the situation instead of tolerating it: if `PRAGMA data_version` changes
  unexpectedly, the write is refused (see [`db.md`](db.md) §6), so the problem shows up as an error,
  instead of the graphs drifting apart without anyone noticing.

### Known and accepted

- The `ack` can be set slightly too early when a background patch overlaps an incoming event, which
  could let one outdated property write through. The fix is to capture the ack when the buffer is
  drained.
- Client components don't survive a reset. A `ClientComponent` is rebuilt and mounted again when a
  reconnect requires sending the tree again. That's the right default, and the props-down contract
  makes it invisible for anything whose state can be recreated. It's still wrong for a component that
  holds something expensive to rebuild, such as a large canvas or a complex widget in the middle of an
  animation. Doing better would mean preserving a DOM subtree across a full rebuild, which requires
  identity to survive `container.replaceChildren()`, and reconciling a tree the server has stopped
  tracking. That's worth doing only once the current behavior causes a real problem.
- `clientOnly` can target only the element itself or an ancestor, by class. There's no targeting of
  siblings, and no node references. Targeting a sibling requires a handle to a node that might not
  have been composed yet, which is a design problem, not only an implementation task.
- The session token is a bearer token. Anyone who has it can connect to the session. It's generated
  with `SecureRandom`, never reused, and appears only in the page it belongs to. But it isn't bound to
  a cookie or a principal, so a token leaked through a referrer header or a log allows a session
  takeover. Binding it to the request that created it is the obvious improvement.

### Wanted, but not urgent

- Streaming the initial HTML instead of rendering it all at once.
- A Spring Boot adapter.
- Merge policies like `phx-update` for subtrees that third-party code manages.
- Managing more of `<head>` than `<title>`, such as meta and Open Graph tags for each route.
- A development-mode overlay for the errors that [§7](#failures) describes.

## 14. Notes and risks

### Compose runtime API changes

`Applier` and `Recomposer` are stable, but running the runtime outside a UI toolkit isn't an officially
supported use case. Pinning versions, keeping the API surface Jetlin depends on small, and having tests
that would catch behavior changes all reduce the risk.

### Dependency availability

The build originally pinned Compose Multiplatform 1.5.12, the newest release that resolved entirely
from Maven Central. From 1.6 onward, the desktop runtime pulls androidx artifacts that are published
only to Google's Maven repository, which the development environment couldn't reach. Nothing here
depends on newer features: `Applier`, `Composition`, `Recomposer`, and the snapshot system are stable
across these versions, so upgrading was a one-line change. The build now uses Compose runtime 1.12.0.
See the decision log in `db-framework-plan.md` §13.

### Related work

[Molecule](https://github.com/cashapp/molecule) and [Mosaic](https://github.com/JakeWharton/mosaic)
both run the Compose runtime outside Android, and were useful references for the frame clock and
snapshot handling. [Redwood](https://github.com/cashapp/redwood) sends Compose tree changes over a wire
protocol to native hosts. [Kilua](https://github.com/rjaros/kilua), Kobweb, and Compose HTML drive a
DOM from the Compose runtime in the browser. JetBrains is
[exploring Compose HTML for server-side rendering](https://blog.jetbrains.com/kotlin/2026/08/exploring-compose-html-for-server-side-rendering/).
That work is complementary: it targets the first paint, while Jetlin targets what happens after it.
Keeping Jetlin's HTML DSL similar in shape leaves room for the two to work together.
