# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Jetlin renders interactive web UI as Kotlin `@Composable` functions that run **on the server**,
using the Compose runtime with no UI toolkit underneath it. The browser gets server-rendered HTML
plus a small TypeScript runtime that applies DOM patches and reports events back over a
WebSocket.

`docs/architecture.md` is the full design doc — read it before touching sessions, hibernation, the
protocol, or the applier; it explains *why*, not just what, and documents which behaviors are
deliberate rather than accidental. `README.md` has the pitch and quick examples. Both can drift from
the version catalog (e.g. architecture.md discusses a since-superseded Compose pin) — treat
`gradle/libs.versions.toml` and the build files as the source of truth for versions.

## Commands

```bash
./gradlew build                                  # compile + unit tests, all modules
./gradlew test                                   # unit tests only
./gradlew :jetlin-html:test --tests "jetlin.html.LiveViewTest"              # one test class
./gradlew :jetlin-html:test --tests "jetlin.html.LiveViewTest.specific *"   # one test method (backticked names need a glob)

./gradlew :samples:demo:run                      # run the demo at http://localhost:8080
./gradlew :samples:demo:benchmark                # retained heap per session, live vs hibernated

npm --prefix jetlin-client install
npm --prefix jetlin-client run build              # rebuilds the checked-in jetlin.js
npm --prefix jetlin-client run check              # tsc --noEmit

cd e2e && npm install && npx playwright test      # browser tests — demo must already be running
```

- The unit test suite asserts **exact op streams**, not `contains` — a change that patches more of
  the page than necessary fails the build. Follow that convention for new framework tests.
  `jetlin-testing`-based tests (used by the sample app and meant as the model for application code)
  are looser and describe user-visible behavior instead.
- `jetlin-server-ktor/src/main/resources/jetlin/jetlin.js` is a **checked-in build artifact** of
  `jetlin-client`. The Gradle build does not regenerate it — if you touch `jetlin-client/src`, rerun
  the `npm run build` above so the two stay in sync (CI enforces this by rebuilding and diffing).
- `:conventions` has no production code; it encodes repo-wide rules the compiler can't express as
  ordinary Konsist-based tests (e.g. every `@Test` function must return `Unit`, since Kotlin's
  expression-bodied tests can silently vanish from JUnit otherwise). It runs as part of
  `./gradlew build`.
- CI (`ci/github-actions.yml`) is **not wired up** — it hasn't been moved to
  `.github/workflows/` (see `ci/README.md` for why). Don't assume GitHub Actions runs on push.
- Library modules (anything prefixed `jetlin-`) have `explicitApi()` turned on — public declarations
  need explicit visibility and return types. `samples/*` are applications and are exempt.

## Architecture

### Module dependency chain

```
jetlin-protocol   (ops + messages, kotlinx.serialization — the wire vocabulary)
      ▲
jetlin-runtime    (CompositionHost, FramePolicy, GlobalSnapshotManager — Compose headless on the JVM)
      ▲
jetlin-html       (LiveView, HtmlApplier, virtual DOM, elements, routing, forms, HTML serializer)
      ▲
jetlin-server-ktor (HTTP + WebSocket endpoints, SessionRegistry, rate limiting)

jetlin-testing    (drives a jetlin-html view headlessly — no browser, server or socket)
jetlin-client     (TypeScript browser runtime; builds into jetlin-server-ktor's resources)
```

`jetlin-html` is deliberately portable: `LiveView` knows nothing about WebSockets or Ktor and can be
driven directly from a test. Server adapters are additive on top of it.

### The update loop

1. An inbound event names a node id and event type; the server looks up the handler lambda held for
   that pair and calls it inside a mutable snapshot (`transact { }` = one
   `Snapshot.withMutableSnapshot` per handler call, however many state writes it makes).
2. Compose recomposes only the composables that read the state that changed.
3. `HtmlApplier` (extends `AbstractApplier`) records every insert/remove/move/attribute/text edit as
   an op while the tree is rebuilt. The buffer is drained once per recomposition pass into one patch.

Nothing diffs two tree versions — the ops **are** the diff, because the runtime already knows what
it changed. The same loop runs for server-originated writes (a timer, a shared store another session
touched): `GlobalSnapshotManager` is what turns writes made *outside* a composition into apply
notifications, since nothing does that automatically outside a UI toolkit.

Each session runs on `Dispatchers.Default.limitedParallelism(1)` — event handling, recomposition
and patch draining are serialized by construction, not by locking.

### Sessions, hibernation, adoption (`jetlin-server-ktor`)

- A `GET` renders HTML once and stores the live composition in `SessionRegistry`; the WebSocket
  reattaches to it by token rather than re-rendering. Adoption walks the server-rendered DOM instead
  of resending the tree — any mismatch falls back to a full `Reset` rather than guessing.
- Sessions go live → orphaned (socket gone, composition kept for a grace period) → hibernated
  (`rememberSaved` state persisted via `SessionStore`, composition torn down). `remember` is scratch
  space and is *not* preserved.
- Jetlin is single-node by design today — `SessionStore` is shaped for a shared backend, but three
  windows (socket attached, render→connect gap, disconnect grace period) are node-local regardless of
  where snapshots are stored, so a shared store alone would not make it multi-node.

### Testing application views (`jetlin-testing`)

Views are tested headlessly through `runViewTest`/`setContent`, using `testTag` (server-side only,
never serialized to the DOM unless `JetlinConfig.exposeTestTags` is on) and matcher-based queries
(`onNode(hasTestTag(...))`, `within(...)` to scope by subtree). Two assertions have no client-side
testing equivalent and are worth reaching for:
- `recordUpdate { }.assertOnlyWithin(...)` — asserts which nodes an interaction actually touched;
  catches broken `key(...)` usage that produces correct HTML but re-sends far more than necessary.
- `hibernateAndRestore()` — drives a session through the real hibernate/wake cycle to check that the
  right state was (or wasn't) declared with `rememberSaved`.
