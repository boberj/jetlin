# The vessels sample — state of the branch, and the plan it came from

Branch: `claude/vessels-sample`, branched from
`claude/kotlin-compose-reactive-framework-mexz47`.

This document exists so the work can be picked up cold. It has three parts: what is
actually on the branch right now, what was learned about the framework (the durable
part), and the plan as it stood when work stopped.

---

## 1. State of the branch

**The code does not compile.** That is deliberate and known, not an accident to
debug. `:samples:vessels:compileKotlin` has never been run successfully, because
`Main.kt` was never written.

| File | Lines | State |
|---|---|---|
| `settings.gradle.kts` | +1 | `:samples:vessels` registered |
| `samples/vessels/build.gradle.kts` | 20 | server-ktor + Netty, jetlin-testing for tests |
| `Data.kt` | 318 | Written, never compiled |
| `VesselsPage.kt` | 395 | Written, never compiled |
| `Main.kt` | — | **Missing. This is what blocks compilation.** |
| `VesselPage.kt` | — | Missing (the detail page) |
| `src/test/…` | — | Missing |
| `FINDINGS.md` | — | Missing; findings are in section 2 below instead |

**Why it does not compile:** `VesselsPage.kt` calls a `Shell { }` composable that was
going to live in `Main.kt`. There is no `jetlin { }` configuration, no route
registration, and no CSS. Nothing else is known to be wrong — but nothing else is
known to be *right* either, since the compiler has never seen it.

**First thing to do on picking this up:** write `Main.kt`, then run
`./gradlew :samples:vessels:compileKotlin` and fix whatever falls out of 713 lines
written against an API surface that was read rather than exercised. Do not write the
detail page first.

### Two things this branch depends on that are NOT in the repository

Both were supplied through the conversation and live in ephemeral session storage.
Whoever picks this up will need them again.

