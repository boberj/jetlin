# What building this found

The fleet list and vessel detail pages of `cygnifi/dashboard`, rebuilt on Jetlin. The original is a
TanStack Start application — React 19, server functions, Tailwind, a virtualized table — designed
with no reference to this framework, which is the whole value of it as a test: whatever it needs is
what a dashboard needs, not what Jetlin happens to be good at.

Everything Jetlin had been tested against before this was written to test Jetlin. That is a suite
that agrees with itself about what a web application looks like. This is the first thing built
against it that did not.

---

## 1. The numbers

Measured against the running sample: 80 vessels, all rendered, no windowing.

| | Size |
|---|---|
| First paint (HTML, 80 rows) | **971,851 bytes** |
| Re-sorting all 80 rows by name | **2,793 bytes** |
| Nudging one vessel's priority | **70 bytes** |
| Narrowing 80 rows to 1 by search | **3,581 bytes** |

The 70 bytes is the thesis of the framework stated as a measurement. One click, eighty rows on the
page, and what crosses the wire is the two cells that changed. Nothing compared two versions of the
page to work that out — the runtime already knew which composables had read the value that changed.
`FleetAppTest` asserts this rather than merely reporting it, which is the assertion `docs/comparison.md`
claims no other framework here can express, now stated at a scale where getting `key` wrong would
otherwise be invisible.

The first paint is the other side of the same coin, and the honest cost of finding 1 below.

---

## 2. Still open

### 1. Virtualization is not expressible, and this part is architectural

The original windows the table: 52-pixel rows, eight of overscan, `useVirtualizer`. A server cannot
do that, because a server cannot see the scroll position. So this ships all eighty rows, and the
first paint is 949 kB — eleven action icons, two meters and a progress ring per row, eighty times.

A `ClientComponent` reporting scroll offset back would be the obvious answer, and is not built. But
note what it would cost: the server would then be rendering a window whose position is client state,
which is a different framework from the one described in the README. This is the finding that has
not moved and is not obviously movable.

### 2. There is still no "current URL with one parameter changed"

`queryParam` reads; nothing writes. `fleetUrl()` in `VesselsPage.kt` reassembles the query string by
hand, including re-deciding which parameters to omit at their defaults. It is nine lines to express
"the same page, sorted the other way", and it is the only URL operation a page like this ever wants.

Smaller than it was — only the sort lives in the URL now that the search moved to the container — but
every application that puts state in a URL will write this function.

### 3. No tooltip affordance

Eleven action icons per row, each of which needs a label. `attr("title", …)` is the honest fallback
and is what this uses. Anything better is a positioned overlay, which is a component the framework
does not have and probably should not grow one of.

### 4. `awaitIdle()` does not cover a suspended effect

A page that loads behind `LaunchedEffect { … delay(600); … }` is not "busy" as far as the recomposer
is concerned — nothing is pending, the effect is simply suspended. So `awaitIdle()` returns while the
page is still showing its loading state, and four tests failed on it before `awaitDetail()` was
written to wait in real time first.

Nothing is broken; the name promises more than it can deliver. Either a way to wait for effects to
settle, or a sentence in the KDoc saying it does not.

### 5. State and event APIs are spread across three packages

One page imports `jetlin.html.Div`, `jetlin.runtime.rememberSaved` and `jetlin.protocol.ListenerSpec`.
Both of the non-`html` ones cost a failed compile to discover. The layering is right — the runtime
does not know about HTML, the protocol does not know about either — but the application sees the
seams, and an application does not care where the seam is.

### 6. `stopPropagation` has no sugar

A row that navigates on click and contains eleven buttons that must not needs
`on("click", ListenerSpec(stopPropagation = true)) { … }` eleven times, where `onClick { … }` would
otherwise do. `onClick(stopPropagation = true) { … }` is the obvious shape and does not exist.

### 7. `ClientComponent`'s worked example does not use the handle

