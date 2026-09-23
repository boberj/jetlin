# How Jetlin compares

Jetlin runs the UI on the server and sends the browser instructions for updating the DOM. Other
frameworks did this first: [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view) and
[Livewire](https://livewire.laravel.com/) inspired this project, and Blazor Server and Vaadin predate
both. This page covers where the approaches actually differ, and where Jetlin is behind.

It was written in September 2026, against the version of Jetlin in the same commit as this file.
Everything about the other frameworks comes from their public documentation. The further down a table
a claim is, the more likely it is to be out of date.

---

## The main difference

Every framework here has to answer the same question: **when state changes, how does it work out what
to send to the browser?**

| | How it decides |
|---|---|
| **Jetlin** | It doesn't have to work it out. Compose tracks which composables read which state, so a write invalidates exactly those composables. The changes the Applier makes to the node tree are sent to the browser as they are. |
| **Phoenix LiveView** | Diffs the rendered template against the previous render, using compile-time knowledge of which parts are static. |
| **Livewire** | Re-renders the component's Blade template, and the browser diffs the resulting HTML (morphdom). |
| **Blazor Server** | Diffs a render tree, similar to a virtual DOM, and sends the edits. |
| **Vaadin Flow** | Tracks changes to a server-side component tree and sends them. |
| **Hotwire/Turbo** | Tracks nothing. The server returns HTML fragments and the browser swaps them in. |

This is the one area where Jetlin does something the others don't. It never compares two versions of
anything, because the runtime recorded the reads and already knows what changed. That comes from the
Compose runtime, not from anything special in Jetlin: Compose's incremental engine was designed for
this, and it works without a UI toolkit.

**Benefits:** reordering a keyed list sends move operations instead of rebuilding the rows, and an
update touches only the nodes that changed. The test suite compares exact lists of operations rather
than checking that a list contains something, so an update that changes more of the page than needed
fails the build. That kind of guarantee is hard to even express in a diffing design.

**Costs:** the model is unfamiliar. A `@Composable` function looks like a template, but it isn't one: it
is a function whose reads are tracked and that can run again at any time. A wrong `key` in a list fails
silently. The page looks the same, but it sends many times more data. `jetlin-testing` includes
`recordUpdate { }` specifically to detect this, because nothing else would.

---

## Overview

| | Jetlin | LiveView | Livewire | Blazor Server | Vaadin Flow | Hotwire |
|---|---|---|---|---|---|---|
| Language | Kotlin | Elixir | PHP | C# | Java | Ruby/any |
| UI written as | `@Composable` functions | HEEx templates | Blade templates | Razor components | Java component objects | Server templates |
| Transport | WebSocket | WebSocket | HTTP (fetch) | WebSocket (SignalR) | WebSocket/HTTP | HTTP + SSE |
| Unit of update | Recorded changes | Template diff | HTML diff | Render-tree diff | Component diff | HTML fragment |
| First paint | Server HTML | Server HTML | Server HTML | Server HTML (prerender) | Server HTML | Server HTML |
| State lives on | Server | Server | Server | Server | Server | Server |
| After a disconnect | Grace period, then hibernation | Grace period, then remount | Stateless per request | Circuit kept, then lost | Session-scoped | N/A |

---

## Features

Each row shows whether a feature is supported, partly supported or missing. Rows where Jetlin is
missing a feature are included as they are.

| | Jetlin | LiveView | Livewire | Blazor Server |
|---|---|---|---|---|
| Server-rendered first paint | yes | yes | yes | yes |
| Reuses the served DOM on connect | yes | yes | yes | yes |
| Targeted DOM patching | yes | yes | via morphdom | yes |
| Keyed lists move rows instead of rebuilding | yes | yes | partial | yes |
| Server-pushed updates | yes | yes | via polling/events | yes |
| Client-side routing | yes | yes | partial | yes |
| Forms with server validation | yes | yes | yes | yes |
| Debounce/throttle on input | yes | yes | yes | manual |
| Immediate client-side behaviour | `clientOnly` (fixed commands) | `JS` commands + hooks | Alpine.js | manual JS interop |
| Third-party widget integration | `ClientComponent` | hooks | `wire:ignore` + Alpine | JS interop |
| Idle session hibernation | yes | no | n/a (stateless) | no |
| Headless test kit | `jetlin-testing` | `LiveViewTest` | Livewire test helpers | bUnit |
| Bundled data layer | `jetlin-db` (SQLite, in memory) | Ecto | Eloquent | EF Core |
| Per-record access control in the data layer | yes, as Kotlin functions | Ecto scopes, by hand | policies, by hand | by hand |
| Second enforcement layer in the database | **no** | Postgres RLS | Postgres RLS | Postgres RLS |
| Asserting *how much* re-rendered | yes | no | no | no |
| File uploads | **no** | yes | yes | yes |
| More than one node | **no** | yes | yes | yes (with backplane) |
| Published artifacts | **no** | yes | yes | yes |
| Session cap and runaway-client limit | yes (not a DoS defence) | yes | framework-level | yes |
| Telemetry | **no** | yes | yes | yes |
| Ecosystem, components, documentation | **no** | large | large | large |

---

## The data layer

Phoenix has Ecto, Rails has Active Record, and Blazor has EF Core. `jetlin-db` works differently, and
the differences are trade-offs, not simply advantages.

**No query language.** An Ecto query or a LINQ expression is compiled to SQL and runs against a
database that can be much larger than memory. `jetlin-db` keeps all records in memory as live objects,
so the Kotlin standard library's `filter`, `sortedBy` and `groupBy` are the query language, and
following a relation is a field access. That is simpler, but it is also the limitation: there are no
indexes, every query is a linear scan, and memory is the upper limit, at about 1.1 kB per stored record
(measured). The established ORMs are designed for data that doesn't fit in memory. `jetlin-db` is
designed for data that does, which covers most applications but not all.

**Policies are functions, not scopes.** In Ecto or Active Record, "only the owner may see this" is a
`where` clause you have to remember to add, through a scope, a default scope, or a Pundit policy you
call yourself. In `jetlin-db` it is a method on the entity's companion object, every way of obtaining a
record goes through it, and an entity without a policy fails the build. The limitation is that this
only works for data held in memory. A policy can't be translated to SQL, which is also the cause of the
next two points.

**Reactive revocation, which the others don't have.** A policy reads live state, and reading a
filtered collection subscribes the reader. So unsharing a record removes it from other people's *open
pages*, and revoking a role moves someone off the page they're on. In LiveView or Blazor you would
write a `broadcast` and a `handle_info` for each case. In a request-response framework it only takes
effect on the next request. In Jetlin it requires no code at all. This is the one thing on this page
that isn't available elsewhere, and it follows from the same property described in the first section.

**No second layer of enforcement, which the others have.** Ecto, Active Record and EF Core typically
run on Postgres, where row-level security can enforce the same rules again inside the database. SQLite
has no equivalent, so `jetlin-db`'s policies are the only enforcement. A bug in a policy exposes data
with nothing else to stop it, and arbitrary Kotlin policies can't be converted to RLS. If that matters
for your data, `jetlin-db` is the wrong choice, whatever the tables above say.

---

## Where Jetlin is behind

This section is more useful than the tables above.

**It isn't published.** There are no Maven coordinates, so you can't depend on it. Until that changes,
nothing else on this page matters much.

**No file uploads.** Almost every real application needs them.

**Single node only.** `SessionStore` is designed to allow a shared implementation, and
`SessionStoreContract` defines the required behaviour, but the policy for the node-local windows has to
be decided first. LiveView, Blazor and Livewire all run behind load balancers today.

**Limits are global, not per client.** A cap on live sessions keeps the process from running out of
memory, and a per-connection event budget stops a misbehaving page from using a large share of the
server. Neither protects against a deliberate attacker: a single source can use up the session cap so
everyone else is refused, or flood many sessions at the allowed rate. Per-address limits require
deciding whether to trust `X-Forwarded-For`, and for now they belong in a proxy in front of the
application. The mature frameworks put them there too, but they document this clearly and provide
operational guidance.

**The data layer has no second enforcement layer and no indexes.** See above. Also, one process owns
the database file, so the data layer is limited to a single node as well.

**No ecosystem.** LiveView has component libraries, LiveView Native, a community going back eight
years, and Elixir's supervision trees. Vaadin sells a commercial component suite. Jetlin has a
five-page demo.

**Elixir has something Kotlin doesn't.** On the BEAM, each LiveView runs in an isolated lightweight
process, so a crash takes down one process and a supervisor restarts it. Jetlin's equivalent is a
single-threaded dispatcher per session plus the error handling described in section 7 of the
architecture document. That handling is careful, but it isn't process isolation.

---

## Where Jetlin does something the others don't

**Assertions on update cost.** `recordUpdate { }` reports which nodes an interaction changed. A test can
assert that checking one checkbox patched one row and the counter that depends on it, and nothing else.
None of the other frameworks can express this, because none of them know what changed until after they
have diffed. It catches a real class of bug: if a list is keyed by a value that changes, the page looks
identical, but every edit re-sends the whole list. Breaking `key(todo.id)` in the sample leaves 15 of
its 16 tests passing, and the one that fails is this assertion.

**Hibernation.** An idle session drops from about 136 kB to about 364 bytes. Values declared with
`rememberSaved` are stored and the composition is destroyed; values declared with `remember` are
discarded as temporary. That makes an idle user roughly 390 times cheaper to keep. LiveView remounts
instead of restoring, and Blazor loses the circuit.

**Test tags that cost nothing.** `testTag("draft")` names a node for tests without adding anything to
the page, unlike a `data-test` attribute that every user downloads. `exposeTestTags` writes them out for
browser tests, and is off in production.

**One language, with no serialization layer in between.** A handler captures the objects it needs and
calls application code directly. There is no client-side state to invalidate, no API to design, and
validation rules sit next to the code that uses them. Every framework on this page is built on this idea.
Jetlin's version is unusually direct because Kotlin is statically typed and Compose already provides an
incremental UI runtime.

---

## When to choose something else

- **You need to ship to production this quarter.** Use LiveView, Livewire or Blazor. They are mature,
  published, documented and supported.
- **Your users have high latency or unreliable networks.** Every framework here suffers in those
  conditions, but the mature ones have years of mitigations. Jetlin has `clientOnly` and little else.
- **Your content is mostly static.** Use Hotwire or plain server rendering. Keeping a live session for
  each reader costs resources and gives nothing in return.
- **You need offline support.** None of these frameworks can provide it. Use a client-side framework.
- **You're on the JVM and want this approach now.** Vaadin Flow is mature and commercially supported.
  It uses component objects rather than a declarative style, which makes code read quite differently,
  but it works and it ships.

Consider Jetlin if you write Kotlin, want declarative server-driven UI, and are comfortable working
with an early-stage project.
