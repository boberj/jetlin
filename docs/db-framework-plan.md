# jetlin-db — implementation plan

**Status:** phases 1–6 implemented. `:samples:demo` was ported and then deliberately put back on its own
in-memory store — see the last row of §13, which also records what the port cost, because that is the part
worth keeping. `./gradlew build` is green — 341 tests in the main build and 10 in the migration tooling's
included build — and the browser suite is 35/36: the same 35/36 it was before any of this work, the one
failure being pre-existing and unrelated. Nothing from phases 1–6 is outstanding; §11 is designed and
deliberately not started.
`docs/db.md` is the document that describes what exists; this one is the work order and can be archived.
**Audience:** Claude Code, working in the jetlin repository
**Companion reading:** `docs/architecture.md`, `samples/demo/src/main/kotlin/jetlin/samples/demo/Store.kt`

---

## 0. How to use this document

Read sections 1–5 before writing any code. They are the reasoning behind the design, and several of
the implementation steps look arbitrary or wrong without them.

Sections 6 onwards are the work. Each phase is a self-contained unit that ends with a green build
and a demonstrable capability. Do not start a phase before the previous one's acceptance criteria
pass. Do not merge phases: the point of the ordering is that each one can be abandoned or redesigned
without unpicking the ones before it.

When something in this plan turns out to be wrong — and some of it will be — record the correction
in section 11 rather than silently deviating. The next session will have only this file.

---

## 1. What is being built

A persistence framework for jetlin applications. Entities are ordinary Kotlin objects that live in
memory, backed by Compose snapshot state, durably stored in SQLite. Reading a field subscribes the
composable that read it; writing a field commits to disk and recomposes every session that was
reading it. Access control is declared per entity as a Kotlin function and enforced at the point
data is obtained.

The design goals, in priority order:

1. **Ease of use.** One mental model, no cache to invalidate, no query language to learn for the
   common case.
2. **Per-record access control** covering three shapes: owner-only, shared by some property such as a
   team, and readable by many but writable by few.
3. **Type safety** without a separate query DSL to keep in step with the schema.
4. **Migrations** that are generated, reviewable and editable.

The target application is what jetlin targets: a handful to a few thousand users, one node, one
process, a working set that fits comfortably in memory.

---

## 2. History — how this design was arrived at

This section exists so that a later reader does not re-litigate settled decisions or, worse,
re-introduce a rejected one because it looked clever.

### 2.1 Four candidates were considered

| | Approach | Outcome |
|---|---|---|
| A | SQL-first, SQLDelight-style: `.sq` files generate typed Kotlin | Rejected. Access control is by convention only; a developer writes the `WHERE` clause by hand and can forget it. |
| B | Kotlin DSL table objects, Exposed/Ktorm-style | Rejected as the primary model. Requires maintaining table objects alongside domain classes, and a query-based read path fits badly with Compose's read tracking. |
| C | Annotated data classes, KSP-generated typed queries, principal as a required context parameter | **Partly adopted.** Its schema declaration and codegen approach, its migration diffing, and its compile-time principal requirement all survive into the final design. |
| D | Object-graph persistence, EclipseStore-style | **Partly adopted.** Its live-object read/write model survives. Its lack of a query story and its class-evolution migration model do not. |

The final design is C and D fused: C's typed, policy-gated entry points as the only way to *obtain*
an entity, D's live mutable objects as what you get *back*.

### 2.2 Why a memory image rather than a query-backed ORM

The decisive constraint is that jetlin composes on a thread-confined session dispatcher
(`Dispatchers.Default.limitedParallelism(1)`, see `CompositionHost`). A lazy relation load inside a
composable would block that thread. Partial residency therefore forces either blocking reads during
composition or an asynchronous loading protocol that would dominate the API.

Keeping the working set resident removes the problem entirely: relation traversal is a pointer
dereference, there is no N+1, and no read ever blocks. The cost is that memory becomes a real
constraint shared with the session compositions, which is acceptable at the stated scale and must be
documented as a cliff rather than discovered as one.

### 2.3 Why the snapshot system is the transaction system

Compose snapshots are MVCC: isolated reads, an atomic apply, conflict detection on merge. A database
transaction is the same shape. Committing to SQLite *before* calling `Snapshot.apply()` means a
rejected write is never visible to any composition, so the browser never sees a value the database
refused. This is strictly better than the usual optimistic-update-then-roll-back dance, and it is the
single strongest technical argument for this design. Do not lose it.

`CompositionHost.transact` already wraps every event handler in `Snapshot.withMutableSnapshot`, and
nested mutable snapshots are legal, so this needs no change to `jetlin-runtime`.

### 2.4 Why access control is enforced at acquisition, not at read

Three options were weighed:

- **A. A reference is authority.** Queries, lookups and relation traversal are gated. Once you hold
  an entity, field reads are unchecked.
- **B. Per-principal facades.** Every read goes through a per-principal wrapper and is checked.
- **C. Reads require a principal in the type**, via context-parameter extension properties.

**Option A was chosen.** B costs an allocation and a check on the recomposition hot path and makes
the type flowing through application code a view rather than an entity, complicating identity and
equality. C propagates `Principal` into the signature of every composable that touches data, which is
invasive on every page, and still does not solve transitive visibility (section 5.3).

A is what Rails, Django and Prisma effectively give you once a repository layer exists. Its failure
mode is a leaked reference, and section 4.8 describes the development-time detector that makes that
failure findable rather than invisible.

**This is the load-bearing decision of the whole design.** If it is ever revisited, most of section 4
changes with it.

---

## 3. Context — what jetlin already provides

Read these before designing anything. The framework fits into existing machinery rather than
bringing its own.

**`samples/demo/.../Store.kt` is already this design, minus persistence.** `Todo` holds
`var done: Boolean by mutableStateOf(done)`, `TodoStore` is process-wide, and the comment records
that a write recomposes readers in other users' sessions. That file is the proof the model fits, and
porting it is the acceptance test for phase 6.

**`CompositionHost.transact`** wraps handlers in `Snapshot.withMutableSnapshot` and awaits idle. One
click is already one atomic state mutation producing one patch.

**`GlobalSnapshotManager`** already registers a global write observer and pumps
`Snapshot.sendApplyNotifications()`. Writes made outside a composition — a background job, a startup
load — already reach every recomposer. Nothing new is needed for cross-session reactivity.

**One thread per session.** Event handling, recomposition and patch draining cannot interleave. This
is what makes a thread-confined ambient principal safe, which it would not be on a normal async server.

**`JetlinConfig.attributes { }`** computes session-scoped values from the originating HTTP call, and
its KDoc states that it runs again when a socket wakes a hibernated session, specifically so the
principal is recomputed rather than trusted from a stale snapshot. This is exactly the hook the
principal needs. Read that KDoc; it is the contract.

**`rememberSaved` is JSON-shaped.** Entities structurally cannot be stored in session state, only
ids. This enforces one of the guardrails for free.

**`FramePolicy.Paced`** bounds how often a session turns state changes into patches, which matters
once one write can fan out to many sessions.

**`:conventions`** holds repo-wide rules as Konsist tests. New rules this framework needs go there.

---

## 4. The design

### 4.1 Modules

Add to `settings.gradle.kts`:

```
:jetlin-db          runtime: Record, columns, policies, identity map, transactions, SQLite
:jetlin-db-ksp      KSP processor: schema metadata, Draft types, column objects
:jetlin-db-gradle   Gradle tasks: dbDiff, dbMigrate, dbVerify
:samples:teams      a multi-user sample exercising all three access shapes
```

`:jetlin-db` depends on `:jetlin-runtime` (for the snapshot machinery), not on `:jetlin-html`. The
optional glue that reads the principal out of a `CompositionLocal` goes in a small
`:jetlin-db-html` module or behind an interface, so that `:jetlin-db` stays usable headlessly and
testable without a composition.

`jetlin-` modules run under `explicitApi()`. Public API needs stated visibility and return types.

**Boundary discipline.** `Record`, `Policy`, `View`, `Id` and the cell delegate must contain no
database concepts — no SQL, no connection, no transaction, no table. Section 11 extracts them into a
`:jetlin-data` core shared with external-system adapters, and that extraction should be a file move
rather than a redesign. This costs nothing now and is the whole difference between the two later.

**Do not modify `:samples:demo` until phase 6.** It backs 36 Playwright tests and 16 application
tests; breaking it early makes every subsequent failure ambiguous.

### 4.2 Entities

```kotlin
@Entity
class Todo(
    @Owner val owner: User,
    title: String,
) : Record() {
    var title by column(title)
    var done by column(false)
    var archived by column(false)
    var project: Project? by reference()

    companion object : Policy<Todo, User> by owned(Todo::owner)
}
```

