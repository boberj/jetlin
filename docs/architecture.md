# Jetlin architecture

A server-driven UI framework for Kotlin. You write interactive web UI as `@Composable` functions
that run on the server; the browser gets HTML plus a small runtime that applies the DOM changes the
server sends and reports events back.

Phoenix LiveView and Livewire were the inspirations for the idea.

Status: **early**. The core is built and tested end to end in a real browser, with routing, request
context, navigation and forms on top of it. Section 12 lists what is designed but not yet built.

---

## 1. How it works

A composition runs on the server for as long as a user's session lasts. When state in that
composition changes, the Compose runtime recomposes the affected parts and updates its node tree.
Jetlin's node tree is a virtual DOM, and every edit the runtime makes to it is recorded as an
operation. Those operations are the wire protocol.

```
 user clicks                                                    DOM updated
      │                                                               ▲
      ▼                                                               │
 event { node, event, seq }                          patch { rev, ack, ops }
      │                                                               │
      └──► handler lambda ──► state changes ──► recomposition ──► applier records ops
```

The chain has four links:

1. **An event arrives** naming a node id and an event type. The server looks up the handler lambda
   it is holding for that pair and calls it inside a mutable snapshot.
2. **State changes.** Compose tracks which composables read which state, so it knows exactly which
   ones are now out of date.
3. **Recomposition** re-runs only those composables. Everything else is skipped.
4. **The applier records ops.** As the runtime edits the node tree, each insert, remove, move,
   attribute write and text write is appended to a buffer. The buffer is drained once per
   recomposition pass and sent as a single patch.

Nothing in that chain compares two versions of a page. The runtime already knows what changed
because it tracked the reads, so the ops describe changes rather than being derived from them. A
counter increment produces one `SetText`; reordering a keyed list produces `Move` ops and leaves the
nodes themselves alone.

The same chain runs when nothing came from the browser at all. A coroutine that writes state — a
timer, a database subscription, a message from another user's session — invalidates the composables
that read it, and a patch follows. Sending updates to a connected client needs no separate API.

---

## 2. Modules