1. **The source application.** A zip of `cygnifi/dashboard`, uploaded because
   `add_repo` refuses it structurally (*"cross-tier adds are not supported in v1:
   requested cygnifi/dashboard but session already has repos from owner(s)
   [boberj]"*). It was extracted to a scratchpad directory that no longer exists.
   The files that matter are `src/routes/index.tsx`,
   `src/routes/index.functions.ts`, `src/routes/vessels.$vesselId.index.tsx` and
   `src/lib/vessel-layout.ts`.
2. **Three screenshots** — the fleet list with data, and the detail page top and
   bottom. These changed the design more than the source did, because they show
   density the TSX hides. Section 3 records what they settled, but the images
   themselves are gone.

### Scope decisions already settled — do not re-litigate

- **Structural fidelity, not visual.** Same information architecture and
  interactions; plain CSS, not a reproduction of the original's styling.
- **Priority interactions:** filtering/search, sorting, list-to-detail navigation.
- **No pagination and no virtualization.** Render every matching vessel in one go.
- **Search state is per-session and out of the URL**, and must survive navigating to
  a vessel and back. Sort stays in the query string (flagged as an assumption, never
  confirmed — see section 4).
- **Detail page data is live** (still fake): a server-side ticker, not a snapshot.
- **The drag/resize layout editor is out of scope**, as is the Leaflet map.
- **Framework gaps get written down, never fixed on this branch.** The whole point is
  to measure the framework as it is rather than quietly improving it while measuring.

---

## 2. Findings — the durable result

This is the part worth keeping regardless of what gets built next. Findings 1–3 were
established by reading the framework and hold whatever direction the work takes.

**1. Nothing holds per-session state across a navigation.**
`remember` dies when the view is swapped. `rememberSaved` looks right and is not: its
provider is registered in a `DisposableEffect`, so leaving the composition
unregisters it — it survives *hibernation*, where `performSave()` runs on a live
composition, but not a route change. And `JetlinConfig.view(path) { … }` gives every
route its own composable root, so there is no persistent layout to hold anything
either.
*Workaround, used locally:* smuggle a holder through `attributes { }`, which runs once
per session and which `RequestContext.forUrl` carries across navigation. It is
documented for authentication, tenancy and locale — not view state — and it is not
restored on rehydration, so a hibernated session comes back with an empty search box.
Every dashboard has state of exactly this shape.

**2. No SVG.** `document.createElement(tag)` in `jetlin.ts` has no `createElementNS`
beside it, and neither does `Ssr.kt`. An `<svg>` built that way is an
`HTMLUnknownElement` and renders as blank space — so `Element("svg")` is not a
workaround, it is a silent failure.
*Why 209 tests never caught it:* the demo's own sparkline draws `<div>` bars inside a
`ClientComponent`, so nothing in the suite has ever asked for an SVG element. This is
precisely the blind spot the exercise exists to find, and it is the cheapest of the
three to fix.
*Workaround, used locally:* meters and bar charts as `<div>`s with percentage widths,
progress rings as a CSS `conic-gradient`. Both stay inside the composition, so they
are still server-patched — no `ClientComponent` needed.

**3. Virtualization is not expressible, and this part looks architectural.** The real
list windows 52 px rows with `useVirtualizer`. A server that cannot see the scroll
position cannot window a list. The replica renders all 80 rows instead, so the honest
output is a measurement — first-paint size and re-sort patch size — rather than an
argument. A `ClientComponent` reporting scroll offset back is the obvious answer and
was deliberately not built.

**4. No "current URL with one parameter changed".** `queryParam` reads; nothing
writes. `fleetUrl()` in `VesselsPage.kt` reassembles the whole query string by hand,
including re-deciding which parameters to omit at their defaults. That is the only
URL operation a page like this ever wants.

**5. `Select` has no natural handler.** `Select`/`Option` exist and `onInput` works on
them, but `onInput` is the wrong word for a dropdown. `onChange` returning the
selected value is missing.

**6. Missing elements.** No `H4`–`H6`, `Ol`, `Dialog`, `Details`/`Summary`, `Tfoot`,
`Caption`. A dashboard of titled cards inside a page that already used `H1`/`H2` runs
out of headings quickly. `Element(tag)` covers all of it, so this is ergonomics
rather than capability — but it costs something on every card.
(`Table`/`Thead`/`Tbody`/`Tr`/`Th`/`Td` are all present, so the list itself is fine.)

**7. No tooltip affordance.** Every one of the eleven action icons in a row has a
tooltip in the real page. `attr("title", …)` is the honest fallback; anything better
is a positioned overlay, which the framework does not have.

**8. Two things belong to the client, and neither was built.** `DashboardGrid`'s
drag-to-move and resize is pointer-move state at 60 Hz, and the detail page's Leaflet
map is a third-party widget that patches its own DOM. Both are exactly what
`ClientComponent` is for — recording them as such is a point in the framework's
favour, not against it.

**Verdict as it stands:** findings 4–8 are ergonomics, a to-do list. Findings 1, 2
and 3 are not, and none of them is something a self-designed test suite would ever
have surfaced.

---

## 3. What the screenshots settled

The images are gone; this is what they showed.

**The fleet list row is far richer than a name and a status:**

| Column | Contents |
|---|---|
| VESSEL | ship icon, name in bold, then a second line in small grey mono: serial (`1933-E7F4-A9D2`) and LAN IP (`10.97.0.1`) |
| STATUS | one pill — green `Online`, red `Offline` |
| DATA USAGE | **two** stacked labelled meters — `SL` (Starlink, solid track) and `5G` (cellular, dotted track) — each with a GB figure right-aligned |
| PROGRESS | a circular ring with the percentage in the middle |
| PRIORITY | `⌃ 4 ⌄` — a stepper, so priority is edited **inline in the table** |
| ACTIONS | eleven small icon buttons in two rows, several carrying count badges (notes blue, tickets green/red) |

Row tinting matches the source's `rowClass()`: red left stripe and tint for
emergency/alert, orange for construction, grey and faded for disabled.

Above the table: an org header (initials tile, "Northern Offshore Services", "Fleet
Management Dashboard"), a vessel count, and three status counts —
`● 51 online  ● 10 offline  ● 6 unknown`. A "Fleet Status Overview" card carries the
search box in its header, right-aligned. `FleetStore` already has
`organizationName`, `onlineCount`, `offlineCount` and `unknownCount`.

**The detail page's blocks.** STATUS is two columns of label/value pairs (Model,
Serial, Firmware, Uptime, Clients, Last online, Public IP, Site ID, …) plus CPU and
memory bars and a row of tag chips. NOTES & PORTS is a note with an Add affordance,
port chips (`W1 1 2 3`), and applied licences with relative dates. CONNECTIONS is
three cards — WAN, Cellular, Wi-Fi — each a status dot, a title and label/value rows,
with a nested STARLINK DISH panel under WAN (throughput, latency, obstruction, dish
uptime, GPS). ROUTING and VLANS are list rows with a name, a VLAN chip and a CIDR.
SPEEDFUSION is a peer with a `2/2 connected` pill over two sub-cards. DATA USAGE has
month tabs, a large total, a bar chart and a WAN/Cellular legend.

**Five charts in total**, which is what makes finding 2 bite repeatedly.

---

## 4. The plan as it stood

Superseded — the user changed direction before this was built — but preserved
because most of it survives any re-aiming of the exercise.

### Remaining work, in order

1. **`Main.kt`** — `jetlin { }` config (`exposeTestTags = true`, `head = STYLES`,
   `attributes { }` for the session-scoped `FleetView`), routes `/` and
   `/vessels/{vesselId}`, the `Shell { }` composable `VesselsPage.kt` already calls,
   and the CSS. Model it on `samples/demo/src/main/kotlin/jetlin/samples/demo/Main.kt`,
   which has exactly this shape.
2. **Revise `Data.kt` and `VesselsPage.kt`** to match the settled decisions: drop
   `VesselPage`/`PAGE_SIZE`/`page(…)` in favour of
   `list(query, sort, ascending): List<Vessel>`; delete `Pager()` and the `page`
   parameter; shrink `fleetUrl()` to sort only; read the search from the session
   holder instead of `queryParam("q")`. Add a `Telemetry` type and a `tick(vessel)`
   random walk. Keep the 200 ms debounced input and the `key(vessel.id)` rows.
3. **Compile.** Before writing anything else.
4. **`VesselPage.kt`** — "← Back to Fleet", the header (ship tile, name, Online pill,
   InControl / Remote Web Admin links, badged action buttons), then six of the nine
   blocks: status, notes & ports, connections, routing, vlans, data usage. Fed by the
   ticker, with month tabs on data usage. No map, no layout editor. Charts are
   `<div>` bars and `conic-gradient` rings, never SVG.
5. **Tests**, then a `FINDINGS.md` carrying section 2 of this document.

### Where the view state lives

```kotlin
val FleetViewState = AttributeKey<FleetView>("fleetView")   // class FleetView { var query by mutableStateOf("") }

jetlin { attributes { mapOf(FleetViewState to FleetView()) } }

@Composable fun VesselsPage() {
    val view = LocalRequest.current[FleetViewState] ?: FleetView()
    val sort = SortKey.from(queryParam("sort"))
    val ascending = queryParam("dir") != "desc"
    val vessels = FleetStore.list(view.query, sort, ascending)
}
```

**Open question, never answered:** sort key and direction stay in the query string
while search does not. The reasoning was that a sorted fleet is a link worth sending
and a half-typed search box is not. If that split is unwanted, moving sort into
`FleetView` is a one-line change.

### Tests

`FleetAppTest.kt` via `jetlin-testing`, modelled on
`samples/demo/src/test/kotlin/jetlin/samples/demo/TodoAppTest.kt`:
`@BeforeTest { FleetStore.reset() }` because the store is process-wide, `setRoutes`
for anything that navigates, and no node ids or HTML strings anywhere. Cover: the
list opens with all 80 vessels; search narrows it; a sort header reorders and a
second click flips; the priority stepper changes one row; a flag icon tints its row;
a row link reaches the right vessel; **the search text is still in the box after
going to a vessel and back**; the detail page shows a loading state then data; month
tabs change the usage block; a telemetry tick changes numbers with no interaction.

Three update-cost assertions via `recordUpdate { }`, in ascending order of value:

- Sorting 80 rows moves them rather than rebuilding them.
- A telemetry tick patches the handful of cells that changed and nothing else.
- **Nudging one vessel's priority touches that row and nothing else** — one click,
  eighty rows, an exact op list. This is the assertion `docs/comparison.md` claims no
  other framework can express, stated at a scale where a wrong `key` would otherwise
  be invisible.

Browser tests only for what the headless tests cannot reach: that the back button
restores the search text and the sort.

### Verification

```bash
./gradlew :samples:vessels:compileKotlin   # first, and before writing the detail page
./gradlew :samples:vessels:test
./gradlew test                             # the existing 180 must be untouched
./gradlew :samples:vessels:installDist && samples/vessels/build/install/vessels/bin/vessels &
curl -s localhost:8080/ | wc -c            # the cost of not virtualizing, as a number
```

### Risks

**Scope creep.** Eleven action icons per row, a nested Starlink panel, applied
licences, tag chips, two chart types. Six blocks and one complete row rendered
honestly is the deliverable. The line to hold: build a thing once, and if the second
instance teaches nothing, stub it.

**Nothing has compiled.** 713 lines written against an API surface that was read
rather than exercised. `Main.kt` first, then compile, then continue.

**A per-second ticker in tests** must be drivable without real time passing, or the
test suite becomes slow and flaky. Keep `tick()` a plain function the tests call
directly; only `Main.kt` wires it to a clock.
