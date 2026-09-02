# Jetlin architecture

A server-driven UI framework for Kotlin. You write interactive web UI as `@Composable` functions
that run on the server; the browser gets HTML plus a small runtime that applies the DOM changes the
server sends and reports events back.

Phoenix LiveView and Livewire were the inspirations for the idea.

Status: **early**. The core is built and tested end to end in a real browser, with routing, request
context, navigation, forms, hibernation and adoption of the server-rendered DOM on top of it.
Section 13 lists what is missing, in the order it would stop you shipping.

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
│  jetlin.js (8.5 kB minified)                                               │
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

8.5 kB minified, no dependencies. It keeps three things: `id → Node`, a mirrored array of logical
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
alive in `SessionRegistry`; when the WebSocket connects with that token it takes over the composition
that is already there, so a page is composed once per session rather than once per request.

### Keeping the markup the browser already has

That socket then keeps the DOM it was served rather than being sent the tree a second time. The
client walks the markup, indexes it, and tells the server it has done so; the server replies with
`Ready` instead of `Reset`, and anything that changed between rendering the HTML and the socket
connecting — a `LaunchedEffect` that already fired, a shared store someone else edited — arrives as
an ordinary patch. On the demo's shapes page that turns a 3213-byte opening message into 21 bytes,
and first-load traffic from 8520 bytes to 5328.

The bytes are the smaller half. A rebuild would discard the DOM the browser had already parsed, laid
out and painted, taking with it whatever happened to the page in the meantime: focus, a selection, a
scroll position, an element another script inserted.

Elements name themselves with `data-jl`, but text nodes are the problem — they carry no attributes,
an HTML parser merges two adjacent ones into a single node, and one with no content produces no node
at all. So the markup states what the parser cannot:

| Marker | Meaning |
|---|---|
| `data-jl-t="0:3,2:7"` | which child indices are text, and the id of each |
| `<!--\|-->` | a boundary between two text children, so they survive parsing as two nodes |
| `<!--0-->` | a text child with no content, which the client turns back into an empty text node |
| `data-jl-raw` | content from `unsafeInnerHtml`; not the composition's, so not indexed |

Adoption applies only to the first socket reaching the composition that rendered the page, and the
server decides that rather than trusting the client's request. A reconnecting browser is holding
markup the server has since edited without watching — it stopped recording when the previous socket
went away — and a woken session has composed afresh, with node ids bearing no relation to the
`data-jl` values still in the page. Both get a `Reset`.

The walk is best-effort. Any disagreement with the markup — an element with no id, a text child the
markers do not account for — abandons it and asks for the full tree, so a marker that never arrived
or a proxy that rewrote the HTML costs an optimization rather than correctness. `Jetlin.connect({
adopt: false })` forces the old path, which makes it one flag to determine whether adoption is
implicated in a bug.

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

### When something fails

Three things can go wrong on an open socket, and they are not the same thing.

**A frame that cannot be read** is dropped with a warning. Frames come from a browser, which is not
obliged to be well behaved: ending the session over one would let any client kill its own session
with a typo, and would turn a protocol version skew into an outage rather than a log line.

**A handler that throws** costs one interaction. The composition is untouched — the exception came
out of the event lambda, not out of recomposition — so the page is still correct and the session
still works. The client is sent `ServerMessage.Error(fatal = false)` and carries on.

**A composable that throws** ends the session. The recomposer stops for good, so nothing this
session does afterwards can succeed; the client is sent `Error(fatal = true)` and reloads into a
fresh one. Leaving it connected would be worse than disconnecting it: a page that looks live and can
never change again.

`CompositionHost.isAlive` is what separates the second from the third, and it is the only thing that
can: both arrive at the transport as an exception out of `dispatch`.

What the browser is told is a fixed, generic sentence. An exception's own text routinely carries
query fragments, file paths and identifiers, and none of it is the client's business. The real
exception is logged and handed to `JetlinConfig.onError`, which is where an application forwards it
to whatever it uses for error reporting — a log line nobody reads is not error handling. On the
client the message is also raised as a `jetlin:error` DOM event, so an application can show a toast
or a banner; the framework has no business deciding what an error looks like, but it does have to
make one noticeable, because a click that quietly did nothing is the worst of both.