- `Record` supplies identity (`id`), equality by identity, and the write-recording hook.
- `column(initial)` is a property delegate over `mutableStateOf`. Reads subscribe. Writes go through
  the policy check and the write recorder.
- `reference()` is a delegate holding another `Record`, persisted as a foreign key, resolved to an
  object reference at load.
- `hasMany<T>()` is the inverse, returning a policy-filtered `View<T>`.
- Constructor parameters that are also columns are passed to the delegate, as shown. `@Owner` marks
  the ownership column for the `owned()` shorthand and for migration metadata.

Entities are classes with mutable properties, not immutable data classes. That is inherent to the
model and was accepted knowingly.

### 4.3 Policies

```kotlin
public interface Policy<T : Record, P : Principal> {
    public fun canRead(record: T, principal: P): Boolean
    public fun canWrite(record: T, principal: P): Boolean = canRead(record, principal)
    public fun canWrite(record: T, column: Column<T, *>, principal: P): Boolean = canWrite(record, principal)
    public fun canCreate(record: T, principal: P): Boolean = canWrite(record, principal)
    public fun canDelete(record: T, principal: P): Boolean = canWrite(record, principal)
}
```

The principal is an ordinary parameter, not a context parameter. Policies are called by the framework
and never by application code, so there is nothing to protect at this layer. Context parameters are
for the application-facing API only.

Because the graph is resident, a policy is a plain Kotlin expression. There is no SQL translation, no
expression tree, and no second representation to keep in sync. This is the main thing the memory
image buys.

The three shapes:

```kotlin
// Owner only.
companion object : Policy<Todo, User> by owned(Todo::owner)

// Shared by team, written by owner.
companion object : Policy<Todo, User> {
    override fun canRead(record: Todo, principal: User) =
        record.owner == principal || record.project?.team in principal.teams
    override fun canWrite(record: Todo, principal: User) =
        record.owner == principal
}

// Read by team, one column admin-only.
override fun canWrite(record: Todo, column: Column<Todo, *>, principal: User) = when (column) {
    Todos.archived -> principal.isAdmin
    else -> record.project?.team in principal.teams
}
```

`principal.isAdmin` and `principal.teams` are reads of a live `User` entity, not of a snapshot taken at
login. Section 4.7 explains why that matters.

**KSP must fail the build if an `@Entity` declares no policy.** An entity with no access rules is
almost always an oversight, and this is the cheapest safety property available.

### 4.4 Getting a principal

```kotlin
val PrincipalKey = AttributeKey<User?>("principal")

jetlin {
    attributes { call ->
        val principal = call.sessions.get<Auth>()?.userId?.let { db.authenticate(it) }
        mapOf(PrincipalKey to principal)
    }
}
```

**The principal is nullable.** An earlier draft of this plan threw `Unauthenticated` here, which is
wrong: a login page and a marketing page have to be reachable. Authentication is a *route*
requirement, declared per route in 4.11, not a precondition of having a session.

`db.authenticate(id)` is the framework's single privileged root: it resolves a `User` without a
principal, because otherwise the system cannot bootstrap. It must be the *only* such entry point, and a
`:conventions` test should assert that nothing else in `:jetlin-db`'s public API returns a `Record`
without a principal in scope.

Context parameters are lexical and do not flow through a `@Composable () -> Unit`, so views need a
bridge:

```kotlin
@Composable
public fun WithPrincipal(content: @Composable context(User) () -> Unit) {
    val principal = LocalPrincipal.current
    with(principal) { content() }
}

view("/", title = "Todos") { WithPrincipal { TodoListPage() } }
```

Context parameters are stable from Kotlin 2.4. **Check `gradle/libs.versions.toml` before starting
phase 3** and record the outcome in section 11. If the project is on an earlier version, either
upgrade or fall back to an explicit `principal: User` parameter on roots, which costs ergonomics but
nothing structural.

### 4.5 Reading

Roots require a principal; everything downstream does not.

```kotlin
@Composable
context(principal: User)
internal fun TodoListPage() {
    val filter = LocalTodoFilter.current.value
    Ul({ classes("todos") }) {
        db.todos.filter { it.title.contains(filter, ignoreCase = true) }
            .forEach { todo -> key(todo.id) { TodoRow(todo) } }
    }
}

@Composable
internal fun TodoRow(todo: Todo) {
    Span({ classes("todo-text") }) { Text(todo.title) }
}
```

`db.todos` is a `View<Todo>`: a lazy, read-filtered `List<Todo>` over the identity map. `filter`,
`sortedBy` and `groupBy` are the stdlib. There is no query DSL, deliberately — at this scale a linear
scan over resident objects is fine, and declared indexes can be added later without changing any call
site.

Lookups and relation traversal are gated identically:

```kotlin
context(principal: User)
public fun <T : Record> View<T>.find(id: Id<T>): T?    // null, not someone else's record
```

`project.todos` yields only records this principal may read.

### 4.6 Writing

```kotlin
Button({ onClick { todo.update { done = !done } } }) { Text("Toggle") }

db.todos.add(Todo(owner = principal, title = draft.value.trim()))
todo.delete()
```

`update` carries `context(principal: User)`, so a mutation with no principal in lexical scope does not
compile. The `Draft` receiver is a generated per-entity type whose setters consult the column-level
policy, so `archived = true` can be denied while `title = "x"` in the same block succeeds.

A bare `todo.done = true` was considered and rejected: a property setter has nowhere to put a context
parameter, so it would fall back to a runtime check against an ambient principal. `update { }` costs
eight characters and keeps the compile-time guarantee. A future `owned()`-only sugar could reinstate
bare assignment, but not in v1.

The transaction:

```kotlin
public suspend fun <T> Db.transact(block: () -> T): T {
    val writes = mutableListOf<Write>()
    val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { writes += it.asWrite() })
    return try {
        val result = snapshot.enter(block)
        connection.transaction { conn -> writes.forEach { it.flush(conn) } }
        snapshot.apply().check()
        result
    } catch (t: Throwable) {
        snapshot.dispose()
        throw t
    }
}
```

The write observer gives dirty tracking for free — no persistence context, no dirty-check pass.
Commit precedes apply, so a denial or a constraint violation produces no patch at all.

### 4.7 Reactive authorization

Because policies read live snapshot state, and reading a filtered `View` subscribes to whatever the
policy touched, revocation is reactive. An admin writing `user.teams` invalidates the composables
that iterated a team-filtered collection, and the shared records disappear from that user's open page
with no invalidation code anywhere.

This is emergent, not a feature to build, but it is worth an explicit test because it is easy to lose
by accident — for example by caching a policy result per entity instead of per (entity, principal), or
by snapshotting the principal at login.

The corollary is that **policies sit on the recomposition hot path.** They must be cheap, pure and
free of side effects. Document it; consider a Konsist rule that policy bodies do not call suspend
functions or touch IO.

### 4.8 Guardrails for option A

These are what make "a reference is authority" survivable. All are cheap; none is optional.

1. No ungated `get(id)` reachable from application code. `authenticate` is the sole exception.
2. Relation collections are policy-filtered, accepting the per-read cost.
3. Entities cannot enter `rememberSaved` — already enforced by its JSON shape, but assert it.
4. Writes re-check, because a reference can outlive the check that produced it. Writes are rare
   enough that the cost does not matter.
5. Exactly one escape hatch, named `unsafe`, greppable, logged at WARN.
6. **The leak detector.** In development and test builds, each entity records the principal that
   acquired it, and every read asserts the ambient principal still matches. Compiled out in production
   behind a build flag. This converts leaked-reference bugs from an invisible property into a test
   failure with a stack trace at the acquisition site.

Item 6 is the highest-value item in this section. It does not make option A sound; it makes option
A's actual failure mode findable, which is the difference between a model you can ship and one you
can only hope about.

### 4.9 Storage

SQLite via `org.xerial:sqlite-jdbc`, in WAL mode, `synchronous=NORMAL`, `foreign_keys=ON`, a
`busy_timeout` set. One file, in process, no server.

The process owns the file: take an exclusive lock and record `PRAGMA data_version` at startup so an
outside writer is detected loudly rather than quietly corrupting the resident graph.

At boot, load tables into the identity map, resolve references, build declared indexes. For the
target scale this is well under a second and can be made lazy per table later if it stops being.

Litestream is the documented backup story. No code changes required; a deployment note.

### 4.10 Migrations

KSP emits the schema from the entity classes into a snapshot file checked into the repo.

```
./gradlew dbDiff "share_todos_by_team"   # diffs entities vs snapshot, writes editable SQL
./gradlew dbMigrate                       # applies pending migrations
./gradlew dbVerify                        # CI: fails if entities and snapshot disagree
```

