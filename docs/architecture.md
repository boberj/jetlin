# Jetlin architecture

A server-driven UI framework for Kotlin: write interactive web UI as `@Composable` functions that
run on the server, with the browser acting as a thin renderer. Livewire and Phoenix LiveView in
spirit; the Compose runtime as the engine.

Status: **walking skeleton**. The core is built, tested and demonstrably working end to end in a
real browser. Section 8 lists what is designed but not yet built.

---

## 1. Why this is worth building

The idea — keep UI state on the server, ship diffs to a thin client — is well proven.
[Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view) and
[Livewire](https://livewire.laravel.com/) have both demonstrated that a large class of applications
never needed a client-side SPA. There is no equivalent for Kotlin: the JVM options are
[Vaadin](https://vaadin.com) (Java, its own widget world) and [Kweb](http://docs.kweb.io/)
(self-described as a proof of concept).

The interesting part is not the port. It is that **Kotlin has a better engine for this than either
reference framework does**, and it has been sitting in plain sight.

### The core thesis

Both reference frameworks spend most of their cleverness reconstructing information they lost.

LiveView compiles templates into a
[`Rendered` struct](https://hexdocs.pm/phoenix_live_view/Phoenix.LiveView.Engine.html) of static
string segments, dynamic slots and fingerprints, plus special `Comprehension` structs so a list's
shared static parts are sent once. Livewire re-renders the component to HTML on every round trip and
ships the whole thing to a client-side [morph](https://msaied.com/articles/laravel-livewire-v3-internals-morph-markers-js-hooks-and-alpine-integration)
algorithm that walks two DOM trees in parallel guessing at node identity. Turbo went through the same
arc, [switching from morphdom to idiomorph](https://radan.dev/articles/turbo-morphing-deep-dive)
because id-based matching was too brittle.

All of that machinery exists for one reason: their view layer is a pure function re-run from
scratch, so *what changed* has to be recovered after the fact.

The Compose runtime does not have this problem. It is a general-purpose incremental tree engine:
positional memoization in the slot table means only composables whose observed state changed re-run,
and the runtime hands the resulting tree mutations to an
[`Applier`](https://arunkumar.dev/jetpack-compose-for-non-ui-tree-construction-and-code-generation/)
as insert/remove/move operations. Make the Applier's node tree a virtual DOM and **the patch stream
falls out of the runtime**. No fingerprints, no static/dynamic split, no morphing, no DOM diffing
anywhere in the system.

What comes with it, free, is a mature state and effect model that neither reference framework has:
`remember`, `derivedStateOf`, `LaunchedEffect`, `snapshotFlow`, `CompositionLocal`, `key`, and
coroutines throughout. LiveView's equivalents are `assigns` and `handle_info`; Livewire's are public
properties and lifecycle hooks.

### Verified, not asserted

`jetlin-html/src/test/kotlin/jetlin/html/HtmlApplierTest.kt` asserts on **exact op lists**, not
`contains`, so any extra chatter fails the build:

| Change | Ops emitted |
|---|---|
| One text node's content changes | exactly `SetText(id, …)` |
| One attribute changes on one of two siblings | exactly `SetAttr(id, "class", …)` |
| Append to a keyed list | exactly one `Insert` carrying the whole new subtree |
| Remove from a keyed list | exactly one `Remove` |
| Reorder a keyed list | only `Move` ops — nodes are never rebuilt |
| Unrelated state changes | **zero ops** |

### Prior art, and why none of it is the answer

| Project | What it proves | Why Jetlin is not it |
|---|---|---|
| [Molecule](https://github.com/cashapp/molecule) | The Compose runtime runs headless on the JVM | Reduces a composition to one `StateFlow` value and discards the node tree — the tree is exactly what we need |
| [Mosaic](https://github.com/JakeWharton/mosaic) | Custom `Applier` + `Recomposer` + frame clock off Android | Renders to a terminal; the closest structural reference, and Jetlin's runtime follows its shape |
| [Redwood](https://code.cash.app/native-ui-and-multiplatform-compose-with-redwood) | Compose tree mutations serialized over a wire protocol, in production | Schema-driven native widgets with a Zipline guest; the toolchain and widget model do not fit arbitrary HTML/CSS |
| [Kilua](https://github.com/rjaros/kilua), Kobweb, Compose HTML | Compose runtime driving a DOM | The composition runs *in the browser*; no server-held state, no server push |
| [Compose HTML SSR](https://blog.jetbrains.com/kotlin/2026/08/exploring-compose-html-for-server-side-rendering/) (JetBrains, Aug 2026) | JetBrains sees value in Compose on the server | Exploration of first-paint SSR, not live interactivity. A future interop target, not a competitor — see §9 |

---

## 2. Shape of the system

```
┌─ Browser ──────────────────────────────────────────────────────────────────┐
│  jetlin.js (4.6 kB minified)                                               │
│  applies ops · delegates events · guards in-flight input · reconnects      │
└───────────────▲──────────────────────────────────────┬─────────────────────┘
                │ patch { rev, ack, ops }              │ event { node, seq }
┌───────────────┴──────────────────────────────────────▼─────────────────────┐
│ jetlin-server-ktor   GET → SSR HTML + token · WS → adopt session           │
├────────────────────────────────────────────────────────────────────────────┤
│ jetlin-html          LiveView · HtmlOwner · HtmlApplier · SSR serializer    │
├────────────────────────────────────────────────────────────────────────────┤
│ jetlin-runtime       CompositionHost · FramePolicy · GlobalSnapshotManager  │
├────────────────────────────────────────────────────────────────────────────┤
│ androidx.compose.runtime   Recomposer · Composition · Applier · snapshots   │
└────────────────────────────────────────────────────────────────────────────┘
```

`jetlin-protocol` sits alongside as the shared vocabulary of ops and messages.

### `jetlin-runtime` — hosting a composition

`CompositionHost` is `Recomposer` + `Composition` + a frame clock, with no Android and no UI
toolkit. One host is one session. Three decisions matter:

**Everything is confined to one thread.** Each session gets
`Dispatchers.Default.limitedParallelism(1)`. Event handling, recomposition and patch draining cannot
interleave, which removes a class of races with no locking and gives natural per-session
back-pressure.

**The mutable snapshot is the batching unit.** `transact { }` wraps a handler in
`Snapshot.withMutableSnapshot`, so a handler that writes ten state objects produces one apply
notification, one recomposition pass, and one patch message.

**Global writes must be pumped.** State written from a background coroutine lands in the global
snapshot and stays invisible until someone calls `Snapshot.sendApplyNotifications()`. On Android the
UI dispatcher does this; off Android nobody does, and the failure mode is silent — server push
simply never recomposes. `GlobalSnapshotManager` is the process-wide pump, conflated so a burst of
writes wakes recomposers once.

`FramePolicy` controls how aggressively state changes become patches. `Immediate` recomposes as soon
as there is work; `Paced(interval)` caps the rate for sessions fed by high-frequency server-side
sources. One frame is at most one patch message.

> A detail worth recording: when a composable throws, the recomposer ends up `Inactive`, **not**
> `ShutDown`. Waiting on recomposer state alone therefore cannot distinguish "died" from "not
> started", and hangs forever. `awaitIdle()` races the idle wait against the runner job instead.

### `jetlin-html` — the virtual DOM

`ElementNode` / `TextNode` form the tree. `HtmlApplier` extends `AbstractApplier` and records every
mutation as an op. It performs no comparison of any kind — every op corresponds to a decision the
runtime made.

Two mechanisms carry most of the weight:

**Attachment gating.** Nodes only emit ops once reachable from the root. Compose inserts subtrees
bottom-up, so a node's children are populated before it joins the tree; suppressing ops until
attachment is what lets a whole new subtree ship as one `Insert` instead of a create-configure-parent
chatter of dozens of ops.

**Splitting data from identity.** `ComposeNode`'s `update` block uses two `set` calls:

```kotlin
set(data)     { applyData(it) }     // structural: attributes, props, listener specs → ops
set(handlers) { this.handlers = it } // identity: fresh closures each pass, never transmitted
```

Handler lambdas get new identities on every recomposition. Including them in the compared value
would make every element unequal to its previous self and defeat Compose's skipping; excluding them
from storage would leave stale closures capturing stale values. Splitting solves both, and is why
Jetlin needs no equivalent of Livewire's `wire:click="methodName"` string indirection — a closure
captures what it needs and stays type-checked.

**XSS is structural.** Text is a `TextNode`, never a string spliced into markup. There is no
interpolation point at which user data could become HTML. Escaping is a property of the
architecture, not a rule authors must remember.

### `jetlin-client` — the browser runtime

4.6 kB minified, no dependencies. It maintains `id → Node`, a mirrored logical child array per
element, and listener specs.

The child array matters: the DOM's own `childNodes` cannot be used for indexing because browsers
merge adjacent text nodes and third-party scripts inject siblings. Keeping our own array means every
index in an op means exactly what the server meant by it.

`Move` replicates `AbstractApplier.move`'s destination adjustment (`from > to ? to : to - count`)
exactly. Divergence here would reorder lists differently on the two sides — silently.

Events use **one capture-phase listener per event type** on the container. Capture rather than
bubble so events that do not bubble (`focus`, `blur`) still reach the delegate, and so a single
registration survives any amount of subtree churn.

### `jetlin-server-ktor` — transport

`GET` renders the composition to HTML with a session token; `WS /jetlin` adopts the session that
render created. The composition is built **once per session**, not once per transport — LiveView by
contrast performs a "dead" render followed by a live one.

---

## 3. The two hard problems

Every LiveView-style framework is judged on these.

### Latency, and not eating what the user is typing

A naive implementation round-trips every keystroke and then overwrites the input with a stale server
value. Four mechanisms, designed in rather than bolted on:

1. **Patches touch properties, never `innerHTML`.** Unrelated DOM is untouched by construction, so
   focus, selection and scroll position survive. The e2e suite tags surrounding nodes and asserts
   they are the same objects after an update.
2. **The stale-write guard.** Each patch carries `ack`, the highest client event sequence folded
   into it. The client tracks the last sequence it sent from each node; a `value`/`checked` write
   older than that is dropped rather than applied. `a server push does not clobber text the user is
   typing` types a full sentence while the server clock pushes patches throughout, and asserts every
   character survives.
3. **Client-side debounce and throttle**, declared per listener and enforced before the event is
   ever sent.
4. **`value` as a DOM property, not an attribute** — setting the attribute only changes the
   control's default and is silently ignored once the user has interacted.

### Memory and scale

Measured with `./gradlew :samples:counter:benchmark` — 1000 concurrent live sessions of a 111-node
view:

```
sessions:           1000
nodes per session:  111
per session:        134 kB
total:              131 MB
```

For calibration: [LiveView is reported](https://alembic.com.au/blog/monitoring-phoenix-liveview-performance)
at roughly 3 MB per active connection dropping to ~150 kB hibernated;
[Vaadin sessions](https://vaadin.com/blog/how-many-users-can-you-host-per-node-lets-do-the-math)
typically run 50 kB–1 MB. Jetlin's *active* footprint is in the same range as LiveView's
*hibernated* one. 10k sessions is roughly 1.3 GB — comfortable on a normal server.

This was measured in the walking skeleton deliberately, because it is the number that decides
whether the whole architecture is viable, and finding it out late would be expensive.

---

## 4. Protocol

Server → client:

| Op | Meaning |
|---|---|
| `ins(parent, index, node)` | insert a complete subtree |
| `rm(parent, index, count)` | remove children |
| `mv(parent, from, to, count)` | reorder, matching `AbstractApplier.move` semantics |
| `attr(id, name, value)` | set attribute; `null` removes |
| `prop(id, name, value)` | set DOM property (`value`, `checked`) |
| `text(id, text)` | set text node content |
| `on(id, event, spec)` / `off(id, event)` | listener registration |

Messages: `patch{rev, ack, ops}`, `reset{rev, children}` (full tree, used on attach and rehydration),
`error{message, fatal}`. Client → server: `hello{token}`, `event{node, event, seq, payload}`.

`ListenerSpec` carries a declarative extractor set (`value`, `checked`, `key`, `form`) plus
debounce, throttle, `preventDefault` and `stopPropagation`. Handlers never cross the wire.

Encoding is JSON via kotlinx.serialization with a `t` discriminator. Compact positional encoding and
CBOR are drop-in swaps later; debuggability is worth more right now.

---

## 5. Authoring model

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

HTML-first, shaped like Compose HTML: attributes and CSS are directly under the author's control,
there is no lossy translation of layout semantics, and interop with JetBrains' Compose HTML work
stays open. A widget layer (`Column`/`Row`/`Card` over a `Modifier`-like API) can sit on top later
without changing the core, because widgets are just composables that emit HTML.

Server push needs no API at all:

```kotlin
@Composable
fun ServerClock() {
    var ticks by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }
    Text("Uptime: ${ticks}s")
}
```

A coroutine writes state, composables that read it recompose, patches follow. This is LiveView's
`handle_info` without the ceremony, and something Livewire's stateless model structurally cannot do.

---

## 6. Design forks

The four decisions that shaped everything, and what was rejected.

**State model — stateful with hibernation.** The composition lives in server memory while connected;
on disconnect or idle its saveable state is captured to a pluggable store and the composition
dropped, rehydrating on reconnect, possibly on another node.
*Rejected:* always-stateful (Vaadin/LiveView) needs sticky sessions and loses state on deploy —
[precisely where Vaadin's model strains](https://vaadin.com/docs/latest/flow/production/distributed-deployment);
stateless snapshots (Livewire) forfeit fine-grained diffing, long-lived effects and server push,
which is most of the reason to use Compose at all.

**DSL — HTML-first core.** *Rejected:* a Compose-UI-shaped API (`Column`/`Row`/`Modifier` → CSS
flexbox) is a permanently lossy translation surface; better as an optional layer above.

**Server host — Ktor first, portable core.** `LiveView` knows nothing about WebSockets or Ktor and
can be driven from a test with no server in the loop. *Rejected:* Spring-first adds plumbing before
the core is proven; the adapter is a small later addition.

**Client runtime — TypeScript.** 4.6 kB. *Rejected:* Kotlin/JS would share protocol types but ships
a disproportionate runtime for ~600 lines of DOM patching.

---

## 7. Verification

```bash
./gradlew test                         # unit: exact op-stream assertions
./gradlew :samples:counter:benchmark   # retained heap per session
./gradlew :samples:counter:run         # http://localhost:8080

cd e2e && npm install && npx playwright test
```

15 unit tests plus 7 browser tests. The e2e suite covers first paint without JavaScript, granular
patching, keyed list add/reorder/remove, unprompted server push, typing safety under concurrent
server updates, and reconnection with state preserved.

Two bugs were found by these tests rather than by reasoning, which is the argument for having built
them this early:

- **Events fired before the socket opened were silently dropped.** First paint is interactive HTML
  that exists before the WebSocket finishes connecting, so a fast click genuinely lands in that
  window. Fixed with a bounded outbox flushed on open.
- **Ops buffered while disconnected double-applied after a reset.** A composition keeps running with
  no client attached (the sample's clock keeps ticking), so by reconnect the buffer described a tree
  the arriving client had never seen. Fixed by draining the buffer when sending a full snapshot.

---

## 8. What is not built yet

Designed, not implemented:

- **Hibernation.** `SessionRegistry` is where it belongs: instead of closing an unclaimed session,
  capture its saveable state, drop the composition, store the snapshot under the same token. Needs a
  `SaveableStateRegistry` with kotlinx.serialization savers (not androidx's Bundle-oriented
  `autoSaver`) and a `SessionStore` interface with in-memory and Redis implementations.
- **SSR DOM adoption.** The client currently receives a full `reset` on connect rather than adopting
  the server-rendered DOM in place, so the tree is transmitted twice on first load. The scheme is
  worked out — `data-jl` ids are already emitted, text nodes need `data-jl-t="index:id"` on their
  parent, and adjacent text nodes need separator comments because browsers merge them — but the
  reset path was needed for rehydration anyway, so it was built first.
- **Back-pressure.** A slow or absent client currently buffers ops without bound. The frame clock is
  the right place to apply pressure: stop granting frames when the socket's send buffer is full.
- **Routing and live navigation**, forms and validation, uploads, `jetlin-testing`, JS commands and
  hooks for client-only interactivity, a Spring Boot adapter, telemetry.

Known limitations: `ack` can be set slightly early when a background patch overlaps an inbound
event, which could let one stale property write through; the fix is to capture the ack at drain time.

---

## 9. Risks

**Compose runtime API drift.** `Applier` and `Recomposer` are stable, but non-UI hosting is not a
first-class supported use case. Mitigated by pinning versions, keeping the surface small, and
covering it with tests that would catch behavioural change.

**Dependency reachability.** The build pins Compose Multiplatform **1.5.12**, the newest release that
resolves entirely from Maven Central. From 1.6.x the desktop runtime pulls androidx artifacts
published only to Google's Maven repo, which this build environment's egress policy blocks. Nothing
in Jetlin depends on anything newer — this is a one-line bump once `dl.google.com` is reachable.

**JetBrains' Compose HTML SSR effort** is both convergence risk and opportunity. Keeping the HTML DSL
shape-compatible means Jetlin could sit on top of it rather than beside it. The two are complementary
today: that work targets first paint, this one targets what happens after.

**Latency remains the standing critique** of the whole architecture. Mitigations are in from day one,
but a `clientOnly {}` island escape hatch — so toggles, dropdowns and tooltips never touch the
server — is the significant missing piece.
