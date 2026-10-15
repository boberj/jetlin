# Spike: the Jetlin browser client in Kotlin Multiplatform

> **Throwaway.** This directory answers one question and isn't meant to be maintained. It's a
> standalone Gradle build that the root `settings.gradle.kts` doesn't include, so it can't affect
> `./gradlew build`. Delete it whenever it stops being useful.

**The question:** what would it cost to write `jetlin-client` (today ~940 lines of TypeScript,
shipped as a 9 KB minified `jetlin.js`) in Kotlin instead, and what would we learn along the way?

**The short answer:** a Kotlin client that shares the real protocol code with the server is about
**27× larger over the wire** (88 KB vs 3.3 KB gzipped). It works: it passed the full browser test
suite on its first run. A Kotlin client small enough to compete gives up that shared protocol, and
with it the main reason to use Kotlin here. Recommendation at the end.

## What was built

Three ports of `jetlin-client/src/jetlin.ts`, all exposing the same `window.Jetlin` API, so they
drop in for `jetlin.js` without changing the server or the page:

| Variant | Source | What it is |
| --- | --- | --- |
| **Shared protocol**, Kotlin/JS and Kotlin/Wasm | [`typed/src/webMain`](typed/src/webMain/kotlin/jetlin/client) | The "real" multiplatform version. One `webMain` source set compiled to **both** JS and Wasm. It compiles the actual `jetlin-protocol/src/main/kotlin` files (not a copy) and decodes frames into the same `@Serializable` classes the server encodes from, using kotlinx.serialization and the `kotlinx-browser` DOM declarations. |
| **Dynamic**, Kotlin/JS only | [`dynamic/src/jsMain`](dynamic/src/jsMain/kotlin/jetlin/client/Jetlin.kt) | A size floor. A near-transliteration of the TypeScript: JSON is read through unchecked `external interface`s, and it uses JS `Map`/`WeakMap`/arrays instead of Kotlin collections. It shares nothing with the server. |

Every variant goes through **the same esbuild minification as the TypeScript client** (`--bundle
--format=iife --minify --target=es2020`), so the comparison measures the language and its runtime,
not the minifier. The TypeScript row is rebuilt from source by the same script.

## Results

### Size of what the browser downloads

From [`measure.sh`](measure.sh) (Kotlin 2.4.10, kotlinx.serialization 1.11.0, kotlinx-browser 0.5.0):

| Variant | raw | gzip -9 | brotli -11 | vs. TS (gzip) |
| --- | ---: | ---: | ---: | ---: |
| **TypeScript (current)** | 9.0 KB | 3.3 KB | 2.9 KB | 1× |
| Kotlin/JS, dynamic | 13.0 KB | 5.0 KB | 4.5 KB | 1.5× |
| Kotlin/JS, shared protocol | 307.4 KB | 88.3 KB | 71.6 KB | 27× |
| Kotlin/Wasm, shared protocol (`.wasm` only) | 325.3 KB | 108.1 KB | 87.0 KB | 33× |
| Kotlin/Wasm, shared protocol (`.wasm` + JS glue + loader) | 338.0 KB | 111.9 KB | 90.3 KB | 34× |

Where the 307 KB of the shared-protocol JS build comes from (esbuild metafile, after minification):

| Kotlin module | bytes | share |
| --- | ---: | ---: |
| Kotlin stdlib | 127,186 | 40% |
| kotlinx-serialization-json | 65,216 | 21% |
| The client + protocol + generated serializers | 61,527 | 20% |
| kotlinx-serialization-core | 60,865 | 19% |

So about 80% of the shared-protocol build is libraries, and the stdlib alone is 14× the whole TS
client.

### Does it actually work?

The full Playwright suite (`e2e/live.spec.ts`, 36 tests) was run against each variant, unmodified,
with [`swap-proxy.mjs`](swap-proxy.mjs) in front of the demo serving the alternate client:

| Variant | Result |
| --- | --- |
| TypeScript, direct and through the proxy | 36/36 (baseline, and proof the proxy is transparent) |
| Kotlin/JS, shared protocol | **36/36 on the first run** |
| Kotlin/JS, dynamic | 13/36 on the first run, 36/36 after fixing three `dynamic` bugs (see below) |
| Kotlin/Wasm, shared protocol | 33/36 on the first run, **35/36** after one fix. The remaining failure is a real behavior change, not a bug (see below). |

### Time until the client is live