Generated migrations are SQL files a human reads and may edit. SQLite's `ALTER TABLE` supports only
add column, drop column, rename column and rename table; everything else requires the 12-step
rebuild, which the generator must emit automatically. Views referencing a rebuilt table are the known
trap here.

Destructive changes require an explicit acknowledgement in the migration file rather than being
generated silently. Startup refuses to boot on a schema mismatch.

### 4.11 Protecting routes and views

Route protection is three separate questions that are easy to conflate:

1. **Is there a principal at all?** Authentication. Answered in 4.4, before any composition exists.
2. **May this principal reach this route?** Coarse and role-shaped: `/admin/*`.
3. **Does the thing this route names exist for this principal?** `/todo/42` where 42 is someone else's.
   `find` already returns null (4.5); what is missing is what the *route* does about it.

The third is the one that leaks in practice, and the demo currently has the insecure shape:
`view("/todo/{id}") { TodoStore.find(pathParam("id")) }` is a textbook insecure direct object
reference.

**Guards live in the route table, not in the view body.** A guard inside the view has already run the
view.

```kotlin
view("/login",       title = "Sign in")                       { LoginPage() }
view("/todos",       title = "My todos", requires = SignedIn) { WithPrincipal { TodoListPage() } }
view("/admin/users", title = "Users", requires = { it.isAdmin }) { WithPrincipal { AdminUsers() } }
```

The outcome is a value, never an exception. A view that throws ends the session and restarts the
page — that behaviour is pinned by the demo's error test — and guards must not use that path.

```kotlin
public sealed interface Access {
    public data object Allow : Access
    public data object NotFound : Access
    public data class Redirect(val to: String) : Access
}
```

`SignedIn` resolves to `Redirect("/login?next=$url")`. **A failed role check resolves to `NotFound`,
not to a forbidden page**, unless the route's existence is already public: a 403 on `/admin/users`
confirms there is an admin panel. Default to invisibility; make disclosure the explicit choice.

**Entity-bound routes are the part worth building.** Let the route resolve its own subject:

```kotlin
view(
    "/todo/{id}",
    subject = { params -> db.todos.find(TodoId(params["id"])) },
    title = { todo -> todo.title },
) { todo ->
    TodoDetailPage(todo)
}
```

`find` is gated by 4.5, so it returns null for a record this principal may not read, and null resolves to
`NotFound` before the view is composed. The body receives a non-null, already-read-checked `Todo`.

This deletes the bug class rather than guarding against it: under this API the insecure version is
not expressible, because there is no path parameter left to look up by hand. It also fixes the title,
which would otherwise render another user's data into `<head>` before the body checked anything.

**Eviction is free.** The guard is evaluated inside the composition and reads live snapshot state, so
it inherits reactive revocation (4.7):

```kotlin
@Composable
internal fun Guarded(route: Route, content: @Composable () -> Unit) {
    val principal = LocalPrincipal.current
    when (val access = route.access(principal, pathParams)) {
        Allow       -> content()
        NotFound    -> NotFoundPage()
        is Redirect -> LaunchedEffect(access.to) { navigate(access.to) }
    }
}
```

`principal.isAdmin` is a cell. An admin revoking a role writes it, which invalidates `Guarded`, which
re-evaluates to `Redirect`, which moves that user off the page they are sitting on. No polling, no
logout broadcast. Same for entity-bound routes: unshare a project and anyone holding its todo open
lands on not-found.

**Navigation derives from the same guard.** Hiding a link and blocking a route are the same fact, and
two copies of it will drift.

```kotlin
IfPermitted("/admin/users") { NavLink("/admin/users") { Text("Users") } }
```

**Three entry paths, tested separately.**

- **Deep link.** Guard runs before the server-side render; a redirect is a 302.
- **In-session navigation.** No page load; the guard runs in the composition and redirects
  client-side.
- **Hibernation wake.** `attributes { }` re-runs, so the principal is recomputed from the arriving
  connection — but the session resumes on whatever URL it was on, so **the current route's guard must
  be re-evaluated on wake, not only on entry.** Otherwise a user whose role was revoked while
  hibernated resumes on `/admin/users`. This is the case most likely to be missed.

**Guards are not the security boundary.** The record's policy is. A guard is UX plus a cheap early exit; it
stops you rendering a page that would have been empty. If a route guard is ever the only thing
protecting data, one forgotten guard is a leak and the design has collapsed back into 2.1 candidate
A. Keep the ordering: `find` gated, traversal gated, `update` gated, guards on top — never instead.
Say so in the module KDoc, because "the route is protected" is a very tempting reason to skip the
policy.

---

## 5. Known limitations — do not treat as bugs

### 5.1 A leaked reference is authority
Chosen knowingly. See 2.4 and 4.8.

### 5.2 Memory is the ceiling
The working set is resident and shares a budget with live session compositions, which
`samples/demo:benchmark` already measures. Extend that benchmark to report graph size alongside
session size (phase 5) so the cliff is visible before it is hit.

Section 11.3 softens this considerably: an async cell renders a placeholder instead of blocking, so
cold tables can move off the resident graph without changing the model. Residency is an optimization,
not a foundation. Do not use async cells for the database in v1 — a microsecond SQLite read should
not produce a placeholder flicker — but know the exit exists.

### 5.3 Transitive visibility on write is not caught
Moving a project to a different team changes who can read its todos, but only `Project`'s policy is
consulted. Catching it would require policies to declare their visibility dependencies and the write
path to fan out. **Out of scope for v1.** Document it in the module KDoc.

### 5.4 No defense in depth
SQLite has no row-level security, so the framework is the only enforcement layer. Arbitrary Kotlin
policies do not translate to Postgres RLS, so this will not port automatically if an app later needs
a second layer.

### 5.5 Cross-session write conflicts
Two sessions writing the same entity can conflict on apply, and a plain `mutableStateOf` rejects the
losing apply. Decide per column: last-write-wins via a merge policy, or surface the conflict. Phase 4.

### 5.6 Ad-hoc queries are linear scans
No indexes in v1. Acceptable at target scale; revisit only with a measurement.

---

## 6. Implementation phases

Each phase ends with `./gradlew build` green and the stated capability demonstrable.

### Phase 1 — Records and columns, no persistence

Create `:jetlin-db`. No SQLite, no policies, no KSP.

- `Record` base class: identity, `id`, equality and `hashCode` by identity.
- `column(initial)` property delegate over `mutableStateOf`.
- `reference()` delegate.
- `IdentityMap`: registry of live records by type and id.
- `View<T>`: a lazy `List<T>` over the identity map, unfiltered for now.

**Acceptance:** a test composes a view reading `record.field`, writes the field from outside the
composition, and asserts exactly one op is produced. Reuse the harness style in
`jetlin-html/src/test/.../HtmlApplierTest.kt` — assert exact op lists, not `contains`.

This phase proves the reactivity substrate before anything is persisted, which is the right order:
if this does not work, nothing else matters.

### Phase 2 — SQLite persistence and the snapshot transaction

- SQLite connection with the pragmas in 4.9, exclusive lock, `data_version` check.
- `Write` recording via `Snapshot.takeMutableSnapshot(writeObserver = ...)`.
- `Db.transact` exactly as in 4.6.
- Load-at-boot: read tables into the identity map, resolve references.
- Hand-written schema for now; KSP arrives in phase 3.

**Acceptance:**
1. A write inside `transact` is durable across a process restart.
2. A `transact` whose body throws leaves the database unchanged **and produces no ops** — assert
   both. This is the property from 2.3 and it is the one most likely to be lost later.
3. Two `LiveView`s over one identity map both recompose on a single write.

### Phase 3 — KSP: schema, Draft types, column objects

Create `:jetlin-db-ksp`.

- Read `@Entity`, `@Owner`, and the column and reference delegates.
- Generate: a schema descriptor, a `Todos`-style column object, and a `Draft` type per entity.
- **Fail the build on an `@Entity` with no policy.**
- Emit the schema snapshot file consumed by phase 4.

Check the Kotlin version for context parameters here (4.4) and record the answer in section 11.

**Acceptance:** an entity with a typo in a column name fails to compile; an entity with no policy
fails to compile with a message naming the entity.

### Phase 4 — Policies and gating

- `Policy` interface and the `owned()` shorthand.
- Gate `View`, `find`, and relation traversal on `canRead`.
- `update { }` with a `context(principal)` parameter and column-level `canWrite`.
- `add` / `delete` on `canCreate` / `canDelete`.
- The `unsafe` escape hatch, logged.
- The leak detector (4.8 item 6) behind a build flag.
- Decide and implement the conflict policy (5.5).
- Route guards (4.11): `Access`, `requires`, entity-bound `subject`, `Guarded`, `IfPermitted`,
  guard re-evaluation on hibernation wake.
