# UI sessions: improvements learned from Vaadin

This plan is a proposal. None of it is implemented. It collects ten improvements to how Jetlin
keeps, restores, and ends UI sessions. Most of them come from comparing Jetlin with Vaadin, whose
session model is the closest to Jetlin's among established frameworks. Each improvement stands on
its own, so they can be picked up separately and in any order. §4 suggests an order.

- Audience: Claude Code, working in the Jetlin repository.
- Background reading: `docs/architecture.md` §1, §7 (UI sessions and transport, including "Binding
  tokens to the browser" and "Signing in"), and §9 (memory, hibernation, and back-pressure). Then
  `UiSessionRegistry` in `jetlin-server-ktor/src/main/kotlin/jetlin/server/UiSession.kt`,
  `UiSessionStore` in `UiSessionStore.kt`, the WebSocket loop in `Ktor.kt`, `LiveView.dispatch` and
  `LiveView.hibernate` in `jetlin-html/src/main/kotlin/jetlin/html/LiveView.kt`, `ClientMessage` in
  `jetlin-protocol/src/main/kotlin/jetlin/protocol/Messages.kt`, and `open`, `sendRaw`, and
  `scheduleReconnect` in `jetlin-client/src/jetlin.ts`.

## 1. Background

### 1.1 Two kinds of session

Jetlin has two kinds of session, and this plan is careful to keep them apart:

- A **UI session** is one loaded page: a composition on the server, named by a token in the page.
  Navigating within the page keeps it, and reloading starts a new one. `UiSessionRegistry` holds the
  live ones, and `UiSessionStore` holds the hibernated ones.
- A **sign-in session** is the `jetlin_session` cookie that `jetlin-server-ktor-auth` manages. It
  covers every page of a browser. Each UI session is bound to it through a hash of the cookie's
  session ID, the *binding*.

### 1.2 How Vaadin compares

Vaadin has the same split under different names. `VaadinSession` is per browser, wraps the servlet
`HttpSession`, and holds a lock, a locale, an error handler, and arbitrary attributes. `UI` is per
tab, is created on each page load, and holds the component tree. Spring and CDI add matching scopes:
`@VaadinSessionScope` for the browser, `@UIScope` for the tab, and `@RouteScope` for state that
lasts while the user stays under one route or layout.

Jetlin already does several things as well as Vaadin or better, and nothing in this plan should undo
them:

- **Concurrency.** Vaadin locks the whole `VaadinSession`, so all tabs of one browser serialize on
  one lock, and background threads must go through `ui.access { }`. Each Jetlin UI session runs on
  its own single-threaded dispatcher, so tabs don't block each other and there's no lock to forget.
- **Sign-out.** After a Vaadin logout, other tabs keep working until their next request fails.
  Jetlin ends them immediately with `endUiSessions`.
- **Session fixation.** Vaadin offers `VaadinService.reinitializeSession`. Jetlin always issues a
  new session ID on sign-in.
- **Idle memory.** A Vaadin `UI` stays in memory until it times out, unless the application uses the
  commercial Kubernetes Kit. Jetlin hibernates an idle UI session from about 136 kB to about 364
  bytes, with nothing to install.
- **Navigation scopes.** `remember` in `app { }` covers `@UIScope`, and `rememberSaved` in a view
  covers most uses of `@RouteScope`.

The gaps are the rest of this plan.

## 2. Principles

- **Never lose a user action silently.** An event the user made either takes effect exactly once, or
  the page says that it didn't.
- **Hibernation is the answer to memory, not expiry.** Where Vaadin closes a `UI` to save memory,
  Jetlin should hibernate it and keep the user's place.
- **The binding is the security boundary for resuming.** Anything that reattaches a browser to an
  existing UI session, or shares state between its tabs, goes through the same binding check that
  attaching a socket does today.
- **Defaults stay safe and cheap.** Features that change what a reload or a closed tab means are
  opt-in. Features that only prevent loss are on by default.

## 3. The improvements

Each section says what happens today, what Vaadin does, and what Jetlin should do.

### 3.1 Hibernate every UI session when the server shuts down

**Today.** `UiSessionRegistry` launches its reapers in the `Application`'s scope, and UI sessions
only hibernate when a reaper runs after `disconnectGrace`. When the application stops, the scope is
cancelled and every live UI session is dropped. That's true even with a persistent `UiSessionStore`,
so every deploy throws away every open page's unsaved state, which is exactly what the store exists
to keep.

**Vaadin.** Kubernetes Kit, a commercial add-on, serializes sessions to Hazelcast or Redis and
supports rolling updates that move users to the new version.

**Proposal.** Subscribe to Ktor's `ApplicationStopping` in `Application.jetlin`, and call a new
`UiSessionRegistry.hibernateAll(deadline)`:

1. Stop accepting new UI sessions. Page renders get the existing `503` with `Retry-After`.
2. Send each attached socket a new non-fatal `ServerMessage.Error` meaning "the server is
   restarting", then close it. The client treats it like any other disconnect: it shows
   `jl-disconnected` and reconnects with its normal backoff.
3. Hibernate every live UI session concurrently, bounded by one overall deadline, using the same
   path as a reaper (`LiveView.hibernate`, then `store.save`). A UI session that misses the deadline
   is closed without saving, and the number of those is logged once.

When the next process starts, the reconnecting clients wake their UI sessions from the store.

**Details.**

- With `InMemoryUiSessionStore`, saving is pointless because the store dies with the process. Skip
  step 3 and say so in a log line, so nobody believes their sessions survived.
- Ktor's engine has its own `shutdownGracePeriod` and `shutdownTimeout`. The deadline must fit
  inside them. Document that, and log a warning at startup if it can't.
- A rolling deploy also changes code, so a snapshot saved by one version may be woken by the next.
  `UiSessionSnapshot.version` is the format version, not the application's. See open question 1.

### 3.2 Resend events that the server never acknowledged

**Today.** The client numbers each event with `seq`, and each patch echoes the highest one it
includes as `ack`. While the socket is closed, `sendRaw` queues frames in a bounded outbox. But a
frame that was sent on a socket that then died, before the server processed it, is gone: the client
doesn't keep sent frames, and the server doesn't know which `seq` it last processed.
`LiveView.dispatch` overwrites `ack` with whatever arrives, and doesn't reject a repeat.

**Vaadin.** The client keeps each message until the server acknowledges it, and resends what's
outstanding after reconnecting. The server uses sync IDs to detect messages that are duplicated or
missing, and asks the client to resynchronize when they don't line up.

**Proposal.**

- The client keeps every event frame it sends until a patch acknowledges its `seq`. When it
  reconnects, it resends the unacknowledged frames before the outbox, in order. Both buffers stay
  bounded, and dropping a frame because a buffer is full is reported the way a non-fatal error is.
- `LiveView` records the highest `seq` it has processed, and ignores an `Event` whose `seq` isn't
  higher. Handlers aren't idempotent, so this check is what makes resending safe.
- The `seq` the server has processed goes into `UiSessionSnapshot`, so a UI session woken from
  storage rejects repeats too.
- The reply to `Hello` (`Reset` or the adoption reply) carries the processed `seq`, so the client
  can drop frames that were processed but whose patch was lost.

**Details.** An event whose handler changes nothing produces no patch today, so its `seq` is never
acknowledged. Either send an acknowledgement without ops in that case, or acknowledge on the next
patch. The first is simpler for the client. Also check that the stale-write guard in the client
(`sentFrom`) still behaves when frames are resent.

### 3.3 Lifecycle listeners

**Today.** `JetlinConfig.onError` is the only hook. An application can't count UI sessions, audit
them, or release resources that aren't Compose state when one ends.

**Vaadin.** `SessionInitListener`, `SessionDestroyListener`, `UIInitListener`, and detach listeners
on every component.

**Proposal.** One listener on `JetlinConfig`, in the style of `onError`:

```kotlin
jetlin {
    onUiSession = { event -> metrics.record(event) }
}
```

`event` is a sealed type: `Created`, `Attached`, `Detached`, `Hibernated`, `Woken`, and
`Ended(reason)`, where the reason is sign-out, failure, capacity, shutdown, or a handoff that never
came. Each event carries the URL, whether the UI session is bound, and the first eight characters of
the token, the same prefix the throttling log line uses. The full token is a credential and never
appears.

**Details.** The listener runs on a dispatcher of its own, not on the UI session's thread, so a slow
listener can't stall a page. An exception from it is logged and goes to `onError`, and never changes
what happens to the UI session. Inside a composition, `DisposableEffect` in `app { }` already covers
per-UI-session cleanup, and the KDoc should point there for anything that needs the composition.

### 3.4 Configurable system messages

**Today.** The texts a client can receive are constants in `Ktor.kt`: `HANDLER_FAILED`,
`SESSION_ENDED`, `TOO_FAST`, `SESSION_FAILED`, the "unknown or already-attached" error, and the
static `AT_CAPACITY_PAGE`. They're English, and an application can't change them.

**Vaadin.** `SystemMessages` lets an application set the caption, the message, and a redirect URL
for "session expired", "communication error", "internal error", and similar, and a
`SystemMessagesProvider` can choose them per locale.

**Proposal.** Add `JetlinConfig.messages`, a class with one property per message, each a function of
the request so it can be localized from `Accept-Language`:

```kotlin
jetlin {
    messages = SystemMessages(
        uiSessionEnded = { request -> t(request, "session.ended") },
        atCapacityPage = { request -> render503(request) },
    )
}
```

The defaults are today's texts. The test that asserts the "This session has ended." text keeps
working unchanged.

### 3.5 Keep a UI session across a reload

**Today.** A reload is a new `GET`, which always creates a new UI session. Unsaved input and
everything held in `remember` are lost, and `rememberSaved` doesn't help, because the old UI session
isn't connected to the new page at all.

**Vaadin.** `@PreserveOnRefresh` on a route reuses the tab's existing `UI` on reload. Vaadin tells
tabs apart by `window.name`.

**Proposal.** Opt in per route, because some users reload on purpose to start over:

```kotlin
view("/issues/new", preserveOnReload = true) { CreateIssuePage() }
```

1. The client keeps its token in `sessionStorage`, which is per tab and survives a reload.
2. The `GET` can't see `sessionStorage`, so it renders a new UI session as it does today.
3. When the socket connects, `Hello` carries the previous token too. If the route of the URL opted
   in, the previous UI session is live or hibernated, and its binding matches, the server ends the
   new UI session, resumes the previous one, and replies with a `Reset`. Otherwise it carries on
   with the new one.
4. The resumed UI session starts a new `seq` series, because the reloaded page starts counting from
   zero again. See §3.2.

**Details.**

- Duplicating a tab copies its `sessionStorage`, so two tabs can present the same previous token.
  The first one wins, and the second gets a "previous UI session unavailable" answer and keeps its
  new UI session. It must never see an error.
- The new UI session's render is wasted when the old one is resumed. That costs one composition per
  reload, only on routes that opted in.
- A previous token that leaked is as useful as the page token is today, and no more: the binding
  check still applies.

### 3.6 State shared by a browser's tabs

**Today.** State lives either in one UI session (`remember`, `rememberSaved`) or in the whole
process (an object every UI session reads). There's nothing per browser: a cart, a selected
workspace, or a draft that every tab of the same browser should share.

**Vaadin.** `VaadinSession` attributes and `@VaadinSessionScope` beans. Vaadin has to push to every
tab explicitly when such state changes.

**Proposal.** A holder per binding, kept by the registry and reachable from a composition:

```kotlin
val cart = rememberForBrowser { Cart() }
```

Its values are ordinary snapshot state, so a write in one tab recomposes every tab of that browser
with no push code. That's the Jetlin model applied one level up.

**Details.**

- **Identity.** The binding exists only when `jetlin-server-ktor-auth` is installed. For a browser
  that isn't signed in, this needs an anonymous browser cookie. That's a new cookie with its own
  security review. See open question 2.
- **Lifetime.** The holder lives as long as the browser has UI sessions, live or hibernated, plus a
  grace period. It isn't persisted at first. Persisting it means picking a serialization format, the
  same question `rememberSaved` already answered.
- **Sign-in and sign-out.** Sign-in issues a new session ID and so a new binding, which gives the
  new identity an empty holder. Sign-out discards the holder. Neither identity can see the other's
  state.

### 3.7 Hibernate tabs that nobody is looking at

**Today.** A UI session stays fully live as long as its socket is open. A tab left in the background
for a day keeps its whole composition in memory.

**Vaadin.** With `closeIdleSessions`, heartbeats alone don't keep a session alive, so a session with
no user activity expires and is closed.

**Proposal.** Use the Page Visibility API instead of guessing from inactivity. Inactivity is the
wrong signal for Jetlin, because a dashboard that nobody touches but somebody watches is in use.

- The client sends a new `ClientMessage.Visibility(hidden)` on `visibilitychange`.
- After `JetlinConfig.hiddenHibernateAfter` (off by default), the server hibernates a hidden UI
  session while its socket stays open. It sends a message that tells the client the UI session is
  dormant, so the client doesn't treat it as a failure.
- When the tab becomes visible again, the client sends `Visibility(visible)`, the server wakes the
  UI session through the normal wake path, and replies with a `Reset`.

**Details.** Waking recomputes the principal from the socket's request, which is the same behavior
as waking after a disconnect. A role revoked while the tab was hidden takes effect when it's shown.

### 3.8 Release a closed tab right away

**Today.** When a tab closes, the socket closes, and the UI session sits in its grace period for
`disconnectGrace` (30 seconds) before it hibernates, as if the network had dropped. The client
suppresses reconnecting only for its own `load()` navigations. It doesn't handle `pagehide`.

**Vaadin.** The client tells the server when the page unloads, so the server can release the `UI`
early.

**Proposal.** On `pagehide` with `event.persisted == false`, so the page isn't going into the
back-forward cache, the client closes the socket with an application close code, such as `4001`,
that means "closed on purpose". The server then skips the grace period and hibernates immediately.
It hibernates rather than discards, so §3.5 can still resume it if the user comes straight back.

**Details.** A close frame sent while a page unloads isn't guaranteed to arrive. When it doesn't,
the grace period applies as it does today, so nothing gets worse. A `navigator.sendBeacon` to an
endpoint that checks the token and the binding is a possible addition if measurements show close
frames are often lost.

### 3.9 List a browser's UI sessions

**Today.** `UiSessionRegistry.endUiSessions(binding)` can end a browser's UI sessions, but nothing
can list them.

**Vaadin.** `VaadinSession.getUIs()`.

**Proposal.** `UiSessionRegistry.uiSessionsOf(binding): List<UiSessionInfo>`, and
`SessionAuth.uiSessions(call)` for the common case. `UiSessionInfo` holds the URL, whether a socket
is attached, and when the UI session was created. It doesn't hold the token, so nothing that lists
UI sessions can take one over. Uses include "you're signed in in three tabs" and limiting how many
tabs one browser may open. Sending something to the other tabs is §3.6's job, not this API's.

### 3.10 A fallback when WebSockets are blocked

**Today.** The socket is the only transport. Some corporate proxies block or break WebSockets, and a
Jetlin page behind one renders its server HTML but never becomes interactive.

**Vaadin.** Server push through Atmosphere falls back from WebSockets to long polling, and without
push, Vaadin works over plain HTTP requests.

**Proposal.** Not now. Record it so the transport stays replaceable: `jetlin-html` already knows
nothing about WebSockets, which is what makes this possible later. When a user reports the problem,
the likely design is server-sent events for patches and `POST` for client messages, carrying the
same `ClientMessage` and `ServerMessage` JSON.

## 4. Phases

Each phase can ship by itself. The order puts fixes for lost data first.

### Phase 1: shutdown and closed tabs (§3.1, §3.8)

Small and server-heavy. Tests: a `testApplication` that stops while UI sessions are live, with a
store that outlives it, and a second application that wakes them. A client close code test in
`e2e/`.

### Phase 2: resending events (§3.2)

Protocol, client, `LiveView`, and snapshot format. Bump `UiSessionSnapshot.CURRENT_VERSION`. Tests
drop the socket between sending an event and receiving its patch, and assert that the handler ran
exactly once. Rebuild `jetlin.js`.

### Phase 3: listeners and messages (§3.3, §3.4)

API only, no protocol change. Use the listener in `samples/demo` to log lifecycle events.

### Phase 4: keeping a UI session across a reload (§3.5)

Builds on the `seq` handling from phase 2. Try it in `samples/issue-tracker` on the create-issue
dialog.

### Phase 5: per-browser state and listing (§3.6, §3.9)

Starts with signed-in browsers only, so it doesn't depend on open question 2. Try it in
`samples/teams`.

### Phase 6: hibernating hidden tabs (§3.7)

Measure first with `:samples:demo:benchmark` and a realistic share of background tabs, to confirm
it's worth a protocol message.

§3.10 has no phase until someone needs it.

## 5. Alternatives considered

- **Expiring idle UI sessions, as Vaadin does.** Rejected. Hibernation keeps the user's place at
  almost no memory cost, and expiry throws it away.
- **A lock per browser, so tabs share state safely.** Rejected. Snapshot state already makes writes
  from different threads safe, and a lock would bring back the serialization between tabs that
  Jetlin avoids.
- **Keeping the UI session across a reload with a cookie instead of `sessionStorage`.** Rejected.
  Cookies are shared by every tab of a browser, so a reload in one tab couldn't tell which UI
  session was its own.
- **Doing the resend in the transport, with WebSocket-level acknowledgements.** Rejected. A UI
  session can wake on a different socket, or a different process, from the one that lost the frame.
  The acknowledgement has to be about the UI session, so it belongs in the protocol and the
  snapshot.

## 6. Open questions

1. **Snapshots across application versions.** A snapshot taken by one deploy may be woken by the
   next, whose `rememberSaved` keys or value types may differ. Should applications supply a version
   that the snapshot records, and should a mismatch wake as a fresh UI session on the same URL, or
   try to restore the keys that still match?
2. **Browser identity without sign-in.** §3.6 and §3.9 need a binding for browsers that aren't
   signed in. Is a second, anonymous cookie acceptable, or do those features require sign-in?
3. **Preserve on reload, per route or per application?** Vaadin does it per route. A per-application
   default with a per-route override may be simpler to explain.
4. **Hidden-tab hibernation default.** Off is safe. Is there a default that's safe for live
   dashboards, which are hidden in a background tab but expected to be current when shown?

## 7. Decision log

- 2026-10-11: Plan written after comparing Jetlin's session model with Vaadin's.