That event is **cancelable**, and `preventDefault()` is how a page says it has taken over: on a
fatal error the automatic reload does not happen. Deliberately not "a listener is registered" —
plenty of applications will add one only to forward errors to their telemetry, and silently
disabling recovery for them would be a nasty surprise. Taking over is a decision made per error, not
a side effect of wanting to hear about them.

Cancelling leaves a page that cannot change again — the composition is gone and the socket is
closed — which is the state the fatal path exists to avoid, now entered deliberately. Jetlin marks
it with `jl-dead` on the body so it can be dimmed or overlaid without anything being guessed at, and
offers `jetlin.reload()` as the way back. Whoever cancelled owns what the user sees from there.

A view that throws during its *initial* composition never becomes a session at all: `create` closes
the half-built view before rethrowing, so a failing page cannot leak a dispatcher thread per
request, and the HTTP layer returns its own error. A session whose composition died later is closed
by the reaper on its way past — `hibernate` waits for idle *inside* its `try`, because a dead
composition reports its failure from that wait, and the session most in need of releasing was
otherwise the one kind that never was.

The demo's `/errors` page is both halves side by side: a button that throws in a handler next to a
counter that keeps counting, and a button that throws in the view and takes the session with it.

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

## 9. Memory, hibernation and back-pressure

Holding UI state on the server means memory scales with connected users, so per-session cost sets
the ceiling on how many sessions a node can carry.

Measured with `./gradlew :samples:demo:benchmark` — 1000 concurrent sessions of a 113-node view:

```
live:               136 kB per session (133 MB total)
hibernated:         364 bytes per session (356 kB total)
```

### Hibernation

A session passes through three states. **Live**: the composition is in memory with a socket
attached. **Orphaned**: the socket has gone, but the composition stays up for a grace period,
because most disconnections are a tunnel or a sleeping laptop and reattaching to a running
composition is instant and lossless. **Hibernated**: the grace ran out, so what was declared with
`rememberSaved` is written to a `SessionStore` and the composition is destroyed — slot table, node
tree and coroutines all released.

That is where the 400x in the numbers above comes from: an idle session stops costing what a live
one costs, and starts costing what its saved state costs.

The contract is deliberately visible in the code. `remember` is scratch space and does not survive;
`rememberSaved` does. Keeping the line explicit means the saved payload stays small by default, and
an author decides what is worth carrying rather than discovering it.

```kotlin
val draft = rememberSavedField("", key = "draft")   // survives; the user typed it
val expanded = remember { mutableStateOf(false) }   // does not; recomputing costs nothing
```

Sessions are stored through a `SessionStore`. The in-memory default covers a dropped connection and
a closed laptop lid, but not a restart.

Waking is a transfer of ownership rather than a read, so the interface offers `take` — return and
remove, atomically — instead of separate load and delete steps. Two sockets can quote one token at
the same time, a reconnect racing a retry or two tabs restored from the same saved page, and exactly
one has to end up owning the session; otherwise both build a composition from the same snapshot and
one is left live, attached, and invisible to the reaper meant to collect it. The contract is
executable: `SessionStoreContract` includes a test that fails against any implementation reading and
deleting separately.

### On more than one node

**Jetlin assumes a single node.** A shared store would be the obvious next step and would let
sessions survive a restart, but on its own it would not make Jetlin multi-node, which is worth being
precise about because it is the natural assumption.

A store only ever holds *hibernated* sessions. Three windows are node-local regardless of where
snapshots live: while a socket is attached; between the page render and that socket connecting; and
throughout the disconnect grace period, when the composition is deliberately still up. A request
landing on another node during any of them finds nothing.

Closing those needs a policy, not storage — sticky routing, hibernating immediately on disconnect and
paying a re-render for every brief blip, or a handoff protocol where the receiving node asks the
owner to release the session. Each is a different trade, and picking one is the prerequisite for a
shared store rather than a consequence of it.

Two details worth knowing:

- **Keys.** `rememberSaved` derives a key from the composable's position, which distinguishes saved
  values in *different* composables. Two calls side by side in the *same* composable are
  indistinguishable on the pinned Compose runtime, so they need explicit keys. That case is detected
  when state is captured and reported, rather than allowed to silently overwrite one value with the
  other.
