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

To reproduce: `./gradlew :samples:keyed-benchmark:installDist`, then

```bash
OP=remove SECONDS=40 java -XX:StartFlightRecording=duration=45s,filename=remove.jfr,settings=profile \
  -cp 'samples/keyed-benchmark/build/install/keyed-benchmark/lib/*' jetlin.samples.keyed.ProfileKt
jfr print --events jdk.ExecutionSample remove.jfr
```

### What does not fix it

Three things were tried and measured rather than assumed:

- **A newer Compose runtime.** The obvious lever, and still blocked. `org.jetbrains.compose.runtime`
  POMs resolve from Maven Central up to 1.12.0 and 1.8.2 even compiles, but its *runtime* classpath
  wants `androidx.annotation` and `androidx.collection`, which are published only to Google's Maven.
  The note at the bottom of the README is accurate.
- **`@NonRestartableComposable` on `Element` and `Text`**, to cut a restart group per element. It made
  things worse: swap 648 → 795 ms, remove 661 → 786 ms, on the same page. The node groups that
  dominate slot movement are still there, and the restart scopes were paying for themselves.
- **Chunking**, covered above: 13.5× on swap, 1.3× on removal, because index-based groups shift.

What is left is either a Compose fix or not composing the whole table — the same answer
`LazyColumn` is on Android. A server-side framework rendering a thousand rows into one composition
has no equivalent of it today, and windowing the rows is the shape the fix would take.

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