`unmount(element, handle)` receives the handle, but the demo's `sparkline.js` — the only example —
declares `unmount(element)` and ignores it. Written from that example, the map's `unmount` had the
wrong arity and would have leaked a Leaflet instance per navigation, silently. The contract is right;
the example teaches a subset of it.

### 8. Two things belong to the client, and that is the framework being right

The Leaflet map and the drag-to-resize layout editor. The first is a third-party widget that patches
its own DOM continuously; the second is pointer-move state at 60 Hz. Both are exactly what
`ClientComponent` is for. The map is built as one — props down, events up, and a reconnect rebuilds
it from state the server never gave away. The layout editor is not built, and recording why is more
useful than a bad imitation of it.

---

## 3. What was fixed while this was being built

These were found by an earlier pass at this sample and fixed on the framework branch, which is why
this one could be written the way it is rather than around them.

- **Nothing held per-session state across a navigation** — fixed in `a1bfa8a`. The search box is now
  a `remember` in `app { }`, read through a `CompositionLocal` the sample declares. It was going to
  be smuggled through `attributes { }`, which is documented for authentication and would have come
  back empty after a hibernation.
- **No SVG** — fixed in `8179bfe`. The progress ring is the original's `CircleProgress` ported nearly
  line for line, and the bandwidth chart is a `<polyline>` whose `points` attribute is rewritten once
  a second. Without it, six charts would have been `<div>` bars and a `conic-gradient`.
- **`Select` had no natural handler** and **elements were missing** — fixed in `18f19b2`, `c080584`.
- **`attributes { }` ran more than once per session** — fixed in `006a72e`.

The original write-up of finding 1 was also **wrong about why**, and the correction is worth keeping:
it said `view(path) { }` gives each route its own composable root. It does not — one composition and
one recomposer serve a whole session, and a shared root already existed. The gap was a hole in the
API surface, not an architectural limit, which is why it turned out to be cheap. See
`samples/vessels/PLAN.md`.

---

## 4. What worked, which is also information

**`app { }` did what it was added for.** The search box survives navigating to a vessel and back, in
three lines of application code and no framework concepts beyond `remember`. Hoisting the top bar
into it also means a navigation does not rebuild it — asserted, not assumed.

**Three state lifetimes are distinguishable, and an application needs all three.** The search lives
as long as the session; the selected month tab is `rememberSaved` and comes back when the page does;
the telemetry is neither and is recomputed. Getting any of them wrong is visible to a user, and the
framework now lets them be said apart.

**Server-pushed updates need no ceremony.** The detail page's numbers move once a second because a
`LaunchedEffect` writes state. There is no subscription, no channel and no client-side cache to
invalidate — the same path a click takes.

**Tailwind and a server-driven DOM compose, with one rule.** The stylesheet is compiled ahead of time
by the Tailwind CLI with `@source` pointed at the Kotlin, rather than using the browser build, whose
runtime JIT scans the live DOM — a class first appearing in a patch would have risked arriving
unstyled. Building ahead of time trades that for the rule that class names must be **complete literal
strings in the source**, since that is where the extractor looks. `rowClass()` returns whole strings
for exactly this reason, as the original's does. Verified end to end: toggling a flag patches
`bg-orange-50` to `bg-red-50` and the new class is styled.

The one thing that does not fit the rule is a continuous value — a meter's width is a percentage, and
there is no utility class per percentage. Those are `style("width: …%")`, which is what the original
does too.

---

## 5. What this could not check

The map is a `ClientComponent` over Leaflet from a CDN, and **neither the CDN nor the tile server is
reachable from the environment this was built in** — the egress proxy refuses both. The component's
fallback path is therefore what the screenshots show: a message in a correctly sized box. The mount,
update and unmount contract is exercised, but the map has never been seen working, and that should
not be reported as though it had been.

Visual fidelity generally has no automated check. The tests assert structure and update cost; whether
it *looks* like the original was judged by eye against the screenshots.