- **Old snapshots.** Stored state outlives deployments, so a snapshot written by a previous version
  of the code is a normal thing to encounter. A value that no longer deserializes falls back to its
  initializer instead of failing the session.

On waking, the client's address bar wins over the stored location: the user may have used the back
button while disconnected. If nothing was saved, nothing is stored — a session with no saved state
has nothing to come back to, and the client is told plainly so it can start a fresh one.

### Limits

Two ceilings, both of them degradations rather than defences: the aim is that a bad day for whoever
hits one is a normal day for everybody else.

**Sessions.** `JetlinConfig.maxSessions` caps how many are held at once. Every page render allocates
one whether or not a socket ever arrives to claim it, and unclaimed ones only go when the handoff
timeout runs out.

Without a ceiling, memory settles at roughly `arrival rate × handoff timeout × per-session cost` —
it plateaus rather than growing without end, because the reaper is clearing at the same time. At
~136 kB a session and a 30-second timeout, 100 requests a second is a plateau of about 400 MB, and
1,000 a second is 4 GB. The second of those is where the process dies. The cap turns a plateau you
cannot afford into refusals you can: past it, a page render gets a 503 and a `Retry-After`.

Reconnects are deliberately *not* capped. Somebody reattaching already had a session and is not the
one creating the pressure; turning them away to make room for new visitors is the wrong trade.

The cap is soft: two requests arriving together can both see room and both take it, so the real
ceiling is `maxSessions` plus whatever was in flight. Making it exact would mean serializing every
page render behind one lock to prevent an overshoot that is bounded and harmless.

**Events.** Each connection gets a token bucket — `eventsPerSecond` for the sustained ceiling,
`eventBurst` for how far it may run ahead. A bucket rather than a fixed window because real use is
bursty: a form gets filled in with a flurry and then nothing for ten seconds, and a window that
cannot absorb the flurry has to be set so high it stops protecting anything.

It is consulted **before the frame is parsed**, so a flood costs a clock read rather than a JSON
decode. Over-budget frames are dropped rather than queued — holding them would only move the flood
into memory — and the client is told once per episode with a non-fatal error, because a page that
ignores the first warning will ignore the next thousand.

**Be clear about what this is for.** It is not a defence against a determined attacker, and should
not be described as one. The frame loop was never unbounded to begin with: `dispatch` suspends until
the recomposition settles, so a client could never queue arbitrary work inside the composition, and
unread frames back up into socket buffers until TCP tells the sender to stop. Nor does the bucket
stop somebody who means it — with the session cap at ten thousand, a determined sender opens many
sessions and floods each at the permitted rate, and if a recomposition costs a millisecond that is
still twenty sessions per core.

What it is for is the ordinary case: **a client that has gone wrong.** Application JavaScript in a
loop, a `ClientComponent` pushing on every animation frame, a retry with no backoff, an `onInput`
wired to something that fires continuously. None of those are attacks and all of them are common,
and without a limit one user's broken page quietly takes a share of the server until somebody
notices a latency graph. It also turns "a session costs an unbounded amount" into a number that can
be multiplied by expected users, which is what makes capacity planning possible at all.

Real protection against deliberate abuse belongs in front of the application, in nginx or a load
balancer, which is also where per-address limits live.

**Both are logged, because a limit nobody hears about teaches nobody anything.** Reaching the
session cap and throttling a connection are each reported at `WARN`, and each is reached at request
rate, so both go through a `LogThrottle` that emits at most one line a minute carrying the count of
what it stood for. The throttle line names the page and the first eight characters of the session
token — truncated deliberately, since the token is a bearer credential and a leaked log should not
be a session takeover — and a connection that dropped anything reports its total on the way out.
`SessionRegistry.rejectedCount` and `liveCount` make the same thing visible to monitoring.

### Back-pressure

A composition outlives its socket, so a session with a running timer keeps producing updates whether
or not anyone is listening. Two limits apply:

