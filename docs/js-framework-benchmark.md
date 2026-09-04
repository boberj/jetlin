# The keyed js-framework-benchmark, server side

[js-framework-benchmark](https://krausest.github.io/js-framework-benchmark/current.html) is the
standard way to ask a web framework what its updates cost. Its keyed suite is nine operations on a
table of a thousand or ten thousand rows — build it, replace it, edit every tenth row, highlight one,
swap two, delete one, append to it, empty it — plus a set of memory readings. `samples/keyed-benchmark`
implements that page in Jetlin and drives all nine.

Which needs a caveat before any number, because the benchmark measures something Jetlin only half
does.

## What is measured, and what is not

The published benchmark times a click through to the browser having painted. A Jetlin interaction is
two halves either side of a socket:

| | Where it runs | Measured here |
|---|---|---|
| event dispatched to its handler | server | yes |
| composition recomposes | server | yes |
| applier records the DOM edits | server | yes |
| patch serialized to JSON | server | yes |
| frame crosses the network | — | no |
| client applies the ops, browser paints | browser | no |

So these are not comparable with the published figures, and reading them as though a framework had
been beaten would be wrong. They are comparable with each other, which is the useful part: the same
harness, the same preconditions, the same warmups, across nine operations that stress different
things.

Two columns make up for what the clock cannot see. **Ops** is the number of DOM instructions the
patch contains, and **wire** is its serialized size. A client-side framework has no equivalent of
either — its DOM work never leaves the machine — and on a server-driven one they are what an
interaction actually costs a user on a slow link. They are also exact rather than sampled, which
makes them the right thing to assert on: `KeyedBenchmarkTest` pins every one of them, so an operation
that starts sending a thousand ops where it used to send two fails the build.

## Running it

```bash
./gradlew :samples:keyed-benchmark:keyedBenchmark   # the measurements
./gradlew :samples:keyed-benchmark:test             # the op-level assertions
./gradlew :samples:keyed-benchmark:run              # the page itself, on :8080
```

`SECTIONS=cpu`, `ITERATIONS=n` and `WARMUP_PASSES=n` narrow or lengthen a run.

## Fidelity to the reference harness

The page follows the benchmark's VanillaJS reference markup exactly — the same six button ids, the
same `<tbody id="tbody">`, the same four cells per row, the same `preloadicon` — because the harness
selects on all of it and because a row cheaper to build than the reference's is not measuring the
same thing. Labels are drawn from the same three word lists, through the same deliberately non-uniform
`_random`, so the strings being written are the same shape.

Each operation keeps the harness's split between untimed setup and the one timed click, taken from
`webdriver-ts/src/benchmarksPuppeteer.ts`. That split is not a detail: "clear rows" measured from an
empty table measures nothing, and "create rows" measured without the preceding clears is a replace.
Every measured iteration runs on a session that has never been used before, which is the JVM's version
of the harness reloading the page — a composition that has already built and torn down ten thousand
rows has a slot table sized for them.

## Results

Ten iterations each, after five discarded passes, on a 4-core container running OpenJDK 21.0.10.
Time is the server's share only; `ops` and `wire` are exact.

| operation | min ms | median ms | ops | the patch | wire |
|---|---|---|---|---|---|
| create rows | 95.27 | 99.66 | 1,000 | 1,000 inserts | 678.6 kB |
| replace all rows | 117.10 | 143.39 | 2,000 | 1,000 inserts + 1,000 removals | 723.6 kB |
| partial update | 1.02 | 1.15 | 100 | 100 text writes | 6.5 kB |
| select row | 0.55 | 0.73 | 2 | 2 class attributes | 139 B |
| swap rows | 836.06 | 851.98 | 2 | 2 moves | 139 B |
| remove row | 814.43 | 837.55 | 1 | 1 removal | 80 B |
| create many rows | 3400.14 | 3738.64 | 10,000 | 10,000 inserts | 6.7 MB |
| append to large table | 101.36 | 103.04 | 1,000 | 1,000 inserts | 679.7 kB |
| clear rows | 7.28 | 7.45 | 1,000 | 1,000 single-row removals | 42.0 kB |

Recomposition is 68–99% of each figure; the rest is JSON encoding.

Retained heap per live session:

| state | rows | per session |
|---|---|---|
| page composed, no rows | 0 | 57.2 kB |
| after creating 1,000 rows | 1,000 | 15.1 MB |
| after 5 updates on 1,000 rows | 1,000 | 15.6 MB |
| after 5 create/clear cycles | 0 | 4.9 MB |
| after creating 10,000 rows | 10,000 | 100.0 MB |

A cold request, composing the page and then writing it out — both halves on the clock, with the rows
in the store before the composition starts:

| page | compose | write html | total | html |
|---|---|---|---|---|
| empty table | 1.92 ms | 0.52 ms | 2.44 ms | 1.7 kB |
| 1,000 rows | 21.15 ms | 3.15 ms | 24.30 ms | 435.1 kB |

And the scaling sweep, which is the part the published benchmark has no equivalent of:

| rows | build | swap 2 | remove 1 | update every 10th | rows changed |
|---|---|---|---|---|---|
| 250 | 19.17 | 54.26 | 53.68 | 0.76 | 25 |
| 500 | 44.90 | 215.41 | 213.53 | 0.94 | 50 |
| 1,000 | 141.61 | 867.16 | 883.29 | 1.64 | 100 |
| 2,000 | 398.36 | 3525.62 | 3465.83 | 2.83 | 200 |
| 4,000 | 1460.02 | 13772.87 | 13897.19 | 3.85 | 400 |
| **per doubling** | **×3.0** | **×4.0** | **×4.0** | **×1.5** | ×2 |

## Where the time goes

Three measurements place it, and none of them point here:

- Turning op recording off — `LiveView.clientDetached()`, which removes the `toSpec()` deep copy of
  every inserted subtree and the op buffer with it — moves `create rows` from 133.9 ms to 136.3 and
  `swap rows` from 720.7 to 728.4. Inside the noise. The whole of this framework's contribution to an
  update is free relative to what surrounds it.
- JSON encoding is 7% of a patch. Writing 435 kB of HTML takes 3.15 ms.
- The same thousand rows cost 21 ms composed from scratch and ~93 ms added to a live page.

So the expensive thing is Compose reconciling the children of a keyed group that has a thousand of
them. Compose's own UI never does that — a `LazyColumn` composes only what is on screen — so the
path was never under pressure to handle a table rendered whole.

An application can shrink the group without any framework change, by nesting the rows in groups of a
fixed size. `CHUNK=50` does that here; the default is the single group the benchmark is written
against, so the numbers above are unaffected.

| operation | one group | groups of 50 | | ops, one group | ops, groups of 50 |
|---|---|---|---|---|---|
| create rows | 99.66 | 29.66 | ×3.4 | 1,000 ins | 1,000 ins |
| replace all rows | 143.39 | 115.17 | ×1.2 | 2,000 | 2,000 |
| partial update | 1.15 | 1.17 | ×1.0 | 100 text | 100 text |
| select row | 0.73 | 0.79 | ×0.9 | 2 attr | 2 attr |
| swap rows | 851.98 | 63.12 | ×13.5 | 2 mv · 139 B | 2 ins + 2 rm · 1.5 kB |
| remove row | 837.55 | 636.24 | ×1.3 | 1 rm · 80 B | 19 ins + 20 rm · 13.8 kB |
| create many rows | 3738.64 | 519.57 | ×7.2 | 10,000 ins | 10,000 ins |
| append to large table | 103.04 | 37.57 | ×2.7 | 1,000 ins | 1,000 ins |
| clear rows | 7.45 | 6.68 | ×1.1 | 1,000 rm · 42.0 kB | 20 rm · 919 B |

It is a trade rather than a fix. Swapping goes from quadratic to linear (×2.0 per doubling instead
of ×4.0) and clearing collapses to one op per group, but rows now cross group boundaries: a swap
rebuilds two rows instead of moving them, and a removal shifts every row behind it into the next
group — which is why removal barely improves, stays quadratic, and sends 172 times the bytes. A
scheme keying groups by their contents rather than by position ought to avoid the shifting; that is
a guess, not a result.

### Why removing one row costs most of a second

Black-box timings say "reconciliation"; a profiler says which frames. Forty seconds of removals under
JFR, 3,501 execution samples, and 92% of them are in two methods:

| | share of samples |
|---|---|
| `SlotWriter.fixParentAnchorsFor` | 58.4% |
| `SlotWriter.moveGroupGapTo` | 34.0% |
| `SlotTableKt.updateParentAnchor` | 2.0% |

Both sit under `SlotWriter.moveGroup`, reached through `removeGroups` (18%) and `insertGroups` (16%).

Compose's slot table is a gap buffer. Relocating a group means moving the gap to it — an arraycopy —
and then walking the moved subtree rewriting the parent anchor of every group in it, recursively.
That is fine once. It is not fine per surviving sibling, and removing the row at index 3 of a
thousand leaves 996 siblings whose slot positions have all shifted. One `Remove(tbody, 3, 1)` op
reaches the browser; the second before it is spent inside the slot table.

The cost model that falls out of it is `rows × total groups`, not `rows` alone. Holding the row count
at a thousand and varying only the markup inside each row:

| nodes in the table | remove one row |
|---|---|
| 3,002 | 131.7 ms |
| 5,002 | 208.2 ms |
| 9,002 | 413.6 ms |
| 17,002 | 892.3 ms |

Roughly linear in nodes at a fixed row count, and quadratic in rows at fixed nodes per row. So markup
per row is a plain multiplier — the benchmark's row is eight nodes and its markup is fixed by the
rules, but an application's is not.

To reproduce:

```bash
OP=remove SECONDS=40 JFR=remove.jfr ./gradlew :samples:keyed-benchmark:profile
jfr print --events jdk.ExecutionSample remove.jfr
```

(`OP` is create, swap or remove; `ROWS` and `CHUNK` set the table. A Gradle task rather than
`installDist` and a classpath glob: that distribution is assembled by file name, and two of the
dependencies are both called `runtime-desktop-<version>.jar` — one from JetBrains, one from androidx.
One is dropped silently and the run dies on an unrelated missing class.)

### What does not fix it

- **`@NonRestartableComposable` on `Element` and `Text`**, to cut a restart group per element. It made
  things worse: swap 648 → 795 ms, remove 661 → 786 ms, on the same page. The node groups that
  dominate slot movement are still there, and the restart scopes were paying for themselves.
- **Chunking**, covered above: large gains on building and swapping, almost none on removal, and it
  costs ops on both the operations that reorder.

### What does: a newer Compose

The lever that was out of reach for as long as `dl.google.com` was. See *Compose 1.12.0* below — a
version bump makes swapping and removing between four and five times cheaper, and makes building a
very large table more than twice as expensive.

What it does not change is the shape. Reordering is still quadratic on 1.12, just with a constant
four to five times smaller, so the ceiling moves up about a factor of two in rows and stays a
ceiling. The structural answer is the same as it was: either Compose changes further, or a
server-side framework stops composing whole tables — the thing `LazyColumn` does for Compose's own
UI, and which Jetlin has no equivalent of.

## A note on warmup

The benchmark's own warmup clicks exercise the operation but not the JVM. One `#run` click runs the
element and applier code some ten thousand times, so those paths cross HotSpot's compilation
threshold inside the first iteration. The two operations that touch a handful of nodes do not:
comparing minimums across 0, 5 and 15 discarded passes, `partial update` goes 1.37 → 1.02 → 0.88 ms
and `select row` 0.75 → 0.55 → 0.51, while every other operation moves less than 3%. Five passes is
the default because it captures nearly all of that and costs half a minute.

Worth noting that swap and remove sit at ~820 ms with 0 passes and with 15, so the finding below is
not a warmup artifact.

## Findings

These are what the tests now pin:

1. **Keyed reuse works, and is visible in the op counts.** Swapping two rows of a thousand emits two
   moves. Updating every tenth row emits a hundred text writes. Moving the highlight emits two
   attribute writes. None of these grow with the size of the table, and none of them rebuild a node.
2. **Clearing a table sends one removal per row.** Compose hands the applier one removal per keyed
   group and nothing downstream merges them, so emptying a thousand rows is a thousand
   `Remove(tbody, 0, 1)` ops — forty kilobytes for something the protocol can express in eighty bytes
   as `Remove(tbody, 0, 1000)`. Coalescing adjacent removals on the way out of the buffer would fix
   it; nothing here does that yet.
3. **Structural changes are quadratic in the size of the list.** Writing to per-row state costs the
   same at four thousand rows as at two hundred and fifty. Inserting, removing or moving a row costs
   four times as much for every doubling of the table. The applier is not the cause — it receives two
   `move` calls for a swap either way — and neither is the op count. The cost is in the Compose
   runtime reconciling the keyed children of the group that changed, and it reproduces on a list of
   `Li { Text(...) }` with nothing else in it. Compose's own UI never meets it because a `LazyColumn`
   only composes what is on screen; a server-side framework rendering whole tables does.

4. **A session keeps the memory of the largest table it ever held.** A freshly composed session with
   no rows costs 57 kB. A session that has built and cleared a thousand rows five times, and is empty
   again, costs 4.9 MB — eighty-five times the floor for the same visible page. The slot table is
   sized for the peak and never shrinks. On a server that is the figure that decides capacity: a user
   who once opened a large table pays for it until they disconnect, not until they close it.

The third is the reason the harness includes a scaling sweep the published benchmark has no
equivalent of. Nine operations at one table size cannot tell a large constant from a curve, and the
difference decides whether a number is a fact about the machine it ran on or a fact about the
framework.

Note what the third finding is *not* about. "Update every 10th row" changes a tenth of the table, so
its cost has to grow with the table — and it grows more slowly than the work it is doing: sixteen
times the rows changed for five times the time, as the fixed overhead amortizes. That is cost
tracking rows changed, which is what the design promises. Swapping two rows changes two rows at any
size and emits two ops at any size, and still quadruples per doubling. That is the one place the
promise does not hold.

## Compose 1.12.0

Everything above ran on Compose Multiplatform 1.5.12, pinned since 2023 because its successors reach
Google's Maven for `androidx.annotation` and `androidx.collection` and the machine could not. On a
machine that can, `org.jetbrains.compose.runtime:runtime:1.12.0` is a redirect to
`androidx.compose.runtime:runtime:1.12.0` — Jetpack Compose itself, latest stable — and the bump is
the one line the README always said it was, plus `google()` scoped to the androidx groups.

Both runs below are the same machine (8 cores, WSL2, OpenJDK 21.0.7), same harness, ten iterations
after five discarded passes. They are **not** comparable with the 4-core figures above.

| operation | 1.5.12 | 1.12.0 | |
|---|---|---|---|
| swap rows | 878.49 | **184.81** | ×4.8 faster |
| remove row | 824.95 | **173.81** | ×4.8 faster |
| create rows | 119.71 | **52.94** | ×2.3 faster |
| append to large table | 120.46 | **69.39** | ×1.7 faster |
| replace all rows | 163.60 | **99.73** | ×1.6 faster |
| clear rows | 20.03 | **15.19** | ×1.3 faster |
| select row | 0.89 | 1.04 | ×1.2 slower |
| partial update | 1.75 | 2.13 | ×1.2 slower |
| **create many rows (10k)** | 4363.01 | **10385.04** | **×2.4 slower** |

Ops and wire are byte-identical on both, so nothing about the emitted patches changed.

The two operations this document spends the most effort explaining are four to five times cheaper,
and the scaling sweep says why — and what did not change:

| rows | swap, 1.5.12 | swap, 1.12.0 | remove, 1.5.12 | remove, 1.12.0 |
|---|---|---|---|---|
| 250 | 51.11 | 17.12 | 50.82 | 13.46 |
| 500 | 244.66 | 51.03 | 213.80 | 48.68 |
| 1,000 | 885.26 | 197.37 | 861.19 | 219.30 |
| 2,000 | 3686.47 | 752.96 | 3568.67 | 751.16 |
| 4,000 | 14338.26 | 4010.47 | 13944.96 | 3026.36 |

Still ×4 per doubling. **The quadratic is not gone — its constant shrank by four to five.** That moves
the practical ceiling up about a factor of two in rows and leaves it a ceiling, so the structural
conclusion stands unchanged.

### The one regression, and it is not GC

Building ten thousand rows more than doubled in cost, and a recording says exactly why:

| | 1.5.12 | 1.12.0 |
|---|---|---|
| `Arrays.fill` ← `SlotWriter.clearSlotGap` | 61.4% | **88.4%** |
| `Pending.updateNodeCount` | 18.1% | **3.0%** |
| GC pause, share of wall clock | 6.3% | **1.4%** |

Per build, `clearSlotGap` goes from about 221 samples to about 749 — 3.4× more, which accounts for
the regression on its own. So 1.12 has largely fixed the `Pending` map walk that made reordering
quadratic, and made the gap clearing that dominates a large build substantially worse. One bottleneck
traded for the other. (The classes have moved to
`androidx.compose.runtime.composer.gapbuffer`, with `GapComposer` and `GapPending` alongside them:
the runtime is being refactored toward a pluggable composer, and the gap buffer is now one
implementation rather than the implementation.)

Building is faster at every size the sweep covers — 728.81 ms against 1515.60 at 4,000 rows, ×2.1 —
so the crossover sits somewhere between four and ten thousand.

### Memory, and one behaviour change

| state | 1.5.12 | 1.12.0 | |
|---|---|---|---|
| page composed, no rows | 59.0 kB | 50.5 kB | 14% less |
| after 1,000 rows | 15.1 MB | 13.0 MB | 14% less |
| after 5 updates on 1,000 | 15.6 MB | 13.0 MB | 17% less |
| after 5 create/clear cycles, empty | 4.9 MB | **1.8 MB** | **2.7× less** |
| after 10,000 rows | 100.3 MB | 122.6 MB | 22% more |

The fourth row is the "a session keeps the memory of the largest table it ever held" finding, and it
is substantially better: an emptied session that once held a thousand rows costs 1.8 MB instead of
4.9. The last row moves the other way, with the build regression.

And one thing that is not a number. `rememberSaved` keys itself by `currentCompositeKeyHash`, and
until 1.12 two calls side by side in one composable landed on the same position — so they collided,
and the collision was reported rather than allowed to lose a value. On 1.12 they do not:

```
saved={d9ucqg="first value", d9ucqi="second value"}
```

Adjacent `rememberSaved` calls no longer need explicit keys. The guard stays, because position is
still not an identity in a loop over reorderable data, but it is covered at the registry now rather
than by arranging a real collision through the composer — which is a test pinned to one runtime's key
derivation, and is exactly what the upgrade broke.
