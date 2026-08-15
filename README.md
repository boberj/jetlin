# Jetlin

Interactive web UI written as Kotlin `@Composable` functions that run **on the server**. The browser
gets server-rendered HTML and a 4.6 kB runtime that applies the DOM mutations the server computes.
Phoenix LiveView and Livewire in spirit; the Compose runtime as the engine.

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

Clicking that button sends one event and receives exactly one `SetText` op. No template language, no
client-side diffing, no morphing, no REST layer in between.

## Why the Compose runtime

LiveView compiles templates into static/dynamic slots with fingerprints; Livewire re-renders whole
components and morphs them on the client. Both are recovering information they lost, because their
view layer is a pure function re-run from scratch.

The Compose runtime is already an incremental tree engine — it knows precisely which nodes changed,
appeared, moved or disappeared, and it hands those mutations to an `Applier`. Point that Applier at a
virtual DOM and the patch stream falls out for free, along with `remember`, `LaunchedEffect`,
`derivedStateOf`, `key` and coroutines.

Server push needs no API at all: a coroutine writes state, and the composables that read it
recompose.

```kotlin
var ticks by remember { mutableStateOf(0) }
LaunchedEffect(Unit) { while (true) { delay(1000); ticks++ } }
```

## Status

**Walking skeleton** — the core is built, tested, and working end to end in a real browser.
[`docs/architecture.md`](docs/architecture.md) is the full design: the thesis, the protocol, the two
hard problems (input safety and memory), design forks with rejected alternatives, and what is
designed but not yet built (hibernation, SSR DOM adoption, back-pressure, routing, forms, uploads).

Measured: **134 kB of retained heap per live session**, 1000 concurrent sessions in 131 MB. For
calibration, LiveView is reported at ~3 MB per active connection and ~150 kB hibernated.

## Try it

```bash
./gradlew :samples:counter:run          # http://localhost:8080
```

The demo has a counter, a keyed todo list, and a server-pushed clock.

## Test

```bash
./gradlew test                          # 15 unit tests, asserting exact op streams
./gradlew :samples:counter:benchmark    # retained heap per session

cd e2e && npm install && npx playwright test    # 7 browser tests (server must be running)
```

The unit tests assert on exact op lists rather than `contains`, so any extra protocol chatter fails
the build. The browser tests cover first paint without JavaScript, granular patching, keyed list
reordering, unprompted server push, typing safely while the server pushes updates, and reconnection
with state preserved.

## Modules

| Module | Contents |
|---|---|
| `jetlin-runtime` | `CompositionHost`, `FramePolicy`, `GlobalSnapshotManager` — hosting a Compose composition headlessly on the JVM |
| `jetlin-protocol` | Ops and messages (kotlinx.serialization) |
| `jetlin-html` | `LiveView`, `HtmlApplier`, the virtual DOM, element composables, SSR serializer |
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
Central; 1.6+ pulls androidx artifacts published only to Google's Maven repo, which the development
environment could not reach. Nothing here depends on anything newer — `Applier`, `Composition`,
`Recomposer` and the snapshot system are stable across all of these — so it is a one-line bump.