From [`bench-startup.mjs`](bench-startup.mjs): navigation start until the client sends its `hello`
frame. The server is on localhost, so download time is ~0 and this is parse, compile and startup
cost. Chromium's CPU is throttled 4× to approximate a mid-range phone. 15 runs per variant:

| Variant | median | min | max |
| --- | ---: | ---: | ---: |
| TypeScript | 133 ms | 119 ms | 195 ms |
| Kotlin/JS, dynamic | 116 ms | 98 ms | 151 ms |
| Kotlin/JS, shared protocol | 179 ms | 154 ms | 236 ms |
| Kotlin/Wasm, shared protocol | 187 ms | 155 ms | 209 ms |

TypeScript vs. dynamic is within noise. The shared-protocol builds cost roughly 45–55 ms more to
start, **before** counting the extra ~85 KB of download on a real network.

### Build

| | TypeScript | Kotlin |
| --- | --- | --- |
| Toolchain | esbuild (one npm package) | Gradle + Kotlin Gradle plugin; Binaryen, which Gradle downloads, for Wasm |
| Production build (warm Gradle daemon) | ~0.01 s | ~3 s (dynamic), ~7 s (shared protocol JS), ~12 s (Wasm + Binaryen) |
| Lines of code, excluding comments and blanks | 578 | 549 shared protocol (no wire types: it reuses the protocol's), 568 dynamic |

## What we learned

### 1. Sharing the protocol is cheap to *compile* and expensive to *ship*

`jetlin-protocol` compiled for JS and Wasm **without a single change**. It has no JVM-only
dependencies, so turning it into a multiplatform module would be a build-file change. That's worth
knowing on its own, for example to let a future Kotlin test client or tool reuse it.

The price shows up at runtime. Decoding into those classes needs kotlinx.serialization, which
needs a large part of the stdlib, and together they're about 80% of the 300 KB. Even the protocol's
`Long` fields (`rev`, `ack`, `seq`) bring in Kotlin/JS's 64-bit integer emulation, because a JS
number can't hold a `Long` exactly.

### 2. The type safety is real: the typed port passed first time, the untyped one didn't

The shared-protocol port passed all 36 browser tests on its first run. The compiler checks every
`when` over `ServerMessage` and `Op` for exhaustiveness, and there was no hand-written mirror of the
wire types to drift.

The dynamic port compiled with **no errors and no warnings** and then failed 23 tests. All three
bugs came from the same Kotlin/JS rule: on a `dynamic` receiver, *every* member-style call is
emitted as a JS method call, including the Kotlin stdlib's own scope functions. So:

- `listeners.get(id)?.get(event.type)`, meant as a property lookup, became a call to a method
  named `get`.
- `spec.attrs?.let { ... }` became a call to a method named `let`, which no JSON object has.

Untyped Kotlin is therefore *less* safe than the TypeScript it replaces: TypeScript at least
type-checks the shapes it declares.

### 3. Kotlin/JS size is a cliff, not a slope

The first version of the dynamic port was **34.0 KB** (12.1 KB gzipped), and 69% of that was
stdlib. Replacing six innocuous calls brought it to **13.0 KB** (5.0 KB gzipped):

- `String.split` and `toInt()`
- `lowercase()`
- `getOrNull`
- `throw Error(...)` (Kotlin's own exception hierarchy)
- `Node.COMMENT_NODE` (a Kotlin companion object)

Any of those pulls in `ArrayList`, `StringBuilder`, the exception classes or the `Long` emulation.
A small client therefore depends on every contributor knowing which stdlib calls are expensive,
and nothing warns when someone uses one. Keeping it small would need a byte-size budget check in CI.
The 13 KB version is also, in practice, JavaScript written in Kotlin syntax.

### 4. Shared `webMain` code can behave differently on JS and Wasm

This was the most surprising finding. The page calls `Jetlin.connect({ token })` without an
`adopt` property. Read through an `external interface` as `val adopt: Boolean?`, the missing
property is **`null` on Kotlin/JS but `false` on Kotlin/Wasm**. The same `adopt != false` check
therefore adopted the server-rendered markup on one target and silently skipped adoption on the
other. Skipping it is the fallback path: correct-looking, but it throws away the DOM and re-renders
it. Both targets compiled without warnings, and only the browser tests caught it (the fix is
`adoptRequested` in [`Interop.kt`](typed/src/webMain/kotlin/jetlin/client/Interop.kt)). A single
shared source set doesn't mean a single tested behavior: each target would need its own e2e run.

### 5. Wasm changes the loading contract, not just the size

- **It can't be a drop-in classic `<script>`.** The page calls `Jetlin.connect` synchronously right
  after loading `jetlin.js`, but a Wasm module has to be fetched, compiled and instantiated
  asynchronously. The Wasm build needs [`wasm-loader.js`](wasm-loader.js), a JS shim that records
  calls and replays them once Kotlin's `main` has run. That means three files (shim, ES-module
  glue with top-level `await`, `.wasm`), and the server would need to serve `.wasm` as
  `application/wasm` (today `Ktor.kt` serves exactly one script).
- **The client isn't live at the `load` event any more.** The one remaining e2e failure asserts that
  the adoption-failure warning has been logged by the time `page.goto` resolves. With Wasm, it
  arrives about 30 ms later. The test is right to expect it: today the client is guaranteed to be
  running by then. More importantly, a click in that window has no listener at all. The TS client's
  outbox covers the gap between script and socket, but nothing covers the gap before the script runs.
- **Binaryen is not optional.** Without it, the `.wasm` is 1.15 MB; with it, 333 KB.
- The generated glue still carries Node and Deno loading branches, which a browser never runs, and
  WasmGC requires a recent browser on every engine.

### 6. Interop friction, in numbers

The shared-protocol client needs **21 `js("...")` escape hatches** (all in
[`Interop.kt`](typed/src/webMain/kotlin/jetlin/client/Interop.kt), each labelled with why):

- **The interop is experimental.** All of it (`JsAny`, `js()`) needs an opt-in to
  `kotlin.js.ExperimentalWasmJsInterop`.
- **`js("...")` doesn't accept modern JS.** The strings are parsed by Kotlin's own JS parser, which
  rejects ES2020 syntax: no `?.`, no `??`.
- **`kotlinx-browser` lags the DOM.** Its declarations have no `replaceChildren`, no `CSS.escape`,
  and no iterable `FormData`.
- **Casts are checked.** A cast to an external class is an `instanceof` check, so reading `.value`
  from "whatever the event target is", as TS does, needs a `js()` helper. Otherwise a `<textarea>`
  throws.
- **Missing language-level pieces.** Common Kotlin has no `WeakMap`, so the node→id map has to
  delete its entries by hand.
- **Exports don't fit the page.** `@JsExport` doesn't produce a global for a classic script (and in
  Wasm, exports live on the instance), so `window.Jetlin` is installed by hand.
- **Optional methods don't fit external interfaces.** A client component's `update`/`unmount` are
  optional JS methods, which an `external interface` can't express.
- **Opaque JSON has to round-trip.** Client component payloads are opaque JSON, so they go
  `JSON.stringify` → `parseToJsonElement` to become the protocol's `JsonObject`.

### 7. One thing Kotlin would genuinely improve

Today `jetlin.js` is a checked-in build artifact that a separate npm build regenerates. CI has to
rebuild it and diff it to catch drift. A Kotlin client could be built by Gradle as part of
`jetlin-server-ktor`'s resources, so the artifact and the diff check would both go away. That's a
real simplification, but it's an argument about build plumbing, and it doesn't outweigh a 27× larger
download.

## Recommendation

**Keep the TypeScript client.** Its size and synchronous startup are part of Jetlin's design
(the page is interactive server-rendered HTML, and the runtime is "deliberately small"). The only
Kotlin version that matches them is the dynamic one, which shares nothing with the server and is
less type-safe than the TypeScript it would replace.

The real benefit found here is **protocol drift protection**, and that can be had without shipping
Kotlin to the browser. Two options:

- Generate the TS type declarations from `jetlin-protocol` (for example, by walking the
  kotlinx.serialization descriptors).
- Keep golden JSON fixtures that are encoded by a Kotlin test and type-checked by `tsc`.

Either would replace the "keep them in step" comment in `jetlin.ts` with a check, at zero runtime
cost.

## Running it

```bash
npm --prefix ../../jetlin-client install   # for esbuild
./measure.sh                                # builds every variant into dist/ and prints the tables
```

`settings.gradle.kts` puts Google's mirror of Maven Central first, because Maven Central was
rate-limiting (HTTP 429) the environment this was written in.

To rerun the browser tests against a variant, start the demo (`./gradlew :samples:demo:run` from the
repository root; it needs Gradle running on JDK 24+), then:

```bash
node swap-proxy.mjs kjs-typed 8081 8080 &   # or ts, kjs-dynamic, wasm
cd ../../e2e && JETLIN_URL=http://localhost:8081 npx playwright test
```

And for the startup benchmark, with the demo running: `node bench-startup.mjs 15`.