- **With no client attached, edits are not recorded at all.** Whoever connects next is sent the whole
  tree anyway, so writing down the intervening edits would grow memory to describe a page nobody will
  be shown. The composition keeps running; only the recording stops.
- **With a client attached but too far behind, the buffer is dropped** once it passes
  `maxBufferedOps` (10,000 by default) and the next message is a full snapshot instead of a patch.
  Resending the tree costs more bytes once, but it bounds what a single slow reader can make the
  server hold. The session degrades to coarser updates rather than to an outage.

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

**The client is TypeScript.** About 730 lines of DOM manipulation, which ships as 8.5 kB with no
runtime of its own to carry.

---

## 11. Verification

```bash
./gradlew test                      # unit tests, asserting exact op streams
./gradlew :samples:demo:benchmark   # retained heap per session
./gradlew :samples:demo:run         # http://localhost:8080

cd e2e && npm install && npx playwright test
```

83 unit tests and 18 browser tests. The browser tests cover first paint with JavaScript blocked,
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

## 12. Testing application views

`jetlin-testing` drives a view headlessly — no browser, no server, no socket — so that an
application's own tests describe behaviour rather than protocol.

```kotlin
@Test
fun `clearing the title blocks the save`(): Unit = runViewTest(url = "/todo/1") {
    setContent(route = "/todo/{id}") { TodoDetailPage() }

    onNode(hasTestTag("title")).type("")

    onNode(hasTestTag("title-error")).assertText("A title is required")
    onNode(hasTestTag("save")).assertDisabled()
}
```

### Naming a node without marking up the page

`AttrsScope.testTag` names an element for tests. The name is stored on the node and, unlike an
ordinary `data-` attribute, is never serialized: it does not appear in the HTML, does not travel in
`NodeSpec`, and never becomes a `SetAttr`. A page a user is served carries nothing that exists only
for tests.

Browser tests are the exception, because Playwright can only select on what is really in the DOM.
`JetlinConfig.exposeTestTags` writes tags out as `data-test` as well — and does so by putting them
into the ordinary attribute map at composition time rather than conjuring them up in the serializer.
That is what keeps the feature to one decision: serialization, `NodeSpec`, attribute diffing and the
client's adoption walk all keep working untouched, so an exposed tag reaches nodes inserted long
after first paint and is patched normally when it changes. Writing it only at render time would have
left an attribute in the DOM that the server had no model of, and none at all on anything arriving
through `Op.Insert`.

### Work the browser does for itself

Every interaction described so far is a round trip, which is right whenever the server has an
opinion: it owns the data, the validation and the routing. It has no opinion about whether a menu is
open, and paying a network hop to find out is latency spent on nothing.

`AttrsScope.clientOnly` declares what the browser should do when an event fires:

```kotlin
Button({ clientOnly { toggleClass("open", on = closest("card")) } }) { Text("Details") }
```

The commands are a closed vocabulary — toggle, add and remove a class, focus, blur — not a script.
That limit is the design. Arbitrary client code would be a second application to keep in step with
the first, which is the thing this framework exists to avoid; a fixed set of verbs cannot grow into
one.

They cost no new machinery. `ListenerSpec` already travels to the browser, inside `data-jl-on` in
the first paint and as `Op.Listen` afterwards, so commands ride along and a page is interactive
before a socket exists. `ListenerSpec.notify` says whether the server wants to hear about the event
at all, and is **derived** from whether a handler was declared rather than stated by the author, so
the two cannot disagree. An element with commands and no handler acts and stays quiet; one with both
acts immediately and still reports, which is how a button shows a spinner before the work it
triggers has happened.

The classes named this way belong to the browser. If the composition also writes `class` on the same
element, the composition wins and the next patch overwrites whatever was toggled.

What a headless test can say about this is that it was declared, and declared exactly —
`assertClientCommands` pins the list. Whether the class actually toggles is a question for a browser,
and `jetlin-testing` says so rather than dispatching into nothing: clicking a client-only node fails
with an explanation instead of quietly doing nothing.

### Elements the composition does not own

Some things a server-side tree cannot produce: a map, a chart, a rich-text editor. `ClientComponent`
creates the element and stops there, naming an implementation the application registered in its own
bundle.

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

