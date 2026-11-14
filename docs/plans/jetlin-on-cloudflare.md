# Jetlin on Cloudflare: a design for Workers and Durable Objects

This plan is a proposal. None of it is implemented. It describes how the essence of Jetlin could run
on Cloudflare Workers and Durable Objects, with tenants spread over many objects.

- Audience: the maintainer, and Claude Code working in the Jetlin repository.
- Background reading: `docs/architecture.md` §1–3, §7 and §9, `docs/db.md` §1, §2, §5 and §6,
  [`db-framework-plan.md`](db-framework-plan.md) §2.2–2.4, and
  [`policy-simplification.md`](policy-simplification.md) §1–2.
- Constraint set by the maintainer: Compose runs only on the server. The browser stays a thin
  runtime.

How this was made:
1. Two research passes covered the Cloudflare platform and prior art. The platform pass also built a
   headless Kotlin/Wasm Compose prototype and ran it in local `workerd`.
2. Five design drafts followed: two architectures (A1 "data-local cells" and A2 "edge session
   actors"), plus placement (D1), policy and optimistic concurrency (D2) and latency hiding (D3).
3. An adversarial critique tested them, and a synthesis combined what survived.
4. A final review re-ran the key benchmark, checked the Compose source and the repo references, and
   added §3.7.

The prototype and benchmark scripts were scratch work and weren't kept. Every figure marked
**[measured]** has to be reproduced by the Phase 0 spikes (§11) before anything is built on it.

Labels used throughout:
- **[verified]**: read on a Cloudflare page or changelog during the research (October 2026).
- **[measured]**: measured during the research in local workerd or Node, not on Cloudflare's network.
- **[third-party]**: a reputable outside measurement.
- **[assumption]** or **[unverified]**: not checked. Treat these as hypotheses.

---

## 1. Thesis

Jetlin comes down to three ideas:
- A Compose composition runs on the server for each UI session.
- An applier records ops, and those ops are the diff.
- Database records are snapshot state, so any write recomposes exactly the readers it affects. That
  includes a write that changes who may see what.

Everything else in today's Jetlin is how those ideas were built for a single JVM: Ktor, an identity
map that holds every record, one process. None of it is essential.

On Cloudflare, the design that keeps those ideas puts each composition **next to the data it
reads**. A Durable Object (DO) that is the single authority over one partition of a tenant's data
also runs the compositions of the users viewing that partition. This document calls that DO a
**cell**. Three things follow from it:
- **No suspension during composition.** SQLite in a DO is a library on the object's own thread, with
  a synchronous API [verified]. Point reads took 7–12 µs [measured]. So the identity map can shrink
  from "every record" to a bounded cache over SQLite, and a composition can read a record that isn't
  cached without suspending.
- **Transactions keep their shape.** A transaction is still a Compose snapshot committed to SQLite
  before `apply()`. It now runs in one JavaScript task with no `await`. Each cell is therefore
  serializable. The output gate also holds the resulting patch until the write is durable, provided
  the gate covers WebSocket sends, which is still unknown (§11).
- **Revocation is still just a write** inside a cell.

Everything beyond the cell handles cases one cell can't cover, and each case has an explicit limit:
- Small tenants share one cell.
- Large tenants are split by a co-location key that every row carries from day one.
- When a partition has more concurrent viewers than one cell's memory allows, **host DOs** created
  next to the cell serve the extra viewers. A host holds a replica, filters reads locally, and sends
  writes to the cell as validated intents.
- Policy inputs (users, memberships, grants) live in one per-tenant **core**. The core is replicated
  with a version stamp and a lease, so revocation across DOs has a stated staleness bound and fails
  closed.
- Optimism lives only in the acting user's browser, as reversible effects that the server declared.
  No server-side composition ever renders a value the authority refused.

---

## 2. Architecture overview

```
 Browser: jetlin.js (8.6 kB today; ~11-12 kB with the prediction ledger [assumption])
   | GET / WSS   route-hint cookie: (tenant, partition, epoch, DO name, HMAC)
   v
 Router Worker (TypeScript, no Kotlin/Wasm, so the script stays small and starts fast)
   | isolate LRU of routes --miss--> Directory DO "dir:{region}:{0..63}"
   | forwards the GET and the WebSocket upgrade to the DO named by the token or route
   v
 +- Cell DO "cell:{tenant|pack}:{range}:g{n}"  (home region; jurisdiction if required) ---+
 |  one Kotlin/Wasm instance per DO, one Recomposer per tenant scope (§3.7), sync drain    |
 |  SQLite (authoritative): rows(id, part, ver, ...), _log, tx_dedupe, _ui_sessions,       |
 |    _watch, _meta(epoch, lsn, owns), core replica @c                                     |
 |  RecordCache (bounded, pinned) + QueryVersions + live compositions (capped by nodes)    |
 |  hibernatable client sockets; authority RPC: commit(intent), changesSince, export       |
 +------+--------------------------------------------------^-------------------------------+
        | push log entries (RPC, after commit)            | WriteIntent -> Committed |
        v                                                  | Conflict | Denied | Behind
 +- Host DO "...:g{n}:h{i}"  (created by get() from the cell, so it lands beside it) ------+
 |  same module; persisted replica SQLite + RecordCache + compositions + sockets          |
 |  UI-only events run locally; db writes use a held snapshot, then intent, then apply    |
 +-----------------------------------------------------------------------------------------+
 Home cell "cell:{tenant}:home:g{n}": the core partition (users, teams, memberships, grants)
   and tenant-wide pages over rollup tables; it pushes the core log to partition cells and hosts.
 External systems: Fetch / rememberAction -> fetch(), Queues, Workflows.
```

| Component | What it is | Where it runs | What it owns | Budget |
|---|---|---|---|---|
| Browser runtime | Today's jetlin.js plus a prediction ledger and pending markers | Browser | DOM, route hint, unsettled seqs | +2–3 kB minified [assumption] |
| Router Worker | Stateless routing; checks the token MAC | Nearest point of presence (PoP) | Nothing; caches routes for 60 s, versioned by epoch | Tiny; no Wasm |
| Directory DO | Strongly consistent routing and placement state, 64 shards per region | Region named in the tenant ID | `tenant → {mode, ranges → cell, epoch, hosts}`, load reports, move state machines | ≤16 KB per tenant row |
| Cell DO | Authority for a group of partitions, plus compositions | Tenant's home region (location hint at creation) | Authoritative SQLite, `_log`, live and soft-hibernated sessions | 32 MB of live data, conservatively (§8); SQLite ≤2 GB before split (≤512 MB once it has hosts) |
| Pack cell | A cell holding many small tenants, one `TenantScope` each | Region | Same as a cell, per scope | ≤150 tenants; same memory |
| Home cell | The cell holding a split tenant's core | Home region | Core partition, rollups | Core ≤ ~50 MB [assumption] |
| Host DO | Overflow compositions for one partition | Beside its cell | Persisted full replica, sessions | Same memory as a cell; replica = partition size |

---

## 3. The data layer

### 3.1 Partitions and IDs

Every entity declares where it lives:

```kotlin
@Entity @Colocate(root = Project::class, via = "project") class Todo(...)    : Record()
@Entity @Colocate(root = Project::class, via = "todo.project") class Comment(...) : Record()
@Entity @Core class Membership(val user: User, val team: Team)               : Record()
```

A KSP processor checks that the `@Colocate` declarations form a tree. Every row carries three
framework columns:
- `id`: a ULID, minted by whichever DO inserts the row. It is never a SQLite rowid, so a host can
  insert without a round trip and a partition can move without renumbering.
- `part`: the root key.
- `ver`: the log sequence number (lsn) of the row's last write.

A reference may point within its own root or to a `@Core` entity. Any other reference is a
`RemoteRef<T>` of `(part, id)` and loads like `Fetch`.

A `transact` that writes two roots throws `CrossColoWrite`. It throws even when the whole tenant sits
in one cell, so an app that passes its tests can't break when its tenant is later split.

The reasons come from prior art. Figma's "colos" and Slack's move from workspace to channel sharding
both show that the first shard key fails on large tenants. Notion's re-shard shows the key has to
exist on every row before you need it.

### 3.2 Residency: memory as a cache over SQLite

The identity map becomes a `RecordCache`. Records still have one stable instance while anything uses
them, so identity-map semantics hold.

```kotlin
class RecordCache(val sql: SqlDriver, var budgetBytes: Long) {
  fun get(t: Table, id: Id): Record? = byId[id] ?: sql.selectById(t, id)?.let(::hydrate) // sync, no await
  fun pin(id: Id, s: SessionId)   // at the obtain point (query, lookup, relation)
  fun unpinAll(s: SessionId)      // on soft-hibernate or dispose
  fun evict()                     // unpinned records, LRU order, until under budget
}
```

**Pinning.** Pinning happens where jetlin-db already checks access: when a record is obtained
(db-framework-plan §2.4, option A). A record that a live composition still observes is never
evicted. Evicting it would leave that composition subscribed to an object that no longer exists. An
unpinned record needs no notification, so dropping it is safe. A later miss rehydrates it at the
current `ver`.

**Hydration.** It writes the cells in a separate "load" snapshot that is applied globally. It never
conflicts, because a record that wasn't resident had no state object.

**Why the cache is bounded.** At 1.1 kB per record, "every record in memory" exhausts a 128 MB
isolate at about 50–80k records.

**Unmeasured cost.** The 7–12 µs per read was measured from JavaScript. Marshalling each row and
column between Kotlin/Wasm and JavaScript hasn't been measured (Experiment 6).

### 3.3 Queries and reactive invalidation

A collection becomes a `Query` with an indexable part and an optional Kotlin residual. The indexable
part is equality or `IN` on columns. Policies gain an indexable `readScope(principal)`, which is
combined with the query's own equalities.

```kotlin
db.todos.where(Todo::project eq p).orderBy(Todo::createdAt).window(50)
```

Running a query inside a composition takes four steps:
1. Compute its fingerprints.
2. Read the snapshot state `QueryVersion(shape, values)` for each fingerprint. Reading it subscribes
   the composition.
3. Run a synchronous `SELECT … WHERE part=? AND … LIMIT n+1`.
4. Obtain and pin each candidate record, then run `canRead`.

Invalidation works at three levels:

| Level | State object | Precision |
|---|---|---|
| Row | Record cells, as today | Exact |
| Predicate | `QueryVersion(shape, values)` | Exact for equality and `IN` |
| Table | `TableVersion(table, columns)` | Over-invalidates; used for ranges, `LIKE` and residual filters |

**Disjunctions and `IN` need one key per alternative.** A1 had a bug here, which the critique caught.
A query keyed `(project=P, owner=bob OR team IN {7, 9})` must register three keys: `(P, owner=bob)`,
`(P, team=7)` and `(P, team=9)`. Otherwise an insert into team 7, which bumps `(P, team=7)`, never
reaches Bob's list.

**When bumps happen.** On commit, each changed row bumps the keys of its before and after
projections. These bumps happen **inside the transaction's snapshot, before `apply()`**, so they
become visible atomically with the cell writes.

**Unbounded collections.** Iterating an unbounded collection warns when it exceeds 5,000 rows.
Without a `readScope`, a policy scans the whole partition.

### 3.4 The commit path in a cell

```
event → handler in a mutable snapshot → Db.transact (nested) → judge() →
sql.transactionSync { rows (ver = lsn); _log(lsn, txId, rows, deps.core); tx_dedupe } →
QueryVersion bumps in the snapshot → apply() → drain dispatcher → ws.send(patch)
```

**No `await` anywhere.** Nothing in the chain awaits, so no other event in any session interleaves.
Each cell is therefore **serializable**, which closes the write-skew gap that Compose's snapshot
isolation leaves open.

**The dispatcher.** It has to be a custom drain dispatcher. The research notes say
kotlinx.coroutines' default on JS/Wasm yields to the macrotask queue, which would let other events
slip in between a handler and its patch [assumption, from knowledge].

**Durability.** It depends on an open question:
- If the output gate holds WebSocket `send()`, the patch waits for a quorum of 3 of 5 followers
  [verified for responses and `fetch`].
- If it doesn't, Jetlin gates commit-carrying patches itself by awaiting `ctx.storage.sync()`, which
  exists [verified]. UI-only patches then go out at once, which is better for packs.

**What the log costs.** `_log` adds one row written per changed row. A single-row commit writes about
5 rows in total: the row, about 2 index rows, the log row and the dedupe row.

### 3.5 Replication between DOs

Two kinds of DO subscribe to a cell's log:
- **Hosts** subscribe to their partition.
- **Partition cells and hosts** subscribe to their tenant's core.

The cell pushes log entries by RPC after each commit, with at most 6 in flight, which is the
outbound-connection limit [verified]. RPC is used rather than DO-to-DO WebSockets, because outbound
sockets don't hibernate [verified].

**Ingest is the same function as a local commit, without `judge`.** It runs in four steps:
1. Write the rows and the cursor in one `transactionSync`.
2. Write the resident cells in **one global snapshot apply per authority transaction**, so no
   recomposition ever sees half a transaction.
3. Use the `Versioned(lsn, value)` merge policy, so the higher lsn always wins.
4. Bump the same query versions as a local commit.

**Gaps.** A gap in the sequence triggers `changesSince(cursor)`. The cell keeps `_log` for 7 days or
2 million rows [assumption]. A replica further behind than that gets a fresh `export`.

**Host replicas are full and persisted.** This differs from A2, where every reconnect and deploy
re-downloaded every shape. A woken host catches up from the log instead.

That is affordable only when partitions are small. Bootstrapping a host for a 512 MB partition is
about 1.8 million rows, or about 5 million rows written with indexes: roughly $5 and about 20 s at
an assumed 25 MB/s. So:
- A partition that needs hosts is split earlier, at 512 MB rather than 2 GB.
- Hosts are pre-provisioned from the previous week's peak, not created on a spike.

**If `send()` turns out to be gated,** every replica ingest delays the host's next patches by one
confirmation. An ingest can't use `allowUnconfirmed`, which exists only for KV
`put`/`delete`/`deleteAll`, not `sql.exec` [verified]. That is acceptable if confirmation takes
2–10 ms. If it doesn't, the fallback is:
- write resident cells immediately;
- coalesce SQL ingest to every 100 ms;
- flush synchronously before any query whose shape key has a pending bump.

### 3.6 Connected sessions that aren't live

Today a connected session is always live and hibernates only after its socket closes
(architecture.md §9). On Cloudflare that breaks: a DO hibernates after about 10 s without events and
discards every composition [verified]. A board someone is watching would silently stop updating.
Four mechanisms fix this.

**1. Residency.** While a DO has visible live viewers, a chain of `setTimeout` heartbeats keeps it
resident [unverified that this holds for hours; Experiment 3]. Cost:
- about $4.05 a month per always-awake DO;
- about $1.24 for business-hours residency (10 h × 22 days).

**2. Visibility.** jetlin.js reports `visibilitychange`. Hidden tabs are soft-hibernated first, and
LRU pressure applies only after them. To soft-hibernate a session, the cell:
- writes `rememberSaved` to `_ui_sessions` (durable across deploys, which close every socket and
  drop their attachments [verified]);
- writes a compact copy into the socket attachment for fast waking;
- unpins the session's records.

**3. `_watch`.** A soft-hibernated session registers up to 64 coarse read keys (shape keys and record
IDs, coarsened to the table level beyond that) in `_watch(key, socket)`. A commit looks them up
synchronously and sends `stale` to the matching sockets. A visible tab that receives `stale` asks to
`wake` at once; a hidden tab wakes when it becomes visible. Socket tags can't carry these keys,
because tags are fixed at `acceptWebSocket` [verified].

**4. Rebind instead of Reset.**
- *At hibernation:* store the session's pre-order list of node IDs and a 128-bit hash of the tree's
  structure and content. That is about 350 B for 113 nodes and about 3 KB for 1,000.
- *On wake:* recompose. If the hash matches, rename the rebuilt nodes to the stored IDs on the server
  only, and dispatch the waking event normally.
- *If it differs:* send a `Reset` that the client applies by morphing, which keeps focus and scroll,
  along with `dropped{seq}`.

This replaces A1's deterministic pre-order IDs, which the critique showed would renumber everything
after an insert. IDs stay stable while the session is live, and remapping happens only at wake. The
assumption here is that `HtmlApplier` can rename nodes before the first patch after a wake.

### 3.7 Failure isolation: one Recomposer per tenant scope

This section was added after the synthesis. It rests on the androidx.compose.runtime 1.12.0 source
and a re-run of the research benchmark. Two facts pull in opposite directions.

**More Recomposers make every write slower.**
- Each Recomposer registers its own global apply observer (`Recomposer.kt:1053`). Every snapshot
  apply therefore visits every Recomposer in the Wasm instance.
- Re-running the benchmark reproduced the cost [measured]. With one Recomposer per session, a click
  took 0.21 ms with 1 session hosted and 8.5 ms with 2,000. With one shared Recomposer it took
  0.75 ms.

**Fewer Recomposers widen the blast radius of a bug.**
- Outside hot-reload mode, a composition error is recorded and rethrown (`Recomposer.kt:834–846`).
  That ends `runRecomposeAndApplyChanges` and stops every composition attached to that Recomposer.
- Today's Jetlin depends on per-session isolation here. A composable that throws ends only its own
  UI session (architecture.md §7, "Failures"), and `CompositionHost.isAlive` reads that session's
  own runner job.

"One Recomposer per DO" would turn one buggy page into an outage for every session in the cell, and
in a pack for up to 150 tenants. So:
- **Per tenant scope:** each `TenantScope` that has live sessions gets its own Recomposer, so a
  failure stays inside one tenant.
- **Pool in a single-tenant cell:** the cell keeps a small pool (K ≈ 8 [assumption]) and assigns
  sessions by a hash of their token. A failure then costs 1/K of the cell.
- **Cost:** the per-apply cost grows with the number of *running* Recomposers, which is bounded by
  active tenants or K, not by sessions. The benchmark puts 100 at about 0.4 ms per click.
- **Idle tenants:** a Recomposer with no live compositions is cancelled, so idle tenants in a pack
  cost nothing.
- **Recovery:** when a Recomposer fails, its sessions are disposed and rebuilt from `_ui_sessions` on
  a fresh one, then rebound or Reset (§3.6). Those sessions lose `remember` state. The session whose
  composable threw gets `Error(fatal = true)`, as today.

---

## 4. The policy engine

**Reads are filtered where the composition runs.** `canRead(record, principal)` runs at obtain points
during composition, exactly as today, against local data. That data is the cell's own, or a host's
replica. Hosts are trusted server code, so they receive unfiltered rows.

Per-principal filtering matters at the boundary between server and browser, which is where Jetlin
has always applied it. A2 filtered per principal at the authority instead. The critique showed that
this fails to send rows that become visible after a grant, and costs about 7,500 `canRead` calls per
changed row at 50 hosts × 150 principals. It is cut.

**Writes are judged once, at the authority, at commit.** The cell runs policy-simplification §2.1's
`transact`/`judge` verbatim: a before snapshot, an after snapshot, and
`allows(RecordChange(before, after, columns), principal)` for each touched record. A host also
judges locally so it can throw `AccessDenied` without a network round trip. The cell's verdict wins.

**Policy inputs are confined.** A policy may read only:
- the record itself;
- records in its own partition;
- `@Core` entities.

In development, a read observer on policy evaluation throws `PolicyReadsRemote`, and a conventions
test enforces the same rule. This confinement is what keeps the zookie a single integer.

**The core and the "new enemy" problem.** Each tenant's core has its own log, numbered with seq `c`,
and every partition cell and host replicates it. Each data log entry carries `deps.core`, the core
version the authority had when it judged that entry.

A write intent carries `base.core`. An authority that is behind on core pulls before it judges.

A replica that receives an entry with `deps.core` newer than its own core cursor **parks** the entry
until core catches up. So the sequence "remove Bob from team T, then add secret content to T's
project" can't arrive at Bob's host in the wrong order. Bob's `canRead` returns false in the same
recomposition that would otherwise have shown the secret. This is Zanzibar's zookie
(https://www.usenix.org/system/files/atc19-pang.pdf) in the form of D1's bookmarks
(https://developers.cloudflare.com/d1/best-practices/read-replication/).

**Leases, failing closed, at both hosts and authorities.**
- Every DO with live sessions, and every authority that judges writes, calls the home cell's
  `ping(lastSeq)` every 3 s. The reply is `{seq}`; if the caller is behind, it pulls.
- A successful ping or push renews a 10 s lease [assumption on both numbers].
- An auto-response can't do this job. It returns a fixed string, so it proves the socket is alive
  but not that no deltas were missed.

When the lease expires:
- sessions get a server-declared "reconnecting" overlay, and regions marked as policy-filtered are
  hidden;
- commits whose `allows()` read core cells are refused with a retryable error;
- this fixes D1's "reads keep working" and D2's unbounded staleness at the authority.

**Revocation bounds:**

| Situation | How long a revoked user can still see content |
|---|---|
| Tenant in one cell (most tenants) | 0: the same commit recomposes the viewer, and it goes out in the next patch |
| Across DOs, healthy | One push: about 5–20 ms in-region, 100–250 ms cross-ocean [third-party round trips] |
| Across DOs, home unreachable | At most 10 s lease plus 3 s ping interval, then hidden |
| Policy code itself changed | Up to the gradual-deploy window, about 300 s [third-party], on old-version hosts. The authority always judges with its own code |
| Already in a disconnected browser's DOM | Until the next patch. A server-declared `hideAfterOffline(n)` is optional |

**Isolation inside a pack.** Each tenant gets a `TenantScope`: its own `Db`, identity map, session
list and generated tables `t{shortId}_{entity}`. A session receives its scope once, from the route
and the verified token. No API returns another tenant's scope, and records from another tenant are
unreachable.

The SQL table-prefix check stays as an assertion, not the isolation mechanism. DO facets would give
physical separation, but whether a static class can be a facet is unverified, so facets come later.

---

## 5. Optimistic updates and latency hiding

### 5.1 The stance

jetlin-db's promise is restated in two parts:
1. **No server-side composition ever renders, or sends, a value the authority refused or hasn't made
   durable.** That covers the acting user's own server-side session and every other session.
2. **The acting user's browser may show effects the server declared,** marked as pending, until the
   event settles. The next settling patch reverts or confirms them.

Two forms of server-side speculation were considered and cut:
- **A2's shared overlay.** Co-hosted users would see unconfirmed values. A misclassified "content"
  column becomes a leak.
- **D3's per-session tentative layer.** It can't be built under one Recomposer: cell getters aren't
  composable and can't tell which composition is reading.

In-region writes take about 25–65 ms end to end (§8). So the only real win from server-side
speculation would be cross-ocean, and browser predictions cover that case.

### 5.2 The server-side protocol

**In a cell,** nothing changes: the handler commits synchronously, and `ack` already means the event
has settled.

**On a host,** D2's held handler applies:

```kotlin
val snap = Snapshot.takeMutableSnapshot(reads::observe, writes::observe)
snap.enter { handler() }  // transact writes replica cells inside snap; launches and navigation are queued
val intent = writes.intentOrNull(reads) ?: return snap.apply().also { flushQueued() }  // UI-only
session.pause()           // this session's later events queue; other sessions keep running
when (val o = cell.commit(intent)) {  // txId = H(sessionId, seq, k): retries are free
  is Committed -> { ingest(o.catchUp); snap.enter { writes.stamp(o.lsn) }; snap.apply(); flushQueued() }
  is Conflict  -> { snap.dispose(); ingest(o.fresh); rerun(handler) }          // ≤3, then WriteConflict
  is Denied    -> { snap.dispose(); rerun(handler, throwAtTransact = o.error) } // app catch blocks still work
  is Behind    -> retrySameTxId(backoff)                                         // schema or core lag
}
session.resume()
```

An intent carries:
- `reads`: `(id, columns, ver)`, plus shape keys and their versions to catch phantoms. Above 256
  entries it collapses to a coarse collection dependency.
- `writes`.
- `base.core`, `code.schema` and the principal.

The cell checks, in order: dedupe, schema, core freshness, read versions, then `judge`. It then
commits with `transactionSync`.

Two other pieces:
- `Add(col, n)` gives counters a write with no read dependency, so it never conflicts.
- The `Versioned(lsn)` merge fixes A1's fatal failure, where `apply()` after a confirmed blind write
  could fail and lose the handler's UI state.

**What authors see:** handlers must be re-runnable. Launches and navigation are deferred until apply,
and a Konsist rule forbids other side effects in handlers.

**After a crash or deploy:** the client reconnects with `hello{resume: unsettledSeqs}`. The new host
asks the cell for `status(txIds)`. A write that committed is already in the data. Anything else is
reverted, with a toast saying "may not have been saved".

### 5.3 Wire changes

```kotlin
Patch(rev, ack /* = settled */, ops, open: List<Long> = [], done: List<Long> = [])
// New server messages: Stale, Sleep, Redirect(to, epoch), Dropped(seq), Rebind
// New client messages: Wake, Hint(node, event, kind); hello gains protocol and client versions, and resume
// ListenerSpec gains: predict: List<Effect>, pending: PendingSpec?
```

**Ack-only patches.** Today `LiveView.kt:297` sends nothing when there are no ops
(`if (ops.isNotEmpty())`), so a prediction would never clear. The server now emits
`Patch(rev, ack, [])` when `ack` advances for an event that declared `predict` or `pending`, or that
carried a value. Every other event behaves as before, so the exact-op-stream tests stay stable.

**`open`/`done`.** Held intents and `rememberAction` work keep an event's seq "unsettled" after its
handler returns.

**Reassert on settle.** Once a node's last event is settled, if the server model differs from the
value or checked state the node last reported, the server emits a `prop`. Reading the code suggests
that today a checkbox toggle the handler refuses keeps its native `checked` value, and this fixes
that.

### 5.4 Server-declared client predictions (from D3)

```kotlin
Checkbox(checked = todo.done, onChange = { todo.update { done = it } },
         predict = { self.toggleProp("checked"); row.toggleClass("done") }, pending = Pending())
```

The `predict` block runs **during composition** and produces data: effects such as `Attr`,
`ClassToggle`, `PropToggle`, `Text`, `Hide` and `Show`, aimed at known node IDs. The browser never
runs composables.

Toggle effects are relative. An absolute value computed at render time would mispredict a second
click sent before the first is acknowledged.

`insertTemplate(id)` is limited to static, server-rendered HTML with placeholder IDs. Client-side
bindings would be the start of rendering in the browser.

The client keeps a ledger of `{seq, effects, undo(prior values, one class at a time)}`. Recording
prior values one class at a time means an undo can't wipe out the browser-owned classes that
`clientOnly` commands set. On every patch, in one synchronous task with no paint between steps:

1. rewind all pending predictions;
2. apply the patch's ops;
3. drop each prediction that has settled: its seq is no greater than `ack`, its seq is not in `open`,
   or its target node is gone;
4. replay the rest, capturing fresh prior values.

The invariant: after every patch, the DOM equals the server's state plus the unsettled predictions,
applied in seq order. Other users' patches are applied *underneath* the predictions. A correct
prediction is undone and redone in the same task, so nothing flickers. That assumes browsers don't
restart a transition without a style flush [assumption].

Closing the socket or receiving a `Reset` rewinds everything. A prediction older than 10 s is dropped
and sets `data-jl-stale`.

**Pending markers** add `.jl-pending` and `aria-busy` only if the event is still unsettled after
120 ms, and keep them for at least 300 ms. `:root` gets `data-jl-inflight`.

**Tooling:**
- `jetlin.latency(ms)` simulates latency during development.
- `assertPredictionMatches(node)` in jetlin-testing runs the real handler and compares its patch with
  the declared effects, so a misprediction fails CI.
- Each event carries a hash of its prediction. The server stops shipping predictions for any handler
  whose mismatch rate exceeds 5%.

**Hints.** A `Hint` on a link's pointerdown or hover starts the target route's declared `Fetch`es.

**When to predict:**

| Case | Behaviour |
|---|---|
| Pure UI state, `clientOnly` | Never waits |
| Write, user in the cell's region | Don't predict. A pending marker appears only past 120 ms |
| Write, user far from the cell | Predict simple fields the user owns: toggles, renames, reorders, hiding or deleting their own items |
| Never predict | Actions limited by policy (reassign, archive), aggregates and money, cross-partition sagas, `rememberAction` side effects, sending messages |

**On rejection,** the predicted effect reverts in the settling patch. The handler re-runs with
`transact` throwing the authority's `AccessDenied`, so the app's own `catch` UI renders. If nothing
catches it, the framework shows a toast: "Not saved: ⟨policy refusal message⟩". After 3 conflicts the
toast reads "Changed by someone else; your edit wasn't applied", shown above the fresh data.

---

## 6. Placement and scheduling

### 6.1 The directory

Tenant IDs carry their home region (`weur_7Kq…`), so the router knows which regional directory shard
to ask.

Lookup order:
1. the signed hint in the cookie or URL;
2. the isolate's LRU cache;
3. `caches.default`, with a 60 s TTL;
4. an RPC to the directory shard.

KV is never on the correctness path, because it can lag by 60 s or more [verified].

Every DO stores `owns(range, epoch)`. A request for a key it doesn't own gets `Moved{to, epoch}`
(forwarding rows are kept for 7 days) or `NotOwner`. The router drops its cache entry and retries
once.

### 6.2 Packing small tenants

New tenants enter a pack cell in their region by first-fit-decreasing on *predicted peak concurrent
visible sessions*. The prior is 5% of seats [assumption], replaced by the measured value after 7
days. A pack admits a tenant only if, afterwards:
- Σ memEstimate ≤ 32 MB;
- Σ storage ≤ 1 GB;
- there are at most 150 tenants;
- Σ commits/s ≤ 100 at p95.

```
memEstimate = 1 MB + liveNodes × 1.5 kB + residentRecords × 1.1 kB + readSetEntries × 30 B
```

The estimate counts DOM nodes, not sessions. Every draft calibrated its cap on the 113-node benchmark
page. A 1,000-node page costs roughly 1.2–1.5 MB per session.

A DO can't time its own CPU, because time is frozen during execution [from knowledge], so noisy
neighbours are measured by proxy:
- events, recomposed scopes and ops emitted;
- `rowsRead` and `rowsWritten`;
- `cpuTime` from a Tail Worker [likely].

Each tenant gets a token bucket of 50 events/s, with bursts of 200. A tenant above 40% of any pack
budget for an hour is **promoted** to its own cell.

Packs have one blast radius that can't be avoided: point-in-time recovery (PITR) restores the whole
DO, and an out-of-memory error (1102) restarts every DO in the isolate. Restoring one tenant inside
a pack therefore needs our own logical per-tenant export [assumption: cost unmeasured]. This is a
reason to promote paying tenants early.

### 6.3 Split key and the move state machine

Candidate roots, such as Team, Project or Document, are evaluated **on the JVM first**. jetlin-db
logs the root keys each transaction and each policy read touches. The controller proposes a root
only if at least 99% of commits and 95% of policy reads stay within one root.

DOs can't move or be renamed [verified], so a move always creates a new generation of names
(`:g{n+1}`):

```
PLANNED → PROVISIONING → COPYING → CATCHING_UP → FENCING → CUTOVER → DRAINING → DONE
                                   (any state before CUTOVER) → ABORTED
```

1. **PROVISIONING:** the source S calls `get()` on the target T, so T is placed near S.
2. **COPYING:** S records its log cut and streams rows in chunks of 8 MB or less.
3. **CATCHING_UP:** T applies `_log` entries after the cut.
4. **FENCING:** when lag is under 200 entries, S freezes the range. S is single-threaded, so the
   freeze takes effect immediately, with no lock.
   - UI-only events still run.
   - An event whose write set touches the frozen range is disposed and **parked whole**. A
     non-suspending handler can't be queued partway through, which corrects D1.
   - S ships the final log tail and the soft-hibernated sessions (`rememberSaved` blobs, ID lists and
     hashes).
5. **CUTOVER:** the directory runs `CAS(e → e+1, owner = T)`, then calls `T.activate(e+1)`. S sends
   `Redirect` and forwards the parked events.
6. **DRAINING:** S keeps its forwarding rows and a reverse log for 24 h, then deletes the moved rows.

**No two authorities.** S stops committing before the CAS, and T starts only after it. If the
directory can't be reached, S stays frozen. After 10 s S calls the directory itself rather than
waiting for an alarm, which can be up to a minute late.

**Triggers.** Any one of these, sustained for 10 minutes:
- storage above 2 GB, or above 512 MB once the partition has hosts;
- memEstimate above budget with 4 or more hosts;
- commits above 150/s;
- events above 350/s at p95.

Merging uses 8× hysteresis, 14 days below the merge thresholds, a 6-hour cooldown after any move,
and one move per tenant at a time. **Merging is for locality and fan-out, not cost:** an idle DO
costs only storage. A merge is also proposed when more than 5% of transactions cross partitions.

### 6.4 Scheduling compute by concurrent visible users

Sessions are sized by **concurrent visible** users, not seats. Each session is anchored to the
partition key in its route (`route("/p/{project}/…", partitionBy = Project)`).

Placement order:
1. Prefer the cell.
2. When the cell exceeds its node budget, the router chooses between hosts using power-of-two
   choices.
3. Open a new host when every host is above 80%.
4. Close a host after 30 minutes with no live sessions. It hibernates and costs only storage.

The token names the DO, so the GET and the WebSocket meet in one object. The render-to-connect gap
and the grace period (architecture.md §9) are local to that DO by construction. Moving a session
between DOs uses take-once handoff, `old.take(token)`, which keeps the `UiSessionStore.take`
contract.

### 6.5 Geography

The home region comes from the tenant's own setting or the signup request's continent, fixed by a
location hint on the first `get()`. Jurisdictions are enforced, not just hinted [verified]. Hints
count only on the first `get()` and are best effort [verified].

A partition is **re-homed** if more than 60% of its events come from one other region for 7 days. A
re-home is a move whose target has the new hint.

Far-region hosts are a later, opt-in phase. They would be enabled per region when the measured round
trip to home exceeds 40 ms and the region has enough users. They would use A2's shape-scoped partial
replicas (`RememberObserver` refcounts plus a 5-minute TTL), with explicit Loading states for cold
shapes.

---

## 7. Walkthroughs

Assumptions for every walkthrough:
- browser ↔ PoP round trip: 10–30 ms [assumption];
- PoP ↔ DO in the same metro: about 5 ms round trip [third-party];
- DO ↔ DO in the same metro: about 5 ms [third-party];
- write confirmation: 2–10 ms [unverified];
- handler, recompose and drain: 0.2–1 ms [measured].

**(a) A click that writes (toggle a todo).**

| Step | Time |
|---|---|
| Predicted effect painted | next frame, <16 ms |
| Event reaches the cell (browser → PoP → DO) | 7–18 ms |
| Handler, judge, `transactionSync`, bumps, apply, recompose, drain | ~0.5–1 ms |
| Output gate (or `storage.sync()`) | 2–10 ms |
| Patch back to the browser; rewind, apply, drop the prediction | 7–18 ms |
| **Total, cell in the user's metro** | **~20–50 ms** |
| From a host instead | +5 ms DO round trip, + the cell's gate, + ingest: **~30–65 ms** |
| Across the Atlantic, direct to the cell | ~115–175 ms; the prediction covers it |

**(b) Another user's write recomposes a viewer.** Same cell: the writer's commit applies, the
snapshot manager notifies, the viewer's scopes recompose in the same task or the next frame
(FramePolicy), and the patch is gated. About 0.25–0.5 ms of CPU per recomposed session [measured],
so 125 viewers cost 30–60 ms of single-thread CPU. That caps a cell at roughly 15–30 broadly visible
writes per second, which is why broadcast-heavy pages get lower caps. The viewer paints about
20–50 ms after the writer's click. A viewer on a host adds a push (2–5 ms one way) and an ingest.

**(c) A revocation.** An admin removes Bob from team T.
- **Single-cell tenant:** the `Membership` delete bumps `Membership(user = bob)`. Every `canRead`
  that walked Bob's memberships recomposes, and the record disappears from Bob's next patch, about
  20–50 ms after the admin's click.
- **Split tenant:** the core commit at the home cell is pushed to partition cells and hosts, 5–20 ms
  in-region. Their ingest applies core seq 41 and recomposes Bob's sessions. Any later content write
  is stamped with `deps.core ≥ 41` and parked until core 41 has been applied. If home is
  unreachable, Bob's sessions are hidden within 13 s.

**(d) First paint and socket attach for a new session, on a warm cell.**

| Step | Time |
|---|---|
| Router: isolate cache hit, or ~5 ms on a directory miss | ~0–5 ms |
| Compose the page (warm), HTML serializer | 2–10 ms |
| **Time to first byte** | **one browser round trip + ~10–20 ms** |
| WebSocket: router → `stub.fetch` upgrade → `acceptWebSocket` (hibernatable); first socket adopts the server-rendered DOM | one more round trip |

**(e) A cold tenant wakes.**

| Step | Time |
|---|---|
| First call to an idle DO | 113–394 ms [third-party; grows with script size] |
| Constructor instantiates the per-DO Wasm instance from the precompiled module | 2–4 ms [measured; per-DO instantiation is an assumption] |
| First composition, cold | ~40 ms [measured] |
| Core ping, if the tenant is split (cold in parallel) | +5 ms |
| **First byte** | **round trip + ~160–450 ms** |
| DO hibernated with sockets still open: first message after wake | Wasm instance, then rebuild from attachment or `_ui_sessions`, then rebind: round trip + 5–10 ms |

This is why the router stays free of Kotlin and the cell script stays small.

**(f) A partition split while users are online.**

| Phase | Duration | Users notice |
|---|---|---|
| Copy | 2 GB at an assumed 25 MB/s ≈ 80 s | Nothing |
| Catch up | Seconds | Nothing |
| Fence | ≤1–2 s | Writes in the range are held; pending markers appear |
| Cutover (CAS + activate) | ~10 ms | — |
| Redirect, then reconnect | Jitter 0–1 s + one round trip; T rebuilds each session (~2 ms) and rebinds | Parked events replay on T if the hash matches; otherwise Reset, the event is dropped, and a "try again" toast appears. `remember` state on S's live sessions is lost (documented) |

---

## 8. Budgets

**Memory per DO.** An isolate has 128 MB, shared by DOs of the same class [verified]. How many share
one is unknown. The conservative plan assumes 2 heavy DOs per isolate and at most 50% occupancy for
garbage-collector headroom.

| Item | Conservative (32 MB live data) | Alone in its isolate (64 MB) |
|---|---|---|
| Wasm instance, runtime, structures | 1–2 MB (0.7 MB measured base) | 2 MB |
| Live DOM nodes × 1.5 kB | 14k → 21 MB | 30k → 45 MB |
| ≈ sessions of the 113-node benchmark page | ~125 | ~265 |
| ≈ sessions of a 1,000-node page | ~14 | ~30 |
| Resident records × 1.1 kB | 6k → 6.6 MB | 12k → 13 MB |
| Read ledgers, query versions, `_watch` buffers | ~1.5 MB | ~3 MB |
| Core replica cells kept hot | ~1 MB | ~2 MB |

Two unknowns remain. It isn't known whether the SQLite page cache and hibernated sockets count toward
the 128 MB. Soft-hibernated sessions cost about 364 B plus their ID list, in SQLite rather than
heap.

If 20% of users are connected at peak and half of their tabs are visible [assumption], one cell
without hosts serves about 1,250 users on light pages, or about 140 on 1,000-node pages. That is why
server-side list windowing and a per-session node budget, warned about in development, ship in
phase 1.

**Latency per interaction:**

| Interaction | Cell in the same metro | Same continent | Across the Atlantic |
|---|---|---|---|
| UI-only event | 15–40 ms | 25–50 ms | 110–165 ms |
| Write | 20–50 ms | 30–60 ms | 115–175 ms |
| Write via a host | 30–65 ms | 40–75 ms | — |
| Predicted toggle, perceived | <16 ms | <16 ms | <16 ms |

**Cost drivers:**
- **Duration, the dominant cost.** An always-awake DO costs $4.05 a month; business-hours residency
  about $1.24; hibernated, nothing [verified pricing].
- **Client events.** Billed 20:1, so 1 million events ≈ $0.0075. Outgoing patches are free.
- **Host and replication traffic.** Each intent, push or lease ping is an RPC billed at $0.15 per
  million. One subscriber pinging every 3 s all month makes about 0.86 million requests ≈ $0.13.
- **Rows written.** About 5 per single-row commit, plus about 3 for each host replica. 50 million are
  included, then $1 per million.
- **Storage.** $0.20 per GB-month, multiplied by the number of hosts.
- **Moves.** About 3 rows written per row copied: a 2 GB move is about 20 million rows ≈ $20.

---

## 9. What changes for application authors

**Kept:**
- composition on the server;
- ops as the diff, and `HtmlApplier`;
- the protocol `event` → `patch{rev, ack, ops}`, with adoption and Reset;
- `remember` versus `rememberSaved`;
- non-suspending `Db.transact` with commit before apply;
- plain-Kotlin `canRead`/`allows(RecordChange)`, grants, `policy { }` and refusal messages;
- access checked when a record is obtained;
- reactive revocation;
- `Fetch` and `rememberAction`;
- `testTag`, `recordUpdate`, `hibernateAndRestore`.

The JVM build stays. jetlin-runtime and jetlin-html become multiplatform: replace `System.nanoTime`,
`synchronized`/`@Volatile` in `Fetch`, and the Java atomics. jetlin-db gets a synchronous
`SqlDriver`, backed by sqlite-jdbc on the JVM and `ctx.storage.sql` on Workers. So the
exact-op-stream tests keep running on the JVM.

**Changed:**
1. Entities need `@Colocate(root, via)` or `@Core`. IDs are opaque ULIDs. Cross-root references are
   `RemoteRef<T>`, and a transaction writes one root (`CrossColoWrite`).
2. Policies may read only their own partition and `@Core`, enforced in development. They gain an
   optional indexable `readScope`.
3. Collections are bounded `Query` values with `orderBy`/`window`.
4. Handlers must be re-runnable. Launches and navigation run after apply. `WriteConflict` and a
   default rejection toast are new. Counters use `Add`.
5. Listeners gain `predict` and `pending`.
6. `remember` is lost more often: on hidden-tab hibernation, on DO eviction, on deploys and on moves.
   `rememberSaved` is capped at about 12 KB.
7. Routes declare their partition key. Tenant-wide pages read rollup tables on the home cell.
8. Mutable top-level and `object` state is banned by a Konsist rule. Waits longer than 30 s use
   `rememberAlarm`, which is multiplexed onto the DO's single at-least-once alarm and needs
   idempotent keys.

**Dropped:**
- every record in memory, and loading whole tables at startup;
- a single process-wide `writeLock`;
- Ktor, in this deployment target.

---

## 10. Alternatives considered

- **Edge-first session actors (A2) as the default.** It wins only for users more than about 40 ms
  from home. In return:
  - reads become asynchronous on cold shapes, a waterfall of round trips to home;
  - the speculative overlay is shared, so co-hosted users can see refused values, and a misclassified
    column leaks;
  - filtering per principal at the authority misses grants and costs O(hosts × principals) per row;
  - the lease built on auto-response can't detect missed deltas.

  A2's collapsed mode is what this design calls a cell. Its shape refcounts, `take()` handoff,
  bookmark cookie and "one authority transaction per global apply" survive. Far-region hosts survive
  only as an opt-in later phase.
- **A1's mirrors as written.** A blind write could fail at `apply()` after the authority confirmed
  it. Mirrors had no deterministic `txId` and no status query, every new mirror needed a full
  export, and A1 relied on deterministic pre-order IDs. Replaced by D2's intents, persisted
  replicas, earlier splits and rebind.
- **D3's Layer S, `PredictOutcomes` and prerendering with `movableContentOf`.** Layer S can't be
  built. The other two depend on unproven sandboxing of effects and moves through the applier, so
  they are deferred behind experiments.
- **One Recomposer or Wasm instance shared by the DOs in an isolate.** Faster per the benchmark,
  which was measured inside one DO. Across DOs it would drive another DO's sockets and SQL from the
  wrong context, bypass per-DO gates, and let one tenant's exception kill everyone's recomposer.
- **Deterministic named mutators (Replicache, Calvin) instead of OCC.** They enable rebasing, but
  they require giving up lambda handlers. OCC on Compose's own read and write observers keeps
  lambdas, and Convex shows that retrying I/O-free transactions works.
- **Two-phase commit across partitions.** A coordinator in DO memory is lost on eviction, and the
  6-connection limit makes it worse. Sagas through an outbox, alarms and dedupe are used instead.
- **Other platform pieces:**
  - *D1 as the database:* async from a DO, 10 GB per database, single-threaded.
  - *KV for sync:* lags 60 s or more.
  - *Containers running the JVM:* cold starts of 1–3 s officially, 24 s at p95 in one benchmark; at
    most an escape hatch behind a DO.
  - *The Agents SDK's full-state broadcast:* coarser than Jetlin's ops.
  - *CRDTs by default:* Figma-style last-writer-wins per field plus the `ack` rule is enough. Rich
    text with several editors would opt into OT or Eg-walker.
- **Running Compose in the browser** (ruled out by the maintainer). It would make UI-only events
  instant everywhere. It would also:
  - ship raw records to the browser, where revocation can't take them back (the Jazz lesson);
  - require client-side policy evaluation;
  - grow the runtime from 8.6 kB to about 400 kB or more of Wasm;
  - create two sources of truth.

  Server-side composition is still the right call. Policy filtering happens before data leaves, the
  client stays thin, and a DO-local SQLite makes the server path take tens of milliseconds for users
  in-region. Declared predictions cover what remains.

---

## 11. Riskiest assumptions and the cheapest experiment for each

The first spike targets the biggest risk.

0. **Rebuild the prototype.** The research prototype wasn't kept. Recreate the headless Kotlin/Wasm
   composition with an op-recording applier and a drain dispatcher, run it in `workerd`, and
   reproduce the [measured] figures: about 377 KB of Wasm (132 KB gzipped), about 140 KB of heap per
   113-node session, and about 0.21 ms per click. Kotlin's generated loader mistakes workerd for
   Node, so a small custom loader is needed.
1. **Isolate co-residency and per-DO Wasm.** If DOs crowd into one isolate, or a per-DO instance
   can't be created in the constructor, every budget shrinks and the fix for sharing one Recomposer
   across DOs fails.
   - *Experiment (1 day, cents):* deploy 50 DOs that log a module-global isolate ID, instantiate the
     module in the constructor (timed), and each hold 16/32/48/64 MB. Record when error 1102
     appears.
   - Also check whether DO B's `ws`/`sql` can be used from A's context (it should fail).
   - Repeat with 10k hibernated sockets holding 12 KB attachments, and with heavy SQLite reads, to
     see what counts toward 128 MB.
2. **The output gate.** Does it hold `ws.send()`? What is confirmation latency at p50/p99? How far
   does one socket's writes delay another's UI-only patches?
   - *Experiment (1 day):* one DO with two sockets from the same metro. Socket A writes in a loop;
     socket B sends UI-only events. Compare against an `await storage.sync()` variant, and force a
     write failure right after a `send()` to see whether the send is suppressed.
   - Run it in weur, enam and apac for 24 h, together with DO↔DO round trips between DOs created by
     `get()` from one another.
3. **Residency.** Does a `setTimeout` chain keep a DO with hibernatable sockets resident for 6 hours
   or more, and what is billed? *Experiment: an afternoon plus 24 h passive.*
4. **Memory by page size on Wasm.** Heap for 100-, 500- and 2,000-node pages, separating the fixed
   cost per session from the cost per node. *Experiment: half a day, local.*
5. **Held snapshots with `Versioned` merge.** 250 sessions, random held handlers, blind-write
   conflicts and interleaved ingest. Assert that replica cells equal a replay of the log, and that
   heap and per-event cost stay near 0.32 ms. *Experiment: 1 day, extending the prototype.*
6. **SQL marshalling** from Kotlin/Wasm, per row and per column. *Experiment: half a day.*
7. **Locality of real applications** (JVM only, no Cloudflare work). Do 99% of transactions and 95%
   of policy reads stay within one root, and do policies read only their own partition plus core?
   - *Experiment:* a policy read observer in jetlin-db-testing over `samples/teams`, plus a fuzzer
     that interleaves revoke and write across replicas at skewed versions.
8. **Rebind.** A jetlin-html test: hibernate, rebuild, rename the nodes, then assert the next event
   dispatches and the op stream is exact. *Experiment: half a day.*
9. **Recomposer pool overhead and failure recovery (§3.7).**
   - Measure the per-click cost with K = 1, 8, 32 and 150 running Recomposers in one Wasm instance.
   - Check that a composable throwing in one pool slot leaves the other slots' sessions untouched
     and that the failed slot rebuilds from `_ui_sessions`.
   - *Experiment: half a day, extending the prototype.*
10. **The same apply-observer cost on today's JVM build** (no Cloudflare work).
    - `CompositionHost.kt:83` creates one Recomposer per UI session. Near `maxUiSessions = 10,000`,
      every global write, such as a jetlin-db commit, probably visits 10,000 apply observers
      [unmeasured on the JVM].
    - *Experiment:* a `:samples:demo:benchmark` variant that clicks one session while N sessions sit
      idle.
    - If the cost reproduces, it matters for single-node Jetlin today, independent of Cloudflare.

---

## 12. Phased roadmap

**Phase 0, spikes (2 weeks).** Experiments 0–10. Go/no-go gates:
- per-DO Wasm instance is feasible;
- a live-data budget of 32 MB or more per DO;
- write confirmation p99 under 20 ms in-region;
- residency through timers works.

If the gate check fails, re-plan before building on it. Small budgets mean hosts become the normal
case. A wide gate means Jetlin does its own gating with `sync()`.

**Phase 1, a minimal end-to-end slice (one tenant, one cell).**
- Multiplatform jetlin-runtime and jetlin-html; a Kotlin/Wasm build with a custom loader, a drain
  dispatcher, and the Recomposer pool from §3.7.
- A Workers adapter with a TypeScript router, hibernatable sockets, adoption and Reset.
- The `SqlDriver`, `RecordCache` with pinning, three-level invalidation and the cell commit path.
- `_ui_sessions` write-behind, visibility-based hibernation, residency heartbeats and protocol
  versioning.
- Client: ack-only patches, reassert on settle, pending markers, the latency simulator.
- Tests: the JVM suite unchanged, plus a workerd smoke suite and Playwright against `wrangler dev`.

**Phase 2, tenancy and policy at scale.**
- `TenantScope` packs, the directory with epochs and `Moved`, and `@Colocate`/`@Core` with KSP
  checks.
- Core replication with `deps.core` parking and leases.
- `RemoteRef`, rollups, the move state machine (promote, split, merge) and load reports.
- Rebind and `_watch`.
- The prediction ledger with `predict`, `assertPredictionMatches` and mismatch telemetry.

**Phase 3, overflow hosts.**
- OCC write intents, the held handler, `Versioned` merge, `txId` dedupe, `status()` and resume.
- Persisted full replicas, pre-provisioning from observed peaks, power-of-two placement, and earlier
  splits for partitions with hosts.

**Phase 4, geography and experiments.**
- Re-homing.
- Far-region hosts with shape-scoped replicas behind a per-region flag.
- Facets for physical isolation inside packs, once static classes are confirmed.
- Per-tenant logical backup.
- `PredictOutcomes` and prerendering on navigation, only if their experiments pass.

---

## 13. Platform sources

- https://developers.cloudflare.com/durable-objects/api/sqlite-storage-api/
- https://developers.cloudflare.com/durable-objects/concepts/durable-object-lifecycle/
- https://developers.cloudflare.com/durable-objects/best-practices/websockets/
- https://developers.cloudflare.com/durable-objects/best-practices/rules-of-durable-objects/
- https://developers.cloudflare.com/durable-objects/platform/limits/
- https://developers.cloudflare.com/durable-objects/platform/pricing/
- https://developers.cloudflare.com/durable-objects/reference/in-memory-state/
- https://developers.cloudflare.com/durable-objects/reference/data-location/
- https://developers.cloudflare.com/durable-objects/api/alarms/
- https://developers.cloudflare.com/workers/platform/limits/
- https://developers.cloudflare.com/workers/runtime-apis/rpc/
- https://developers.cloudflare.com/d1/best-practices/read-replication/
- https://blog.cloudflare.com/sqlite-in-durable-objects/
- https://blog.cloudflare.com/durable-objects-easy-fast-correct-choose-three/
- https://context-mode.com/blog/durable-objects-in-production (third-party latency figures)
