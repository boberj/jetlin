# Jetlin

Interactive web UI written as Kotlin `@Composable` functions that run **on the server**. The browser
gets HTML plus a 4.6 kB runtime that applies the DOM changes the server sends and reports events
back.

Inspired by [Phoenix LiveView](https://github.com/phoenixframework/phoenix_live_view) and
[Livewire](https://livewire.laravel.com/).

```kotlin
@Composable
fun Counter() {
    var count by remember { mutableStateOf(0) }

    Div({ classes("card") }) {
        H1 { Text("Count: $count") }
        Button({ classes("btn"); onClick { count++ } }) { Text("+") }
    }
}

fun main() {
    embeddedServer(Netty, port = 8080) {
        jetlin { view("/", title = "Counter") { Counter() } }
    }.start(wait = true)
}
```

Clicking that button sends one event and receives one `SetText` instruction. There is no template
language, no client-side state, and no REST layer in between.

## How it works

A composition runs on the server for as long as the session lasts.

1. An event arrives naming a node and an event type. The server calls the handler lambda it holds
   for that pair.
2. State changes. Compose knows which composables read which state, so it knows which are now stale.
3. Only those composables re-run.
4. As the runtime edits its node tree, each insert, remove, move and attribute or text write is
   recorded. Jetlin's node tree is a virtual DOM, so those recorded edits are the wire protocol.

Nothing compares two versions of the page — the runtime tracked the reads, so it already knows what
changed. Reordering a keyed list moves the existing nodes rather than rebuilding them.

The same path runs when the browser did nothing at all. A coroutine that writes state invalidates
the composables that read it, and an update follows:

```kotlin
var ticks by remember { mutableStateOf(0) }
LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }
```

Because UI state lives on the server, there is no client cache to invalidate and no serialization
boundary to design — a handler closes over the objects it needs and calls straight into your code.

## Status

**Walking skeleton.** The core is built, tested, and working end to end in a browser.
[`docs/architecture.md`](docs/architecture.md) has the full design: the update path, the protocol,
sessions, input handling, design decisions, and what is designed but not yet built (hibernation,
adopting server-rendered DOM, back-pressure, routing, forms, uploads).

Measured at **134 kB of retained heap per live session**, 1000 concurrent sessions in 131 MB. Since
session state lives on the server, this is the number that sets how many users a node can carry.

## Try it

```bash
./gradlew :samples:counter:run          # http://localhost:8080
```

The demo has a counter, a keyed todo list, and a clock driven from the server.

## Test

```bash
./gradlew test                          # 15 unit tests, asserting exact op streams
./gradlew :samples:counter:benchmark    # retained heap per session

cd e2e && npm install && npx playwright test    # 7 browser tests (server must be running)
```

Unit tests assert on exact op lists rather than `contains`, so an update that touches more of the
page than it needs to fails the build. Browser tests cover first paint with JavaScript blocked,
targeted patching, keyed list reordering, server-originated updates, typing while the server sends
unrelated updates, and reconnection with state preserved.

## Modules

| Module | Contents |
|---|---|
| `jetlin-runtime` | `CompositionHost`, `FramePolicy`, `GlobalSnapshotManager` — running a Compose composition headlessly on the JVM |
| `jetlin-protocol` | Ops and messages (kotlinx.serialization) |
| `jetlin-html` | `LiveView`, `HtmlApplier`, the virtual DOM, element composables, HTML serializer |
| `jetlin-server-ktor` | HTTP + WebSocket endpoints, session registry |
| `jetlin-client` | TypeScript browser runtime (`npm run build` → checked-in `jetlin.js`) |
| `samples/counter` | Runnable demo and the memory benchmark |

## Building the client

The bundled `jetlin.js` is checked in, so the Gradle build needs no npm:

```bash
npm --prefix jetlin-client install && npm --prefix jetlin-client run build
```

## Note on dependency versions

Compose Multiplatform is pinned to **1.5.12**, the newest release that resolves entirely from Maven
Central; 1.6 and later pull androidx artifacts published only to Google's Maven repo, which the
development environment could not reach. Nothing here depends on anything newer — `Applier`,
`Composition`, `Recomposer` and the snapshot system are stable across all of these — so it is a
one-line bump.