**Props down, events up, and the DOM in between is disposable.** Nothing survives a reconnect that
had to resend the tree; the element is rebuilt and mounted afresh from props the server still holds.
That is the same bargain `remember` makes, and it is why this needs no machinery for keeping a
subtree alive through a rebuild — the hard half of the problem is answered by not having it.

The rule that makes it safe: **nothing the user authored may live only inside one.** A map's pan and
zoom can be recreated and nobody minds; text somebody typed cannot, so it is pushed up and held in a
`rememberSaved` field, which then survives a reconnect and hibernation through machinery that
already exists.

Mechanically it adds almost nothing. `data-jl-component` and `data-jl-props` are ordinary
attributes, so they ride the HTML serializer, `NodeSpec` and `Op.SetAttr` unchanged: new props are
one attribute write, and a component inserted after first paint arrives complete. The client hooks
into four places that already existed — `build` and `adoptElement` mount, `forget` unmounts, `reset`
unmounts everything before `replaceChildren`. Pushes reuse the ordinary event path, carrying their
payload in `EventPayload.data`, which the framework does not interpret.

Two deliberate restrictions. There are no composable children: the contents belong to the
implementation, Jetlin records the element's logical children as empty and never patches inside it —
which also means nothing renders there with JavaScript disabled. And `name` is a key into a registry
the application populated, never a piece of code, so what crosses the wire can name an
implementation but can never be one.

Unmounting is not optional. A widget that is never told it is going keeps its listeners, timers and
observers, and a list that re-renders leaks a set each time. `JetlinConfig.clientSetup` is where an
application loads its registrations, injected after the runtime and before the session connects,
because a component whose implementation has not been registered by the time the markup is taken up
renders nothing.

`jetlin-testing` reaches the half of this that is not the browser's: `hasClientComponent` finds one,
`assertProps` pins what the server sent, and `pushFromClient` drives an event up so the server's
reaction can be asserted without a browser. What the implementation draws is covered by browser
tests, including that mounts and unmounts balance.

### Saying where, not what

Queries can be confined to a subtree, which is how a test says "the up button *in this row*" instead
of taking an index across every button on the page:

```kotlin
within(onAll(hasTestTag("todo"))[2]) { onNode(hasText("up")).click() }
```

The scope is a query rather than a resolved node, so it is re-resolved on each use and blocks nest.
`Update.assertOnlyWithin` follows the same idea: every change lay inside these subtrees.

Nodes are addressed by matcher rather than by id, and an interaction names a node and an event —
there is no geometry to hit-test, because that is not how input reaches a Jetlin view in the first
place. Events bubble to the nearest ancestor listening, as they would in a browser, and an
interaction on something nothing listens to fails rather than doing nothing.

Two things it covers that a client-side test kit has no equivalent for:

**How much of the page moved.** `recordUpdate { }` reports which nodes an interaction changed, so a
test can assert that checking one box patched one row and left the list alone. This catches a class
of defect that is invisible to every other kind of assertion: keying a list by a value that changes
renders exactly the same HTML and costs the whole list on every edit. Breaking `key(todo.id)` in the
sample leaves fifteen of its sixteen tests passing; the one that fails is this one.

**Whether the right state was declared saveable.** `hibernateAndRestore()` puts the session through
the cycle in section 9, so a test can pin down that a half-typed draft survives and that scratch
state does not.

A view reached by a route declares its pattern — `setContent(route = "/todo/{id}")` — and the path
parameters are resolved by matching the session's URL against it, so the id is written once instead
of twice. Tests that navigate use `setRoutes` instead, which follows the session between views.

Matchers hide the places where HTML is inconsistent about where state lives: `hasValue` reads the
`value` *property*, which is where `bind` writes, while `isDisabled` reads the `disabled`
*attribute*. Reaching for the wrong one is the mistake those matchers exist to prevent.

The module deliberately depends on no test framework. Its assertions throw `AssertionError`
directly, so it works under whichever runner the consuming project already uses.

---

## 13. What is missing

Ordered by what it stops you doing, not by size.

### Would stop a production deployment