- `setPrincipal` in `:jetlin-testing`, so guards and policies can be driven headlessly.

**Acceptance:**
1. Policies are pure functions and test with no database at all:
   `assertFalse(Todo.canWrite(todo, Todos.archived, teammate))`.
2. A `update { }` call with no principal in scope does not compile — assert with a compile-testing
   fixture or, failing that, a Konsist rule.
3. **Reactive revocation**: a session reading a team-shared record loses it when an admin writes
   `user.teams`, with no invalidation code. This is 4.7 and it will be quietly broken by any caching
   added later, so it needs a test now.
4. The leak detector fires when a record acquired by one principal is read under another.
5. An entity-bound route for a record the principal may not read renders not-found, and **the page title
   does not disclose the record**. Test the title explicitly; it is rendered before the body.
6. Guards resolve identically by deep link, by in-session navigation, and on hibernation wake. The
   wake case gets its own test: revoke a role while the session is hibernated, wake it, assert the
   redirect.
7. Revoking a role evicts an open page with no invalidation code:
   ```kotlin
   @Test
   fun `losing admin evicts an open admin page`(): Unit = runViewTest(url = "/admin/users") {
       setPrincipal(root)
       setRoutes(appRoutes)
       onNode(hasTestTag("user-list")).assertExists()

       transact { root.update { isAdmin = false } }

       assertRedirectedTo("/")
   }
   ```

### Phase 5 — Migrations, tooling, benchmark

- `:jetlin-db-gradle` with `dbDiff`, `dbMigrate`, `dbVerify`.
- SQLite 12-step rebuild generation, including index, trigger and view preservation.
- Destructive-change acknowledgement.
- Startup schema verification.
- Extend `samples/demo:benchmark` to report graph size beside session size (5.2).

**Acceptance:** adding a column, dropping a column, changing a column's type and adding a foreign key
each produce a correct migration, and the resulting database round-trips the entity. The type change
and the foreign key are the ones that exercise the rebuild path; test them explicitly.

### Phase 6 — Samples and documentation

- `:samples:teams`: a multi-user sample exercising all three access shapes from 4.3, with two logins
  and a visible team share. This is the readable proof the design works.
- Port `:samples:demo` to `jetlin-db` **last**, once everything else is green. Its 36 Playwright
  tests and 16 application tests are the regression net; keep `TodoStore`'s observable behaviour
  identical so they pass unchanged. If `Main.kt` needs edits beyond the store, something in the
  design is wrong — stop and record it in section 11.
- `docs/db.md` in the style of `docs/architecture.md`: what it does, why, and what is missing.
- Extend `:jetlin-testing` with an assertion that a principal's page contains nothing derived from
  another principal's records. The op-recording machinery already knows which nodes changed, so this is
  mostly wiring.

---

## 7. Repository conventions

- `explicitApi()` is on for every `jetlin-` module. State visibility and return types.
- **Every test function with an expression body must declare `: Unit`.** JUnit silently does not
  discover ones that do not, and `:conventions` fails the build on it. See
  `conventions/src/test/kotlin/jetlin/conventions/TestConventionsTest.kt` for why this rule exists.
- Framework tests assert exact op lists, not `contains`. An update touching more of the page than it
  needs to must fail.
- `:jetlin-testing` depends on no test framework; its assertions throw `AssertionError` directly.
  Anything added there follows suit.
- Comments explain *why*, at the point the reasoning is non-obvious. The existing code is the style
  guide — read `Session.kt` and `GlobalSnapshotManager.kt`. Do not narrate what the code does.
- New repo-wide rules go in `:conventions` as Konsist tests, not in review checklists.

---

## 8. New conventions this framework needs

Add to `:conventions` as they become checkable:

1. No public function in `:jetlin-db` returns a `Record` without a principal in scope, except
   `authenticate`.
2. Policy implementations call no suspend function and perform no IO.
3. Every `@Entity` has a companion implementing `Policy` — enforce in KSP if Konsist cannot see it.

---

## 9. (empty)

Section intentionally empty.

---

## 10. Open questions

- **Kotlin version.** Context parameters are stable from 2.4. Confirm against
  `gradle/libs.versions.toml` and record below.
- **Conflict policy** (5.5): last-write-wins per column, or surfaced? Decide in phase 4.
- **Where the principal `CompositionLocal` lives.** A `:jetlin-db-html` module, or an interface in
  `:jetlin-db` implemented by the application? Prefer the latter if it keeps `:jetlin-db` free of a
  `:jetlin-html` dependency.
- **Whether `:samples:demo` gains authentication** when ported, or keeps a single implicit principal.
  A single principal keeps the Playwright suite unchanged and is probably right; `:samples:teams` is
  where multi-user behaviour is shown.

---

## 11. External systems

**Status:** designed, deferred. Do not start before phase 4 is green. See 11.7 for why.

### 11.1 The split

Extend the core, keep the adapters separate. The line runs along the write path.

The read side and the authorization model generalise cleanly to external APIs. The write side does
not, for one reason: `snapshot.dispose()` cannot un-send a POST. Commit-before-apply (2.3) exists
only because SQLite lets you fail before anything becomes visible, and an external system gives no
such window. Putting both behind one write API would force the database to surrender its best
property to match the weaker one.

```
:jetlin-data     identity, cells, Policy, gating, leak detector, View
:jetlin-db       + residency, snapshot transactions, migrations
:jetlin-remote   + async cells, commands, staleness, credentials
```

### 11.2 What is shared

Sections 4.2, 4.3, 4.5 and 4.8 move to `:jetlin-data` essentially unchanged. None of it is actually
about databases:

- `Record`, the identity map, `Id<T>`. A GitHub repo has an identity as much as a row does.
- The `column()` delegate — snapshot state with a name, indifferent to where the value came from.
- `Policy<T, P>`. Not one line is database-specific. `canRead(repo, principal)` is the same question as
  `canRead(todo, principal)`.
- Acquisition gating and `context(principal)`, with the same compile-time guarantee.
- `View<T>` and policy-filtered collections.
- The leak detector, which matters more here, since external data is more often the sensitive kind.
- Reactive revocation (4.7), which works identically and for the same reason.

Only 4.6, 4.9 and 4.10 — transactions, residency, migrations — are database-shaped. The payoff is
that an application reasons about access in one place regardless of where the data lives.

### 11.3 Lazy loading is a cell that starts absent

No new machinery. A value not yet fetched is snapshot state holding `Loading`; a coroutine fills it;
`GlobalSnapshotManager` already pumps writes made outside a composition. Reading subscribes, arrival
recomposes.

```kotlin
@Composable
fun RepoCard(repo: Repo) {
    when (val stars = repo.stars) {
        is Loading -> Span { Text("…") }
        is Failed  -> Span { Text("unavailable") }
        is Ready   -> Span { Text("${stars.value}") }
    }
}
```

The read itself triggers the fetch: first read schedules it, subsequent reads join the in-flight one.
Composition never blocks, which is the constraint that ruled lazy loading out for the database in
2.2.

### 11.4 Writes are commands, not assignments

There is no dirty tracking here. A property assignment cannot represent "charge this card" — it has
arguments, it can partially succeed, and it cannot be batched into a snapshot apply.

```kotlin
context(principal: User)
suspend fun Repo.addLabel(name: String): Label
```

jetlin's event handlers are synchronous (`transact` takes `() -> T`), so a command cannot be awaited
in `onClick`. It must be launched, which forces in-flight state to be modelled — which is wanted
anyway, for the disabled button and the error message.

```kotlin
val addLabel = rememberAction { name: String -> repo.addLabel(name) }

Button({
    disabled(addLabel.running)
    onClick { addLabel(draft.value) }
}) { Text("Add") }
addLabel.error?.let { P({ classes("error") }) { Text(it.message) } }
```

Default to pessimistic: nothing changes until the call returns. Optimistic updates are an opt-in with
an explicit revert, because they reintroduce exactly the flicker-on-rejection that 2.3 avoids.

Note what falls out for free: because `transact` takes a non-suspending lambda, **an external call
cannot be awaited inside a database transaction.** That is the correct rule and the compiler already
enforces it. Where "write the record and call the API" must be atomic, use an outbox — record the intent
in the transaction, let a worker perform the call and reconcile.

### 11.5 Credentials — support both, default to the application token

Two modes, and the adapter interface must accommodate both from the start because the caching key
depends on which is in use:

