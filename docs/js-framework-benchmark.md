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

## Where the results live

Run the task; it prints the tables. The findings that came out of the first run, and which the tests
now pin, are:

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

The third is the reason the report includes a scaling sweep the published benchmark has no equivalent
of. Nine operations at one table size cannot tell a large constant from a curve, and the difference
decides whether a number is a fact about the machine it ran on or a fact about the framework.