- **The limits are global, not per client.** `maxSessions` stops the process dying and the
  per-connection token bucket stops a runaway page taking a share of the machine, but neither is a
  defence against somebody who means it: one source can fill the session cap and get everyone else
  refused, and can flood many sessions at the permitted rate. Making limits per remote address needs
  a decision about trusting `X-Forwarded-For`, which is its own security question — behind a proxy
  every request otherwise looks like the proxy. Until then this belongs in front of the application,
  in nginx or a load balancer, which is where per-address limits usually live anyway.
- **Nothing is published.** No Maven coordinates, no version scheme, no binary-compatibility
  validator. Nothing outside this repository can depend on Jetlin, which outranks every feature
  below it.
- **CI is not running.** The pipeline is written and parked in `ci/github-actions.yml`; enabling it
  needs someone with `workflow` scope to move it to `.github/workflows/`.
- **No telemetry.** Nothing reports session counts, patch sizes, recomposition time, hibernate and
  wake rates, or how often a buffer overflowed. All of it is knowable — the numbers exist inside
  `HtmlOwner` and `CompositionHost` — and none of it is exported.

### Would stop a real application

- **File uploads.** A WebSocket is the wrong pipe for bulk binary, so this needs a separate HTTP
  endpoint, progress reporting, and correlation back to the session that asked for it.
- **Event coverage is thin.** Five handlers (`onClick`, `onInput`, `onChecked`, `onSubmit`,
  `onKeyDown`) and four extracts (value, checked, key, form). Missing: focus and blur, change on a
  select, mouse and pointer events, key-up, paste, radio groups, multi-select, and anything reading
  `dataset` or coordinates. The mechanism is right and the vocabulary is small — this is filling in,
  not designing.
- **Element coverage is thin.** No `Dialog`, `Details`/`Summary`, `Ol`, `Dl`, `Fieldset`, `Canvas`,
  `Svg`, `Iframe` or media elements. `Element(tag)` is public so nothing is *blocked*; the
  convenience layer is simply incomplete.
- **Navigation is not accessible.** A client-side route change swaps the view without moving focus
  or announcing anything to a screen reader, and scroll position is not restored on back or forward.
  Standard omissions in this style of framework, and standard complaints about it.

### Would stop it scaling past one machine

- **Running on more than one node.** `SessionStore` is shaped for a shared implementation and its
  behaviour is pinned by `SessionStoreContract`, so a Redis or database-backed one is mostly a matter
  of passing that suite. The blocker is not storage but the policy for the node-local windows
  described in section 9, which has to be decided first. Two changes go with it whenever it happens:
  a generation counter on the snapshot so two processes cannot resurrect each other's state, and
  moving `SessionStore` out of `jetlin-server-ktor` so an implementation need not depend on Ktor.

### Known and deliberate

- **The `ack` can be set slightly early** when a background patch overlaps an inbound event, which
  could let one stale property write through. The fix is to capture the ack at drain time.
- **A client component does not survive a reset.** `ClientComponent` rebuilds and remounts when a
  reconnect has to resend the tree. That is the right default, and the props-down contract makes it
  invisible for anything whose state can be recreated. It is still wrong for a component holding
  something expensive to rebuild — a large canvas, a heavy widget mid-animation. Doing better means
  preserving a DOM subtree across a full-tree rebuild, which needs identity surviving
  `container.replaceChildren()` and reconciliation of a tree the server has stopped tracking. Worth
  it only once something real is hurt by the current behaviour.
- **`clientOnly` targets only itself or an ancestor by class.** No sibling targeting and no node
  references. Sibling targeting needs a handle to a node that has not been composed yet, which is a
  design problem rather than a coding one.
- **The session token is bearer-only.** Whoever holds it can attach to the session. It is generated
  from `SecureRandom`, never reused, and only ever appears in the page it belongs to — but it is not
  bound to a cookie or a principal, so a token leaked through a referrer header or a log is a
  session takeover. Binding it to the request that created it is the obvious hardening.

### Wanted but not urgent

Streaming the initial HTML rather than rendering it whole. A Spring Boot adapter. `phx-update`-style
merge policies for third-party-managed subtrees. Head management beyond `<title>` — meta and
Open Graph tags per route. A development-mode overlay for the errors described in section 7.

## 14. Notes and risks

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