- **Application token.** The external system sees one identity and authorizes nothing on your behalf.
  `Policy` is the only thing between a user and every other user's data in that system. This is the
  default and the v1 target.
- **User token,** via OAuth. The external system enforces independently, giving genuine defense in
  depth — the one thing SQLite cannot provide (5.4). Prefer it where the API supports it.

**The hazard, which has no database equivalent:** the identity map is process-wide. If
`Repo("acme/private")` is fetched with Alice's token and cached under `(Repo, "acme/private")`, Bob
reads Alice's authority. The policy check passes, because the object is in the map and Bob's policy
knows about the resource, not about how it was obtained.

The identity map key therefore needs a third dimension:

```kotlin
public data class CellKey(val type: KClass<*>, val id: Any, val authority: AuthorityId)
```

Application-credentialed sources share one authority and cache once. User-credentialed sources cache
per user, costing memory and losing sharing. **This is not optional and it is the single most
important thing to get right in `:jetlin-remote`.** It is also the sort of bug that is invisible in
every test written by a single user, so the test for it must involve two.

Defaulting to the application token does not remove the requirement: the key carries an authority
either way, and the shared-authority case is simply the degenerate one. Building it in now costs a
field; retrofitting it later means auditing every cached read.

### 11.6 Staleness

The database does not go stale, because the process owns the file (4.9). External data goes stale
constantly, so `:jetlin-remote` needs what `:jetlin-db` does not: a TTL per cell, revalidation on
read, invalidation on command completion, and a decision about serving stale data while
revalidating.

This is the problem SWR and React Query solve; read them for the taxonomy. The difference is that the
substrate is snapshot state rather than a store with subscriptions, so the delivery half is already
done — a revalidation that writes a cell recomposes exactly the readers of that cell, across every
session, with no subscription bookkeeping.

### 11.7 When, and what to do now

Do phases 1–4 first, then extract `:jetlin-data` from what exists while writing the first adapter.
Extracting a core from one implementation is guesswork; extracting it from one working implementation
while writing the second is not.

The only thing to do now is keep the boundary honest (4.1). That is cheap insurance and it decides
whether the extraction is a file move or a redesign.

### 11.8 Acceptance criteria, when this phase runs

1. Two users reading the same user-credentialed resource get separate cells; neither sees the other's
   data. Written with two principals, because one principal cannot fail this test.
2. A read of an unfetched cell renders a placeholder without blocking the session thread, and the
   arrival produces exactly one op.
3. A failed command leaves no state changed and surfaces on the action, not as a session-killing
   throw.
4. An external call inside `transact` does not compile.
5. `:jetlin-data` has no dependency on `:jetlin-db`, asserted in `:conventions`.

---

## 12. Documentation

The repository documents itself unusually thoroughly, and that is a property worth preserving rather
than a chore to catch up on at the end. Existing documents: `README.md`, `docs/architecture.md`,
`docs/comparison.md`, `ci/README.md`, plus KDoc carrying most of the reasoning.

### 12.1 House style

Read `docs/architecture.md` before writing any of this. The conventions that matter:

- **Explain why, not what.** The architecture doc explains why text nodes need markers, not that
  they have them. Every design decision that looks arbitrary gets its reason recorded next to it.
- **Cite the evidence.** "Break `key(todo.id)` and fifteen of sixteen tests still pass" is worth more
  than a paragraph asserting the test matters. Prefer a number or a reproducible observation.
- **Say what is missing, in the order it would stop you shipping.** §13 of the architecture doc is
  the model. A limitation section that is honest is more useful than a feature list that is not.
- **Numbered sections, `---` between them, code examples that would actually compile.**

### 12.2 What to write

**`docs/db.md`** — new, in the style of `docs/architecture.md`. This is the main deliverable. It is
not this plan reformatted: this plan is a work order and should be deleted or archived once the work
is done. `docs/db.md` describes what exists. It should cover:

1. How it works — the identity map, cells, the snapshot-as-transaction, one diagram.
2. Entities and policies, with the three access shapes from 4.3.
3. Getting a principal, and route protection (4.11).
4. Reading, writing, and why `update { }` rather than assignment.
5. Reactive authorization (4.7) — this is the framework's most distinctive property and it deserves
   its own section with a worked example.
6. Storage, residency, and the memory cliff (5.2) with real numbers from the benchmark.
7. Migrations.
8. **What is missing**, carrying over 5.1 through 5.6 verbatim in spirit: a leaked reference is
   authority, transitive visibility is not caught, no defense in depth, no indexes.

Carry the *reasoning* from sections 2 and 5 of this plan into it. Those are the parts a future
reader most needs and would otherwise have to reconstruct.

**`README.md`** — four edits:
- Add `jetlin-db`, `jetlin-db-ksp`, `jetlin-db-gradle` to the Modules table.
- Add a short data section after "Testing your own views", in the same show-don't-tell register the
  rest of the README uses. The `todo.update { done = !done }` example plus the reactive-revocation
  example is enough; link to `docs/db.md` for the rest.
- Update the Status section: it currently points at `docs/architecture.md` §13 for what is missing,
  and should point at `docs/db.md` too.
- Update the Try it section if `:samples:teams` is worth running.

**`docs/architecture.md` §13** — the current text says a coroutine writing state could be "a database
subscription", which was aspirational and is now real. More importantly, §13's "Would stop a real
application" list should lose whatever this framework now covers, and the "Would stop it scaling past
one machine" entry needs a new bullet: **the resident graph is per-process, so a second node would
hold a second copy and they would diverge.** That is a harder blocker for multi-node than
`SessionStore` and it should be recorded next to it.

**`docs/comparison.md`** — Phoenix has Ecto, Rails has Active Record, Blazor has EF Core. The
comparison should say where this sits against them: no query language, policies as functions rather
than as scopes, reactive revocation as something none of them do, and no defense in depth as
something all of them have.

**KDoc** — the module-level KDoc on `:jetlin-db` carries the load-bearing warnings, because it is
what an IDE shows and what an agent reads first:
- Guards are not the security boundary (4.11).
- A leaked reference is authority (2.4).
- Policies sit on the recomposition hot path and must be pure and cheap (4.7).
- Transitive visibility on write is not caught (5.3).

**`ci/github-actions.yml`** — add `dbVerify` to the build job so entities and the schema snapshot
cannot drift, in the same spirit as the existing check that `jetlin.js` is not stale.

### 12.3 When

Documentation is written **in the phase that creates the thing**, not batched into phase 6. Phase 6
covers the samples, the README edits and the final pass over `docs/db.md`, but a phase that adds a
concept and leaves it undocumented is not finished. The KDoc warnings in particular belong in phase
4, alongside the code they warn about.

If `docs/db.md` and the implementation disagree at any point, the implementation is wrong or the doc
is stale, and either way it goes in section 13 before it goes anywhere else.

---

## 13. Decision log

Append here as work proceeds. Date, decision, reasoning. If this plan turns out to be wrong about
something, that goes here rather than being silently worked around — the next session will have only
this file.