```
┌─ Browser ──────────────────────────────────────────────────────────────────┐
│  jetlin.js (4.9 kB minified)                                               │
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

`jetlin-protocol` sits alongside as the shared vocabulary of ops and messages.

---

## 3. Running a composition on a server

Compose is normally started by a UI toolkit. `CompositionHost` supplies the same pieces without one:
a `Recomposer` to schedule work, a `Composition` bound to an `Applier` that materializes the tree,
and a frame clock to pace it. The runtime does not care that the tree is a virtual DOM rather than a
set of views.

Three details matter.

**One thread per session.** Each session gets `Dispatchers.Default.limitedParallelism(1)`. Event
handling, recomposition and patch draining all run on it, so they cannot interleave. That removes a
class of races without locking, and gives each session natural back-pressure.

**The mutable snapshot is the batching unit.** `transact { }` wraps a handler in
`Snapshot.withMutableSnapshot`. However many state objects the handler writes, the runtime sees one
apply notification, performs one recomposition pass, and produces one patch.

**Global writes need a pump.** State written outside a composition lands in the global snapshot and
stays invisible to every recomposer until `Snapshot.sendApplyNotifications()` is called. Nothing
calls it automatically outside a UI toolkit, and the failure is silent — background state changes
would simply never recompose. `GlobalSnapshotManager` registers a global write observer and turns
those writes into notifications, conflated so a burst wakes recomposers once.

`FramePolicy` decides how quickly state changes become patches. `Immediate` recomposes as soon as
there is work. `Paced(interval)` caps the rate, which matters for sessions fed by a high-frequency
server-side source where a client cannot usefully consume every change. One frame produces at most
one patch message.

> Worth recording: when a composable throws, the recomposer ends up `Inactive`, not `ShutDown`.
> Recomposer state alone therefore cannot distinguish "died" from "not started yet", and waiting on
> it hangs forever. `awaitIdle()` races the idle wait against the runner job instead.

---

## 4. The virtual DOM

`ElementNode` and `TextNode` form the server-side tree. `HtmlApplier` extends `AbstractApplier` and
records each edit the runtime makes as an op.

Two mechanisms carry most of the weight.

**Attachment gating.** A node only emits ops once it is reachable from the root. Compose builds
subtrees bottom-up, filling in a node's children before parenting it, so without gating a new
subtree would produce a create-configure-parent stream of dozens of ops describing nodes the client
cannot see yet. Gating lets the whole subtree ship as one `Insert` at the moment it becomes visible.

**Data and identity are stored separately.** The element's `update` block makes two calls:

```kotlin
set(data)     { applyData(it) }      // attributes, properties, listener specs → compared, may emit ops
set(handlers) { this.handlers = it }  // lambdas → always stored, never transmitted
```

Handler lambdas get a fresh identity on every recomposition. If they were part of the compared
value, every element would look different from its previous self and nothing would ever be skipped.
If they were left out of storage entirely, a handler would go on capturing values from an old pass.
Splitting the two solves both: the comparison sees only data, and the lambdas are refreshed each
time.

**Text is a node, not a string.** There is no point at which a value is spliced into markup, so
there is nothing to escape and nothing to forget to escape. `AttrsScope.unsafeInnerHtml()` is the
deliberate opt-out, for content that is already HTML and already trusted; it rides the existing
`SetProp` path as an `innerHTML` write, and an element that uses it may not also have composable
children, since the raw markup and the child nodes would overwrite each other.

---

## 5. Protocol

Server → client:

| Op | Meaning |
|---|---|
| `ins(parent, index, node)` | insert a complete subtree |
| `rm(parent, index, count)` | remove children |
| `mv(parent, from, to, count)` | reorder |
| `attr(id, name, value)` | set attribute; `null` removes |
| `prop(id, name, value)` | set DOM property (`value`, `checked`) |
| `text(id, text)` | set text node content |
| `on(id, event, spec)` / `off(id, event)` | listener registration |

Messages: `patch{rev, ack, ops}`, `reset{rev, children}` (a full tree, sent when a client attaches
or rejoins), `nav{url, replace, title}`, `error{message, fatal}`. Client → server: `hello{token}`,
`event{node, event, seq, payload}` and `nav{url}` for back/forward.

Navigations travel on the same channel as patches, emitted after the patch that rendered the
destination, so the address bar can never run ahead of the content on screen.

`ListenerSpec` tells the client what to extract from the DOM event (`value`, `checked`, `key`,
`form`) and how to rate-limit it (`debounceMs`, `throttleMs`, `preventDefault`, `stopPropagation`).
Handlers stay on the server; the client only knows that a node listens for an event.

Encoding is JSON via kotlinx.serialization with a `t` discriminator. A compact positional encoding
or CBOR would be a drop-in change; readable frames are more useful at this stage.

---

## 6. The browser runtime

4.9 kB minified, no dependencies. It keeps three things: `id → Node`, a mirrored array of logical
children per element, and the listener specs.

The child array exists because the DOM's own `childNodes` cannot be trusted for indexing — browsers
merge adjacent text nodes, and third-party scripts insert siblings. Maintaining our own array means
an index in an op means exactly what the server meant by it.

`mv` has to mirror the server-side move semantics exactly, including the destination adjustment when
items shift left. A divergence there would reorder lists differently on each side, silently.

Events use one capture-phase listener per event type on the container. Capture rather than bubble,
so that events which do not bubble (`focus`, `blur`) still reach the delegate, and so one
registration survives any amount of subtree churn.

---

## 7. Sessions and transport

A `GET` renders the composition to HTML and returns it with a session token. The composition stays
alive in `SessionRegistry`; when the WebSocket connects with that token it adopts the composition
that is already there, so a page is composed once per session rather than once per request.

A session is bound to the **whole route table**, not to one view. `RouteHost` resolves the current
path against the registered patterns and composes whatever matched, keyed on the pattern, so moving
between two routes rebuilds while moving between two instances of one route (`/todo/1` to `/todo/2`)
keeps the view and re-runs it with new parameters. Because the route is composition state,
navigating is just a state change: the view swaps inside the live composition and the applier records
the difference. No page load, no new session, same socket.

The browser's same-origin policy does not apply to WebSockets — any page on any site can open one —
so the socket handler checks `Origin` against the request's `Host` before looking up a session, and
refuses the connection otherwise. `JetlinConfig.allowedOrigins` widens this for deployments where the
page and the socket have different hostnames.

Sessions are reaped on a timer. If no socket ever arrives, or one goes away and does not return
within the grace period, the composition is closed and its memory released.

---

## 8. Authoring

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

The API is HTML-first — elements, attributes and CSS — so nothing has to be translated into a
different layout model and existing stylesheets work as they are. A widget layer (`Column`, `Row`,
`Card` over a `Modifier`-like API) could sit on top later without changing the core, since widgets
would just be composables that emit HTML.

State and effects are ordinary Compose: `remember`, `derivedStateOf`, `LaunchedEffect`,
`snapshotFlow`, `CompositionLocal`, `key`, and coroutines throughout.

### Routes, context and navigation

Views are registered against path patterns, and a `Link` renders a real `<a href>` whose click is
intercepted into a live navigation:

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
    ...
    Button({ onClick { navigator.push("/") } }) { Text("Done") }
}
```

