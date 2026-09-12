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
| Data layer in the box | `jetlin-db` (SQLite, resident) | Ecto | Eloquent | EF Core |
| Row-level access control in it | yes, as Kotlin functions | Ecto scopes, by hand | policies, by hand | by hand |
| Database-level second layer | **no** | Postgres RLS | Postgres RLS | Postgres RLS |
| Asserting *how much* re-rendered | yes | no | no | no |
| File uploads | **no** | yes | yes | yes |
| More than one node | **no** | yes | yes | yes (with backplane) |
| Published artifacts | **no** | yes | yes | yes |
| Session cap / runaway-client limit | yes (not a DoS defence) | yes | framework-level | yes |
| Telemetry | **no** | yes | yes | yes |
| Ecosystem, components, docs | **no** | large | large | large |

---

## The data layer

Phoenix has Ecto, Rails has Active Record, Blazor has EF Core. `jetlin-db` is a different shape, and the
differences are worth stating plainly rather than claimed as wins.

**No query language.** An Ecto query or a LINQ expression is compiled to SQL against a database holding
more than fits in memory. `jetlin-db` keeps the working set resident as live objects, so `filter`,
`sortedBy` and `groupBy` from the Kotlin standard library *are* the query layer, and a relation is a
pointer dereference. That is simpler and it is also the constraint: no indexes, linear scans, and memory
as the ceiling — around 1.1 kB per stored row, measured. The mature ORMs are built for data that does not
fit in memory; this one is built for the case where it does, which is most applications and not all.

**Policies are functions, not scopes.** In Ecto or Active Record, "only the owner may see this" is a
`where` clause you remember to add — a scope, a default scope, a Pundit policy consulted by hand. Here it
is a method on the entity's companion, the only way to obtain a record goes through it, and an entity with
no policy fails the build. The cost is that it only works for data the process holds: a policy cannot be
translated to SQL, which is the same fact as the next two paragraphs.

**Reactive revocation, which none of them do.** Because a policy reads live state and reading a filtered
collection subscribes the reader, unsharing a row removes it from other people's *open pages*, and
revoking a role moves someone off the page they are sitting on. In LiveView or Blazor that is a
`broadcast` and a `handle_info`, written by hand per case; in a request-response framework it waits for the
next request. Here it is the absence of code. This is the one thing on this page that is genuinely not
available elsewhere, and it follows from the same property as the first table in this document.

**No defense in depth, which all of them have.** Ecto, Active Record and EF Core sit on Postgres, where
row-level security can enforce the same rules a second time, underneath the application. SQLite has no
such layer, so `jetlin-db`'s policies are the only enforcement there is. A bug in a policy is a
disclosure with nothing behind it, and arbitrary Kotlin policies do not port to RLS. For data where that
matters, this is the wrong tool and the tables above do not make up for it.

---

## Where Jetlin is genuinely behind

Being honest about this is more useful than the tables above.

**It is not published.** No Maven coordinates. You cannot depend on it. Everything else is academic
until that changes.

**No file uploads.** Every real application needs them.

**One node only.** `SessionStore` is shaped for a shared implementation and `SessionStoreContract`
pins the behaviour, but the policy for node-local windows has to be decided first. LiveView, Blazor
and Livewire all run behind a load balancer today.

**Limits are global, not per client.** A cap on live sessions stops the process dying and a
per-connection event budget stops a runaway page taking a share of the machine. Neither is a defence
against somebody who means it: one source can fill the cap and get everyone else refused, or flood
many sessions at the permitted rate. Per-address limits need a decision about trusting
`X-Forwarded-For` and for now belong in front of the application — which is where the mature
frameworks put them too, but they say so up front and have the operational guidance to match.

**The data layer has no second enforcement layer and no indexes.** See above. Also: one process owns the
database file, so the data layer inherits the one-node limit rather than softening it.

**No ecosystem.** LiveView has component libraries, LiveView Native, an eight-year-old community and
Elixir's supervision trees underneath it. Vaadin ships a commercial component suite. Jetlin has a
five-page demo.

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