| Date | Decision | Reasoning |
|---|---|---|
| 2026-09-12 | **Kotlin is 2.4.10, so context parameters are available.** Closes the §10 open question; no fallback to an explicit `principal` parameter is needed. | `gradle/libs.versions.toml`. For the record, the rest of the toolchain: Compose runtime 1.12.0, Gradle 9.7.1, JVM toolchain 24. |
| 2026-09-12 | **The phase gate is `./gradlew check`, not `./gradlew build`.** | `:samples:demo:distTar` fails on a clean tree, before any of this work: `org.jetbrains.compose.runtime:runtime-desktop` and `androidx.compose.runtime:runtime-desktop` both produce a file called `runtime-desktop-1.12.0.jar`, and the application plugin's distribution puts both in one flat `lib/`. Arrived with the Compose 1.12.0 upgrade (17cefdb). Not fixed here because §4.1 says leave `:samples:demo` alone until phase 6, and a dedup in its build script is a sample-packaging concern rather than part of this framework. `check` runs every test in every module, which is what a phase gate is actually for. Fix it in phase 6, either with `duplicatesStrategy` or by depending on `androidx.compose.runtime:runtime` directly. |
| 2026-09-12 | Phase 1: **record ids are allocated at construction, from a process-wide sequence per concrete class** — not at insert, and not per database. | A record has to be usable as a `key` before it is stored, so an id that only exists after an insert is no good. Per process rather than per database because §4.9 already says the process owns the file exclusively; loading advances the sequence past the highest id on disk, so ids cannot collide across a restart. A test that opens several databases shares the sequence, which only means ids skip. |
| 2026-09-12 | Phase 1: **`IdentityMap`'s record-returning members are `internal`.** | §4.8 item 1 says no ungated `get(id)` is reachable from application code. Making that true from the first commit is free; retrofitting it after a public ungated `find` has shipped is not. The public, policy-gated surface lands in phase 4 on top of these members. |
| 2026-09-12 | Phase 1: **the write-recording hook is deferred to phase 2**, where `transact` gives it a consumer. | §4.2 lists it as `Record`'s responsibility, but a hook with nothing attached is unreachable code that the next reader has to guess the purpose of. Phases are supposed to be self-contained; this one is listed in the wrong phase rather than wrong. |
| 2026-09-12 | Phase 2: **`transact` is not `suspend`.** §4.6's sketch declares `suspend fun <T> Db.transact(block: () -> T): T`. | A transaction has to be callable from an event handler, and a handler is `() -> Unit` — `onClick { todo.update { … } }` in §4.6 cannot await anything. It also suspends nowhere: the JDBC driver is blocking and the snapshot work is synchronous. Keeping the block non-suspending is separately load-bearing for §11.4 (an external call inside a transaction fails to compile). |
| 2026-09-12 | Phase 2: **writes are recorded by the cell, not by `Snapshot.takeMutableSnapshot(writeObserver = …)`** as §4.6 sketches. | The observer is handed a `StateObject`, and there is no public way back from one to the cell or record that owns it — it would need a process-wide identity side-table mapping state objects to cells, kept in step as records are deleted. A cell already knows both its record and its name, so recording at the setter is less machinery *and* more precise: the flush updates exactly the columns that changed rather than every column of a dirty record. The plan's actual claim — dirty tracking for free, no persistence context, no dirty-check pass — is what survives, and it does. |
| 2026-09-12 | Phase 2: **writing stored state with no transaction open throws** rather than silently not persisting. | It is the same guarantee from the other side: if a field can change in memory without reaching disk, the divergence is invisible until a restart. A record that is not stored yet is exempt, because its insert carries whatever its fields end up holding. Phase 4's `update { }` opens a transaction when none is open, so application code never meets this error by accident. |
| 2026-09-12 | Phase 2: **no `PRAGMA locking_mode=EXCLUSIVE`; an outside writer is detected with `PRAGMA data_version` instead.** | §4.9 asks for both an exclusive lock and Litestream, and those are mutually exclusive: in WAL mode an exclusive lock shuts out readers too, so Litestream could not read the file it is supposed to be backing up. `data_version` is unchanged by our own commits and bumped by anyone else's, so checking it before each flush detects an outside writer one commit late but loudly, and leaves readers free. A detection that is one commit late is worth more than a backup story that does not work. |
| 2026-09-12 | Phase 2: **tables load in declaration order and a forward reference fails loudly**, naming both tables. | Resolving references against what is already resident needs no deferral pass and no placeholder objects, and the error tells you exactly what to reorder. The cost is that a reference cycle (`User.team` / `Team.owner`) cannot be loaded in v1. Worth revisiting when something needs it; a fixup pass over nullable references is the obvious way and does not change the model. |
| 2026-09-12 | Phase 2: **a snapshot conflict on `apply()` after a successful commit is a known hole**, left for phase 4 (§5.5). | Commit-before-apply makes a database rejection invisible, but it cannot make a *snapshot* rejection invisible: if two sessions write the same cell, the loser's commit has already happened when `apply().check()` throws. Needs the conflict policy decision, so it belongs with phase 4 rather than being half-solved here. |
| 2026-09-12 | Phase 3: **KSP 2.3.12 works with Kotlin 2.4.10.** | KSP has moved to its own version line (it is no longer `<kotlin>-<ksp>`), so there is no "waiting for a KSP built against this Kotlin" problem any more. The processor module depends only on `symbol-processing-api`. |
| 2026-09-12 | Phase 3: **`Policy` and `Principal` are declared in phase 3, not phase 4.** | Phase 3 is required to fail the build on an `@Entity` with no policy, which it cannot do before the type exists. Only the declaration moves; `owned()`, the gating and the draft's column checks stay in phase 4. |
| 2026-09-12 | Phase 3: **a column is a delegated property or a primary-constructor property, and nothing else.** A plain `var` is a build error; a plain `val` with a backing field is a warning. | KSP cannot see a delegate *expression*, only that a property is delegated — so `column()` versus `reference()` is decided by the property's type, not by which function was called, and a property with no delegate cannot be a cell. The `var` case is an error rather than a warning because a plain `var` on an entity reaches the screen and never the database: nothing records it, so the loss is invisible until a restart. |
| 2026-09-12 | Phase 3: **load order is generated, not declared.** The schema object lists tables in topological order of their non-null references; a cycle is a build error naming both entities. | Phase 2 made load order the application's problem (§4.9 ordering), which is a footgun with no upside now that something knows the reference graph. The cycle limitation from the phase 2 entry stands, but it is now caught at compile time instead of at boot. |
| 2026-09-12 | Phase 3: **the schema snapshot is written to generated resources (`jetlin-db-schema.json`), not into the source tree.** | §4.10 wants a snapshot checked into the repo, and that is still the plan — but KSP writing files a human is expected to review is a bad shape (it races the IDE, and a "generated" file in review looks editable). Phase 5's `dbDiff`/`dbVerify` compare this generated file against the checked-in copy, which is also exactly what `dbVerify` has to do in CI anyway. |
| 2026-09-12 | Phase 3: **negative compilation is not asserted end to end.** The rules are unit-tested against the processor's model instead, message wording included. | There is no compile-testing dependency in the repo, and adding one (kotlin-compile-testing with a KSP2 runner) is a significant dependency for the value. What matters about "no policy fails the build" is the rule and its wording, and those are tested directly; that `logger.error` fails a build is KSP's contract, not this processor's. Worth revisiting in phase 5 if the Gradle tooling ends up needing a compile harness anyway. |
| 2026-09-12 | Phase 3: **the generated draft writes straight through; the column-level policy check lands in its setters in phase 4.** | The draft exists *because* of the column check, so the type is only half a type until phase 4 — but generating it here is what proves the shape works, and phase 4 extends one emitter function rather than inventing the type. |
| 2026-09-12 | Phase 4: **the conflict policy is last-write-wins per cell, and transactions are serialized.** Closes the §5.5 / §10 question. | Two halves of one answer. Every cell uses a merge policy that takes the applying value, so a concurrent write resolves instead of throwing — which matters because commit precedes apply, so a *rejected apply* would leave disk ahead of memory with the commit already done. And `transact` holds a per-database lock for the whole block, which it has to anyway: one JDBC connection cannot carry two transactions, and `autoCommit` is connection-wide state. Serializing writes also makes commit order and apply order the same order, which is what makes last-write-wins equal to what is on disk rather than merely a preference. Reads are not serialized and never block. |
| 2026-09-12 | Phase 4: **a refused column fails the whole `update { }`** rather than being skipped. | §4.6 says "`archived = true` can be denied while `title = \"x\"` in the same block succeeds", which reads as per-column partial application. What is true is that the *check* is per column. Silently skipping a refused write is the failure mode this design exists to avoid — a field that changed on screen and not on disk — so a refusal throws and unwinds the transaction, and nothing is committed or applied. |
| 2026-09-12 | Phase 4: **the leak detector needs an ambient principal, and only checks reads where one is installed.** | §4.8 item 6 says every read asserts the ambient principal still matches. A record records the *set* of principals that obtained it through the gate (a single principal would false-positive on every legitimately shared record) and a cell read checks the thread's current principal against that set, with the acquisition stack attached as the exception's cause. A read with no ambient principal cannot be checked, because nothing knows whose read it is — so this catches leaks in tests and wherever a session installs the ambient, not universally. `System.getProperty("jetlin.db.leakDetector")` turns it on; `:jetlin-db`'s test task sets it. |
| 2026-09-12 | Phase 4: **generated code is the gate's only caller.** `Gate`, `databaseOf` and the draft constructors are public so that generated accessors in an application's own module can reach them. | A Konsist rule holds the line that matters — nothing public returns a record without a principal in its signature — and names its two exceptions (`Row.reference`, `Row.referenceOrNull`, the boot loader, where no principal exists yet). §4.4's `authenticate` is not needed as a special case: loading is the privileged root, and a `User` comes out of the same gated lookup as anything else once `attributes { }` has one. |
| 2026-09-12 | Phase 4: **`View` gained `add`, and inverse relations are generated as extension properties** (`project.tasks`), not as a `hasMany()` delegate as §4.2 sketches. | A delegate is a property getter, and a property getter cannot take the principal — it could only read an ambient one, which is the runtime check §4.6 rejects for assignment, for the same reason. A generated extension property with a context parameter keeps the compile-time guarantee and reads identically at the call site. `View.add` is the one record-returning member with no principal in its signature: the view was built by the gate and carries it, which the Konsist rule's wording accounts for deliberately. |
| 2026-09-12 | Phase 4: **route guards live in `:jetlin-html`, not in a `:jetlin-db-html` module.** Closes that §10 question. | A guard is a pure function of the request and whatever `attributes { }` attached to it, so it needs no database concepts at all — `Principals(PrincipalKey)` is generic over the application's principal type. `:jetlin-db` therefore stays headless, and the guards work for an application whose principal is not a record. |
| 2026-09-12 | Phase 4: **a route's document title comes from the composition**, through a `TitleSink` the view installs. | §4.11 requires that a title cannot disclose a record the body refused to show, and the title is rendered before the body. The route table cannot know the title of an entity-bound route, and resolving the subject twice (once for `<head>`, once for the body) is two chances to disagree. So `Subject` sets the title after resolving, and the page render reads what the composition asked for. **Known gap:** an in-session navigation to an entity-bound route still carries the route table's static title, because the title travels in `ServerMessage.Navigate` and changing the protocol would mean rebuilding `jetlin.js`. The page-load and wake paths are correct; the in-session one shows the fallback. |
| 2026-09-12 | Phase 4: **a failed guard is a 404 over HTTP, and a redirect is a 302 decided before a session exists.** | §4.11's "default to invisibility" applies to the status code as much as to the page. Answering the redirect at the HTTP layer also avoids composing a whole session for someone who is about to be sent elsewhere; the same guard then runs again inside the composition, which is what makes revocation and hibernation work. |
| 2026-09-12 | Phase 5: **`./gradlew dbDiff --name=share_todos_by_team`**, not the positional argument §4.10 writes. | Gradle tasks take options, not positional arguments. The name is also optional: without one the migration is named after the first change it contains, which is better than refusing to generate. |
| 2026-09-12 | Phase 5: **index, trigger and view preservation is enforced by the runner, not generated into the SQL.** | §4.10 asks the generator to emit it, but the generator only has two schema files — it cannot see what a particular database has on it, and a deployment's hand-added index is exactly what would be lost. So `dbMigrate` records every index, trigger and view before applying a migration, checks they still exist afterwards, and rolls back naming what vanished, with the fix (add its `CREATE` to the migration). That turns the known trap — a view over a rebuilt table — into a failed migration instead of a missing view nobody notices. It also runs `PRAGMA foreign_key_check` before committing, which catches the rows a new foreign key would have orphaned. |
| 2026-09-12 | Phase 5: **a destructive migration carries a marker line that has to be deleted** before `dbMigrate` will run it. | §4.10's "explicit acknowledgement in the migration file". A marker in the file is reviewable forever and shows up in the diff; a `--force` flag is invisible the moment it has been typed. |
| 2026-09-12 | Phase 5: **adding a *required* reference to an existing table is a rebuild, not an `ALTER`.** | SQLite will only add a column with a foreign key when its default is NULL, so a required one cannot arrive in place. Adding the constraint to a column that already exists is a rebuild for the same reason a type change is. Both are covered by one code path and one acceptance test each. |
| 2026-09-12 | Phase 5: **startup verification compares the declared tables against `PRAGMA table_info` and `foreign_key_list`**, and treats a stored column no entity declares as a failure too. | §4.10 only asks for a refusal on mismatch. An extra column is worth refusing on as well: it means either a half-applied migration or a field deleted from an entity without one, and both want a decision rather than a default. One SQLite quirk had to be taught to both the runtime and the tests: an `INTEGER PRIMARY KEY` reports as nullable, because it is the rowid alias and NULL there means "assign one". |
| 2026-09-12 | Phase 5: **the acceptance criterion "the resulting database round-trips the entity" is met in two halves**, because the entities and the migration engine are in different modules. | The plugin's tests assert that a migrated database's schema equals what the new snapshot declares and that the rows survived; `:jetlin-db`'s tests assert that `Db.open` accepts a matching schema, loads the entity, and refuses every shape of mismatch. Together that is the round trip. Putting them in one test would mean either KSP inside the Gradle module or the tooling inside the runtime. |
| 2026-09-12 | Phase 5: **the benchmark's graph-size reporting moves to phase 6.** | It needs a resident graph to measure, which needs `:samples:demo` ported — and §4.1 says not to touch the demo before phase 6. Measuring an empty graph would produce a number with nothing in it. |
| 2026-09-12 | Phase 5: **the plugin hooks `dbVerify` into `check`** in whatever module applies it. | Same spirit as the existing check that `jetlin.js` is not stale, and it means `ci/github-actions.yml` needs no new step: the existing `./gradlew build` picks it up once a module applies the plugin. |
| 2026-09-12 | Phase 6: **`:samples:demo` is not ported, and the reason is the plan's own stop condition.** *(Superseded the same day by the port, which was then reverted on 2026-09-13 — so the demo ends up where this row left it, though not for the reasons it gives. The analysis was right about what the design requires and wrong about what it costs.)* | §6 says that if `Main.kt` needs edits beyond the store, something in the design is wrong and the work should stop here. It does: every page that writes needs a principal in lexical scope (`update { }` takes it as a context parameter), so each of the demo's five views would need a `context(principal:)` signature and a `WithPrincipal` wrapper, plus a synthetic principal for an application that has no users. That is not a bug in the design — the compile-time guarantee is the feature — but it *is* more than the store, and the plan asked to be told. Two further reasons not to force it: the demo would need a `position` column to keep its move-up/move-down behaviour, since a `View` has no ordering of its own beyond insertion; and the regression net the port was supposed to run against cannot run in this environment (see the next row). `:samples:teams` is the readable proof instead, with 12 application tests that run two principals against one database. |
| 2026-09-12 | Phase 6: **the Playwright suite could not be run at first.** Chromium installs but cannot start without `libnspr4.so`, which needs root. *(Resolved: the dependency was installed and the suite now runs — 35/36, unchanged from before this work.)* | All 36 failures are the browser failing to launch, not the application. The server-side half was checked by hand instead — every demo route still renders with the right `<title>`, including through the new title path — and the 16 application tests, the 36-test suite's headless counterpart, pass. Anyone picking this up with a working browser should run `cd e2e && npx playwright test` against `:samples:demo:run` before trusting the `:jetlin-html` and `:jetlin-server-ktor` changes this work made. |
| 2026-09-12 | Phase 6: **`./gradlew build` is green again**, so the phase gate recorded at the top of this log is back to what the plan assumed. | The duplicate `runtime-desktop-1.12.0.jar` came from depending on `org.jetbrains.compose.runtime:runtime`, a redirect that resolves to its own `runtime-desktop` artifact *and* androidx's. Depending on `androidx.compose.runtime:runtime` directly gives one artifact, one chain and no shadowing — better than a `duplicatesStrategy` that would have left two same-named jars in a flat `lib/`. Every test still passes. |
| 2026-09-12 | Phase 6: **`:jetlin-db-gradle` is an included build, not a subproject.** | A project cannot apply a plugin built by a sibling subproject — the plugin has to be on the build's own classpath first — and the point of phase 6 was for `:samples:teams` to apply the real plugin rather than a copy of what it does. The cost is that the root build's lifecycle tasks do not reach it, so the root project registers `build` and `check` tasks that depend on the included build's, and those two commands still cover everything. |
| 2026-09-12 | Phase 6: **a second privileged root, `insertUnchecked`, refuses to run outside `unsafe { }`.** | §4.4 wants exactly one privileged entry point and §4.8 wants exactly one escape hatch, and seeding needs both at once: the first user in an empty database cannot be created by anybody. Making the ungated insert require `unsafe` — which logs a warning naming its reason every time — keeps it to one hole rather than two, and the `:conventions` test names it alongside `authenticate`. |
| 2026-09-12 | Phase 6: **the benchmark settles the graph number and leaves the session number open.** A resident record costs **1.1 kB**, stable from 20 records to 20,000. *(The session half is settled in a later row: it was the benchmark's own baseline subtraction.)* | That is the figure §5.2 asked for, and it puts the residency cliff in the hundreds of thousands of records rather than the thousands. The session figure is *not* settled: the teams sample's todo page measures 2.3 MB per session against `samples/demo`'s 128 kB for a comparable page. Probing ruled out the gate, the `View` and the policy — a plain `List<Todo>` of five records measures the same 340 kB as the gated one, and an empty session measures 12 kB — so it is something about a keyed list in this measurement rather than anything `jetlin-db` added. It wants a heap profile rather than a heap delta; `docs/db.md` §6 says so rather than quoting a number nobody has explained. |
| 2026-09-12 | Phase 6: **`:jetlin-testing` gained three things** — `setAttribute` (the principal), `title()` and `assertNotDisclosed(…)`. | The first two are what makes a guard testable headlessly, and `setAttribute` doubles as the hibernation-wake case: a session that slept wakes with its attributes recomputed, so changing the principal and waking is exactly what a revoked role looks like. `assertNotDisclosed` is §6's "nothing derived from another principal's records", checked against the rendered markup rather than the node tree, because an attribute or a title discloses as well as text does. |
| 2026-09-12 | Phase 6: **`:samples:demo` is ported after all**, and the stop condition it tripped turned out to be one line of API rather than a design problem. *(Reverted the next day by the owner's decision — see the last rows. The cost figures here stand and are the reason it is still written down.)* | The earlier entry below stands as the reasoning; what changed is that a working browser made the regression net runnable, and the port then cost less than the analysis suggested. All 16 application tests and all 36 browser tests pass *unchanged*. Three things made that possible: the demo's principal is a constant (`object Visitor : Principal` — a principal need not be a record), so the store supplies it and no page signature mentions it; a `position` column replaces the list order a `View` does not have, with `move` swapping positions in one transaction; and seeding can now choose ids, because both test suites hardcode `/todo/1`. The edits outside the store are exactly the three writes that used to be bare assignments — `todo.done = it` and the two in the save handler — which is the design working as intended rather than a concession. |
| 2026-09-12 | Phase 6: **seeding can choose a record's id** (`insertUnchecked(record, id = 1)`), advancing the sequence past it. | The demo's reset has to produce ids 1, 2, 3 every time, because `/todo/1` is in both test suites and in the markup they assert on. An id is part of a fixture often enough — a seeded record something links to by number — that this is worth the one parameter, and it cannot collide: the sequence is advanced past a chosen id, and an id already resident is refused. |
| 2026-09-12 | Phase 6: **the port found a real bug in the write flush.** Inserts ran before deletes, so re-seeding a record under an id that was just deleted failed on the primary key. | Fixed by ordering deletes, then inserts, then updates, and by setting `PRAGMA defer_foreign_keys=ON` for the transaction. That pragma is what makes any single order possible: with foreign keys checked per statement, a transaction that both creates a row and the row pointing at it, and a transaction that points a row away from something it then deletes, want opposite orders. Deferred to commit, neither does. A primary key is *not* deferrable, which is why deletes have to come first. Two tests pin both halves. |
| 2026-09-12 | Phase 6: **the session-memory anomaly is resolved, and it was the teams benchmark's own method.** | Measured in one harness, the demo's synthetic 113-node page costs 130 kB per session and its real 202-node list page costs 332 kB — about 1.6 kB per node, and nothing to do with the database. The teams benchmark's 2.3 MB came from subtracting a baseline it had created and closed, which is not a measurement the demo's harness makes. The teams benchmark now measures only the graph, where it is good: **1.09 kB per record, stable from 2,000 records to 20,000**, and `samples/demo:benchmark` reports a graph figure beside its session figures, which is what §5.2 asked for. A policy-filtered scan does add to a session's read set, about 30 bytes per scanned record — real, and an order of magnitude under the cost of rendering one. |
| 2026-09-12 | **One browser test fails and predates this work.** `markup that cannot be adopted falls back to a full render` — after a deliberately broken adoption, a click no longer updates the page. | Verified by running the suite against `b724ff1`, before any of this work: 35 passed, 1 failed, identically. Left alone because it is a client-side fallback-path bug in `jetlin.js` with nothing to do with persistence, and fixing it means touching the TypeScript and rebuilding the checked-in bundle. Worth its own change. |
| 2026-09-13 | **`:samples:demo` is back on its own in-memory store.** The port is reverted; the framework work it produced is kept. | The owner's call, and a defensible one: a sample whose job is to demonstrate the view layer reads more clearly without a database in it, and keeping one sample on plain `mutableStateOf` keeps it visible that `jetlin-db` is optional rather than assumed. What the port established is the part worth keeping, so it is recorded rather than deleted: it cost one `object Visitor : Principal`, a `position` column, three writes in `Main.kt` that used to be bare assignments, and nothing else — 16 application tests and 36 browser tests passed unchanged. Anyone considering porting an existing application can take that as the estimate. |
| 2026-09-13 | Kept from the port: **the write-flush ordering fix**, `insertUnchecked(record, id = …)`, and the corrected memory figures. | The flush bug was real and is unrelated to which sample found it: any transaction that deleted a row and inserted another under the same id failed on the primary key, and any transaction creating both a row and the row pointing at it depended on the application happening to write them in the right order. Both are fixed and both have tests, and the id-reuse test is what `insertUnchecked`'s `id` parameter exists for. |
| 2026-09-13 | **The benchmarks now measure one thing each**, and the numbers in `docs/db.md` §6 were re-measured after the revert. | `:samples:teams:benchmark` reports the graph — 1.09 kB per record, stable from 2,000 records to 20,000. `:samples:demo:benchmark` reports sessions, with `PAGE=real` for an application's own page rather than the synthetic one: 113 nodes at 129 kB, 42 nodes at 65 kB, which is about 1.5 kB a node either way. §5.2's "report graph size alongside session size" is therefore met across two tools rather than one, because the demo no longer has a graph to report. The earlier 332 kB figure for the demo's real page was measured while it was db-backed and had 20 seeded records on the page; it is not comparable and is no longer quoted. |
| 2026-09-13 | §4.8 item 3 — **"entities cannot enter `rememberSaved`, but assert it"** — cannot be asserted in this module, and the attempt is withdrawn. | The guardrail is real: an entity has no serializer, so `rememberSaved(todo)` does not compile. But that is a fact about an *application's* entities, not about `:jetlin-db` — nothing here prevents someone writing `@Serializable` on one. A test in this module could only have asserted that its own fixture lacks an annotation, which is a test of the fixture. So the reasoning is documented on `Record` instead, including the part the plan does not say — that the objection is identity rather than size, because a record read back out of JSON would be a second object for a row the identity map already has one for, not equal to it, not recomposing its readers and never access-checked — and the assertion belongs to whoever owns the entities. Worth saying in `docs/db.md` if an application ever wants the recipe. |
| 2026-09-13 | §12.2's **"add `dbVerify` to the build job"** is met by wiring rather than by a step in `ci/github-actions.yml`, and that is deliberate. | The plugin hooks `dbVerify` into `check` in whatever module applies it, so `./gradlew build` — which is what the CI job already runs — runs it, and it fails with the drifted columns named. An explicit second invocation in the yml would be a copy of that fact in a file that is not executed locally, and the two would drift the first time the task is renamed or a module is added. `ci/README.md` says where it comes from, which is the part a reader needs. Revisit only if CI ever stops running `build`. |
| 2026-09-13 | **One word for the concept: `principal`, never "viewer".** The rename runs through the code, the generated accessors' context parameter, the route guards, `:samples:teams`, `docs/db.md` and this document's own prose. | This plan used both words for one thing — `Principal` as the interface, "viewer" in every signature, parameter and sentence around it — which reads as though there were two concepts and leaves a reader wondering which one a given sentence is about. `principal` is the word that means something in access control; "viewer" also suggests reading, which is wrong for the thing `canWrite` and `canDelete` are asked about. So: `Policy<T, P>` with `canRead(record, principal)`, `context(principal: User)` in every generated accessor, `CurrentPrincipal`, `WithPrincipal`, `PrincipalKey`, `Principals(…)` for the typed guards, and the Konsist rule's parameter check looking for `P` or `Principal`. Nothing but names and prose changed, and the 351 tests are the evidence for that. |
| 2026-09-13 | **And one word for the thing a policy is about: `record`.** "Row" now means only what SQLite holds. | The same mix as the principal rename, one layer down: `canRead(row: Todo, …)`, `Gate.add(row)`, `View.rows`, `IdentityMap.rows`, `resident.rowCount` — all of them named a live Kotlin object a row. The rule now is that a **record** is the object the application holds, and a **row** is the tuple in the file: so `canRead(record, principal)`, `records(type)`, `recordCount`, and the benchmark's `RECORDS=` rather than `ROWS=`. "Row" is kept, deliberately, where the sentence really is about the file — the loader's `Row` type and its `reference`/`referenceOrNull`, `insertRow`/`updateRow`/`deleteRow` (named for what they write, with a comment saying so), the statement-ordering comments in `commit`, `PRAGMA` and `rowid` talk, affected-row counts, the migration tooling throughout, and Postgres's "row-level security" when naming what another system has. Sentences of the form "one row is one object" also keep it, because that *is* the boundary they describe. Names and prose only; the 351 tests are unchanged. |