`RequestContext` carries the path, path parameters, query and headers. Application-specific values —
a principal, a tenant, a locale — enter through `AttributeKey`, computed once per session from the
originating HTTP call, which avoids threading a type parameter through the whole configuration DSL.

Because `Link` is a real anchor, it works without JavaScript: middle-click and "open in new tab"
behave normally, crawlers follow it, and with scripting disabled it falls back to a plain request
that starts a fresh session on that path.

### Forms

```kotlin
val title = rememberField(todo.title) {
    if (it.isBlank()) "A title is required" else null
}

Input({ classes("input"); bind(title) })
title.error?.let { P({ classes("error") }) { Text(it) } }
Button({ disabled(!title.isValid); onClick { save(title.value) } }) { Text("Save") }
```

The authoritative value lives on the server, so `validate` can consult a database or another service
without an API in between, and a submit button's disabled state is decided in the same place as the
rule that disables it. `touched` keeps a fresh form from opening covered in errors: an untouched
field reports no `error` even while `isValid` is false. `bind` debounces by default, because a field
that round-trips on every keystroke is the usual way this architecture is made to feel slow.

### Interaction latency and typing

Every interaction is a round trip, so two things need care.

Patches set properties and attributes on existing nodes; they never rewrite a container's
`innerHTML`. Untouched DOM stays untouched, which is what keeps focus, selection and scroll position
intact across an update.

For inputs the user is actively editing, that is not sufficient on its own. Each patch carries
`ack`, the highest client event sequence folded into it. The client tracks the last sequence it sent
from each node; a `value` or `checked` write older than that describes a state from before the
user's most recent keystroke, so it is dropped rather than applied. Listeners can also declare
debounce and throttle, enforced on the client before an event is sent at all.

`value` is set as a DOM property rather than an attribute, because the attribute only supplies the
control's initial value and is ignored once the user has interacted with it.

---

## 9. Memory and back-pressure

Holding UI state on the server means memory scales with connected users, so per-session cost sets
the ceiling on how many sessions a node can carry.

A composition outlives its socket, which is what lets a reconnecting user find their session where
they left it. That also means a session with a running timer or a subscription to a shared store
keeps producing updates whether or not anyone is listening, and those updates must not accumulate.
Two limits apply:

- **With no client attached, edits are not recorded at all.** Whoever connects next is sent the whole
  tree anyway, so writing down the intervening edits would grow memory to describe a page nobody will
  be shown. The composition keeps running; only the recording stops.
- **With a client attached but too far behind, the buffer is dropped** once it passes
  `maxBufferedOps` (10,000 by default) and the next message is a full snapshot instead of a patch.
  Resending the tree costs more bytes once, but it bounds what a single slow reader can make the
  server hold. The session degrades to coarser updates rather than to an outage.

Measured with `./gradlew :samples:demo:benchmark` — 1000 concurrent sessions of a 111-node view:

```
sessions:           1000
nodes per session:  111
per session:        134 kB
total:              131 MB
```

At that rate 10k sessions is roughly 1.3 GB. The benchmark is in the walking skeleton rather than a
later performance pass because this number determines whether the approach works at all.

---

## 10. Design decisions

**Sessions are stateful, with hibernation planned.** The composition lives in server memory while
the user is connected. Keeping it means fine-grained updates, long-lived effects and server-driven
updates all work naturally, at the cost of memory per user and a need to handle disconnects. The
planned complement is hibernation: on disconnect or idle, capture the saveable state, drop the
composition, and restore it on reconnect — possibly on another node, which is also what makes
rolling deploys survivable.

