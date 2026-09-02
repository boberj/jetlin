# How Jetlin compares

Jetlin runs UI on the server and sends the browser instructions to update the DOM. That idea is not
new — [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view) and
[Livewire](https://livewire.laravel.com/) are the reason this project exists, and Blazor Server and
Vaadin got there earlier still. This page is about where the approaches actually differ, and where
Jetlin is behind.

Written in September 2026 against Jetlin at the commit this file ships in. Everything about the
others is from their public documentation; the further down a table a claim sits, the more likely it
has moved since.

---

## The one real difference

Every framework here has to answer the same question: **when state changes, how do you know what to
send?**

| | How it decides |
|---|---|
| **Jetlin** | It was told. Compose tracks which composable read which state, so a write invalidates exactly those. The Applier's mutations *are* the wire protocol. |
| **Phoenix LiveView** | Diffs the rendered template against the last one, using compile-time knowledge of which parts are static. |
| **Livewire** | Re-renders the component's Blade template and diffs the resulting HTML in the browser (morphdom). |
| **Blazor Server** | Diffs a render tree, like a virtual DOM, and sends the edits. |
| **Vaadin Flow** | Tracks changes to a server-side component tree and flushes them. |
| **Hotwire/Turbo** | Does not track anything. The server returns HTML fragments and the browser swaps them in. |

This is the one place Jetlin is doing something the others are not. Nothing compares two versions of
anything: the runtime recorded the reads, so it already knows. That is a property of hosting the
Compose runtime rather than of any cleverness here — Compose's incremental engine was built for
exactly this and works fine with no UI toolkit under it.

**What it buys:** reordering a keyed list emits moves rather than rebuilding rows, and an update
touches the nodes that actually changed. The test suite asserts on exact op lists rather than
`contains`, so an update that touches more of the page than it needs to fails the build — a
guarantee that is hard to state at all in a diffing design.

**What it costs:** the abstraction is unfamiliar. `@Composable` looks like a template and is not one;
it is a function whose reads are tracked and which may run again at any time. Getting `key` wrong in
a list is silent — the page looks identical and costs an order of magnitude more traffic. Jetlin
ships `recordUpdate { }` in `jetlin-testing` specifically because that failure is otherwise
invisible.

---

## Where each one sits

| | Jetlin | LiveView | Livewire | Blazor Server | Vaadin Flow | Hotwire |
|---|---|---|---|---|---|---|
| Language | Kotlin | Elixir | PHP | C# | Java | Ruby/any |
| UI written as | `@Composable` functions | HEEx templates | Blade templates | Razor components | Java component objects | Server templates |
| Transport | WebSocket | WebSocket | HTTP (fetch) | WebSocket (SignalR) | WebSocket/HTTP | HTTP + SSE |
| Update unit | Recorded mutations | Template diff | HTML diff | Render-tree diff | Component diff | HTML fragment |
| First paint | Server HTML | Server HTML | Server HTML | Server HTML (prerender) | Server HTML | Server HTML |
| State lives | Server | Server | Server | Server | Server | Server |
| Survives a disconnect | Grace period, then hibernation | Grace, then remount | Stateless per request | Circuit, then lost | Session-scoped | N/A |

---

## Feature by feature

Present, partial and absent, with no attempt to make the last column shorter than it is.

| | Jetlin | LiveView | Livewire | Blazor Server |
|---|---|---|---|---|
| Server-rendered first paint | yes | yes | yes | yes |
| Adopts the served DOM on connect | yes | yes | yes | yes |
| Targeted DOM patching | yes | yes | via morphdom | yes |
| Keyed list moves rather than rebuilds | yes | yes | partial | yes |
| Server-pushed updates | yes | yes | via polling/events | yes |
| Client-side routing | yes | yes | partial | yes |
| Forms with server validation | yes | yes | yes | yes |
| Debounce/throttle on input | yes | yes | yes | manual |
| Optimistic client behaviour | `clientOnly` (fixed verbs) | `JS` commands + hooks | Alpine.js | manual JS interop |
| Third-party widget integration | `ClientComponent` | hooks | `wire:ignore` + Alpine | JS interop |
| Idle-session hibernation | yes | no | n/a (stateless) | no |
| Headless test kit | `jetlin-testing` | `LiveViewTest` | Livewire test helpers | bUnit |
| Asserting *how much* re-rendered | yes | no | no | no |
| File uploads | **no** | yes | yes | yes |
| More than one node | **no** | yes | yes | yes (with backplane) |
| Published artifacts | **no** | yes | yes | yes |
| Rate limiting / session caps | **no** | yes | framework-level | yes |
| Telemetry | **no** | yes | yes | yes |
| Ecosystem, components, docs | **no** | large | large | large |

---

## Where Jetlin is genuinely behind

Being honest about this is more useful than the tables above.

**It is not published.** No Maven coordinates. You cannot depend on it. Everything else is academic
until that changes.

**No file uploads.** Every real application needs them.

**One node only.** `SessionStore` is shaped for a shared implementation and `SessionStoreContract`
pins the behaviour, but the policy for node-local windows has to be decided first. LiveView, Blazor
and Livewire all run behind a load balancer today.

**Nothing bounds inbound work.** No event rate limit, no cap on unattached sessions. The mature
frameworks all have answers here.

**No ecosystem.** LiveView has component libraries, LiveView Native, an eight-year-old community and
Elixir's supervision trees underneath it. Vaadin ships a commercial component suite. Jetlin has a
four-page demo.

**Elixir has something Kotlin does not.** The BEAM's per-process isolation means a crashed LiveView
takes down one lightweight process and a supervisor restarts it. Jetlin's equivalent is a confined
dispatcher per session plus the error handling in section 7 of the architecture doc — which is
careful, and is not process isolation.

---

## Where Jetlin does something the others do not

**Update-cost assertions.** `recordUpdate { }` reports which nodes an interaction changed, so a test
can assert that ticking one checkbox patched one row and the counter that depends on it, and nothing
else. No other framework here can express that, because none of them knows what changed until after
they have diffed. It catches a real class of bug: keying a list by a value that changes renders an
identical page and re-sends the whole list on every edit — breaking `key(todo.id)` in the sample
leaves fifteen of its sixteen tests passing, and the one that fails is that assertion.

**Hibernation.** An idle session drops from ~136 kB to ~364 bytes: whatever was declared
`rememberSaved` is stored and the composition destroyed, with `remember` deliberately discarded as
scratch. Roughly 390× cheaper to hold an idle user. LiveView remounts rather than restores; Blazor
loses the circuit.

**Test tags that cost nothing.** `testTag("draft")` names a node for tests without putting anything
in the page — no `data-test` attribute shipping to every user forever. `exposeTestTags` writes them
out for browser tests, off in production.

**One language, no serialization boundary.** A handler closes over the objects it needs and calls
straight into application code. There is no client-side state to invalidate, no API layer to design,
and validation rules live next to the code that acts on them. This is the shared premise of every
framework on this page — it is just unusually direct in a statically typed language with an
incremental UI runtime already built for it.

---

## When to pick something else

- **You need it in production this quarter.** Use LiveView, Livewire or Blazor. They are mature,
  published, documented and supported.
- **High-latency users on unreliable networks.** Every framework here suffers, but the mature ones
  have years of mitigation. Jetlin has `clientOnly` and not much else.
- **Mostly-static content.** Hotwire or plain server rendering. Holding a live session per reader is
  a cost with nothing to show for it.
- **Offline capability.** None of these. You want a client-side framework.
- **You are on the JVM and want this shape today.** Vaadin Flow is mature and commercially
  supported. It is component-object-oriented rather than declarative, which is a real difference in
  how code reads, but it works and it ships.

Jetlin is worth a look if you write Kotlin, want server-driven UI written declaratively, and are
willing to work on something early.