**The API is HTML-first.** Elements and CSS directly, rather than a widget vocabulary translated
into CSS. It keeps the surface small and predictable, and leaves a higher-level layer as an option
rather than a requirement.

**Ktor first, with a portable core.** `LiveView` knows nothing about WebSockets or Ktor and can be
driven straight from a test with no server involved. Adapters for other servers are additive.

**The client is TypeScript.** About 600 lines of DOM manipulation, which ships as 4.9 kB with no
runtime of its own to carry.

---

## 11. Verification

```bash
./gradlew test                      # unit tests, asserting exact op streams
./gradlew :samples:demo:benchmark   # retained heap per session
./gradlew :samples:demo:run         # http://localhost:8080

cd e2e && npm install && npx playwright test
```

55 unit tests and 12 browser tests. The browser tests cover first paint with JavaScript blocked,
deep links rendering server-side, targeted patching, keyed list add/reorder/remove, updates that
originate on the server, typing while the server pushes unrelated updates, navigation without a page
load, back and forward, server-side validation gating a submit, and reconnection with state
preserved.

Two bugs came out of the browser tests rather than from reasoning about the code:

- **Events fired before the socket opened were dropped.** First paint is interactive HTML that
  exists before the WebSocket finishes connecting, so a fast click really can land in that window.
  Fixed with a bounded outbox flushed on open.
- **Ops buffered while disconnected were applied on top of a fresh tree.** A composition keeps
  running with no client attached — the sample's clock keeps ticking — so by reconnect the buffer
  described edits to a tree the arriving client had never seen. Fixed by draining the buffer when
  sending a full snapshot.

---

## 12. Not built yet

- **Hibernation.** `SessionRegistry` is the place for it: instead of closing a disconnected session,
  capture its saveable state, drop the composition, store the snapshot under the same token. Needs a
  `SaveableStateRegistry` with kotlinx.serialization savers and a `SessionStore` interface with
  in-memory and Redis implementations.
- **Adopting the server-rendered DOM.** The client currently receives a full `reset` when it
  connects instead of binding to the markup already on the page, so the tree is transmitted twice on
  first load. The scheme is worked out — `data-jl` ids are already emitted, text nodes need
  `data-jl-t="index:id"` on their parent, and adjacent text nodes need separator comments because
  browsers merge them — but the reset path was needed for rehydration anyway and was built first.
- **Client-only interactivity.** Toggles, dropdowns and tooltips should not need a round trip. A
  `clientOnly {}` escape hatch is the missing piece.
- File uploads, a testing module, a Spring Boot adapter, telemetry, and CI.

Known limitation: `ack` can be set slightly early when a background patch overlaps an inbound event,
which could let one stale property write through. The fix is to capture the ack at drain time.

---

## 13. Notes and risks

**Compose runtime API drift.** `Applier` and `Recomposer` are stable, but hosting the runtime
outside a UI toolkit is not a first-class supported use case. Mitigated by pinning versions, keeping
the surface small, and covering it with tests that would catch behavioural change.

**Dependency reachability.** The build pins Compose Multiplatform **1.5.12**, the newest release that
resolves entirely from Maven Central. From 1.6 onward the desktop runtime pulls androidx artifacts
published only to Google's Maven repo, which the development environment could not reach. Nothing
here depends on anything newer — `Applier`, `Composition`, `Recomposer` and the snapshot system are
stable across all of these — so it is a one-line bump.

**Related work.** [Molecule](https://github.com/cashapp/molecule) and
[Mosaic](https://github.com/JakeWharton/mosaic) both run the Compose runtime outside Android and
were useful references for the frame clock and snapshot handling.
[Redwood](https://github.com/cashapp/redwood) sends Compose tree mutations over a wire protocol to
native hosts. [Kilua](https://github.com/rjaros/kilua), Kobweb and Compose HTML drive a DOM from the
Compose runtime in the browser. JetBrains is
[exploring Compose HTML for server-side rendering](https://blog.jetbrains.com/kotlin/2026/08/exploring-compose-html-for-server-side-rendering/),
which is complementary — that work targets first paint, this targets what happens after it — and
keeping the HTML DSL similar in shape leaves interoperation open.
