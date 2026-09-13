# jetlin-db

Persistence for jetlin applications. Entities are ordinary Kotlin objects that live in memory, backed by
Compose snapshot state and stored in SQLite. Reading a field subscribes the composable that read it;
writing one commits to disk and recomposes every session that was reading it. Access control is declared
per entity as a Kotlin function and enforced where data is obtained.

Status: **built and tested, not yet used by a production application.** `samples/teams` is the readable
proof; section 8 lists what is missing, in the order it would stop you shipping.

`samples/demo` deliberately keeps its own in-memory store. It was ported to this framework and then put
back: the port worked — its 16 application tests and its browser suite passed unchanged — but a sample
whose job is to demonstrate the view layer is clearer without a database in it, and keeping one sample on
plain `mutableStateOf` also keeps it honest that `jetlin-db` is optional. See the decision log in
`db-framework-plan.md` §13 for what the port cost, which is the useful part of the answer.

---

## 1. How it works

Three things, and the second is the one that is not obvious.

**The identity map.** Everything stored is resident: one live object per stored row, for the life of the
process. Relation traversal is a pointer dereference, so there is no N+1, no lazy-loading protocol, and —
decisively — no read that can block the single thread a session composes on.

**Cells.** A record's mutable state lives in snapshot state with a name attached. A read inside a
composable subscribes that composable. A write invalidates every composable that read it, in every
session, because the map is process-wide and the cells are ordinary Compose state.

**The snapshot is the transaction.** Compose snapshots are MVCC: isolated reads, an atomic apply,
conflict detection on merge. A database transaction is the same shape, so `transact` uses one.

```
 db.transact { todo.update { done = true } }
      │
      ├─ 1. take a mutable snapshot          ← reads isolated, writes invisible
      ├─ 2. run the block                    ← cells record which columns changed
      ├─ 3. BEGIN; UPDATE todos …; COMMIT    ← SQLite decides whether this is allowed
      └─ 4. snapshot.apply()                 ← only now can any session see it
                │
                └──► the compositions that read those cells recompose ──► patches
```

Step 3 before step 4 is the point. A write the database refuses — a constraint violation, a policy
denial, a block that threw — never becomes visible to any composition, so no session ever renders a value
the database rejected, and there is nothing to roll back on screen. The usual optimistic-update-then-revert
has a visible wrong state in it; this has none.

It also means the failure modes are all the same failure mode: `AccessDenied`, a SQLite error and an
exception from your own code all unwind the snapshot, and all of them produce no patch.

---

## 2. Entities and policies

```kotlin
@Entity
class Todo(
    @Owner val owner: User,
    title: String,
    done: Boolean = false,
) : Record() {
    var title: String by column(title)
    var done: Boolean by column(done)
    var team: Team? by reference()
    var archived: Boolean by column(false)

    companion object : Policy<Todo, User> { /* … */ }
}
```

A column is a delegated property or a primary-constructor property, and nothing else. A plain `var` on an
entity is a compile error: nothing records it, so a write would reach the screen and never the database,
and that divergence is invisible until a restart.

KSP generates, from that declaration: the table, a `Todos` object holding its columns, a `TodoDraft` type
for writes, the policy-gated accessors, and a schema snapshot for the migration tooling. An `@Entity` with
no policy fails the build.

### The three access shapes

All three are in `samples/teams`, where they can be read rather than taken on trust.

```kotlin
// 1. Owner only.
companion object : Policy<Note, User> by owned(Note::owner)

// 2. Shared by a property of a related record, written by the owner.
override fun canRead(row: Todo, principal: User): Boolean =
    row.owner == principal || (row.team != null && row.team == principal.team)
override fun canWrite(row: Todo, principal: User): Boolean = row.owner == principal

// 3. Read by many, one column restricted.
override fun canWrite(row: Todo, column: Column<Todo>, principal: User): Boolean = when (column) {
    Todos.archived -> principal.admin
    else -> canWrite(row, principal)
}
```

Because the graph is resident, a policy is a plain Kotlin expression over live objects. No SQL
translation, no expression tree, no second representation to keep in step with the schema. That is the
main thing the memory image buys, and section 5 is what it buys on top.

Two consequences worth knowing before writing one:

- **A policy sits on the recomposition hot path.** A filtered collection evaluates it per row, per read,
  and caches nothing. Keep policies cheap, pure and free of IO — a `:conventions` test enforces the last
  of those.
- **The principal is an ordinary parameter here.** Policies are called by the framework and never by
  application code, so there is nothing to protect at this layer. Context parameters are for the
  application-facing API, where they stop a mutation from compiling without a principal in scope.

---

## 3. Getting a principal, and protecting routes

The principal enters a session through `attributes { }`, which runs on the HTTP call and again when a socket
wakes a hibernated session — so the principal is recomputed from the connection that arrived rather than
trusted from a snapshot that may be minutes old.

```kotlin
val PrincipalKey = AttributeKey<User?>("principal")
val Principals = Principals(PrincipalKey, signIn = "/login")

jetlin {
    attributes { call -> mapOf(PrincipalKey to db.signedInUser(call)) }

    view("/login", title = "Sign in") { SignInPage() }
    view("/", title = "Todos", requires = Principals.signedIn) { WithPrincipal { TodoListPage(db) } }
    view("/admin/users", title = "Users", requires = Principals.where { it.admin }) {
        WithPrincipal { AdminUsersPage(db) }
    }
}
```

**The principal is nullable.** A login page and a marketing page have to be reachable; authentication is a
route requirement, not a precondition of having a session.

`db.authenticate(User::class) { it.email == email }` is the framework's one privileged root: it resolves a
record with no principal, because otherwise the system cannot bootstrap. A `:conventions` test names it, and
will fail on a second one added without the same argument.

`WithPrincipal` bridges a gap that is a property of the language rather than of the design: context
parameters are lexical and do not flow through a `@Composable () -> Unit`, so the principal is put back into
scope at the root of each page.

### Guards are a value, not an exception

```kotlin
public sealed interface Access {
    public data object Allow : Access
    public data object NotFound : Access
    public data class Redirect(val to: String) : Access
}
```

A view that throws ends the session and restarts the page, which is right for a bug and wrong for "you
are not signed in". A **failed role check is `NotFound`, not forbidden**: a 403 on `/admin/users` confirms
there is an admin panel. Disclosure is the explicit choice, never the default.

### Entity-bound routes

The part worth building. Let the route resolve its own subject:

```kotlin
view(
    "/todo/{id}",
    subject = { request -> db.todoFor(request) },   // the gated lookup
    title = { todo -> "${todo.title} · Teams" },
    requires = Principals.signedIn,
) { todo -> WithPrincipal { TodoDetailPage(db, todo) } }
```

`db.todoFor` goes through `Todos.find`, which is gated, so it returns null for a row this principal may not
read — and null renders not-found *before* the body composes and before the title is set. This deletes a
bug class rather than guarding against it: under this API the insecure version is not expressible, because
there is no path parameter left to look up by hand.

The title matters more than it looks. `<head>` is rendered before the body, so a title computed from a row
discloses it even when the body refused to show it. Every route test in this repository asserts the title
separately for that reason.

### Three ways in, all tested

| Entry | What happens |
|---|---|
| Deep link | The guard runs at the HTTP layer. A redirect is a 302 with no session built; a refusal is a 404. |
| In-session navigation | No page load. The guard runs in the composition and redirects client-side. |
| Hibernation wake | `attributes { }` re-runs, so the principal is recomputed — and the **current** route's guard is re-evaluated, not only the one being entered. A role revoked while a laptop slept is noticed when it opens. |

**Guards are not the security boundary.** The row policy is. A guard is UX plus a cheap early exit: it
stops you rendering a page that would have been empty. If a guard is ever the only thing protecting data,
one forgotten guard is a leak. Keep the order — `find` gated, traversal gated, `update` gated, guards on
top, never instead.

---

## 4. Reading and writing

```kotlin
@Composable
context(principal: User)
fun TodoListPage(db: Db) {
    Ul {
        for (todo in db.todos.sortedBy { it.archived }) {
            key(todo.id) { TodoRow(todo) }
        }
    }
}
```

`db.todos` is a generated `View<Todo>`: a live, policy-filtered list over the identity map. `filter`,
`sortedBy` and `groupBy` are the stdlib. There is deliberately no query DSL — at this scale a linear scan
over resident objects is cheaper than parsing anything, and a scan cannot drift out of step with the
schema.

Writes go through a draft:

```kotlin
db.todos.add(Todo(principal, draft.value.trim()))
todo.update { done = !done }
todo.delete()
```

`update` carries `context(principal: User)`, so a mutation with no principal in lexical scope does not
compile — and because a context parameter really is a parameter, that is checkable against the bytecode,
which is what `PolicyTest` does.

**Why `update { }` rather than `todo.done = true`?** A property setter has nowhere to put a context
parameter, so bare assignment could only check an ambient principal at runtime. `update { }` costs eight
characters and keeps the guarantee at compile time. The draft is also what makes column-level policy
possible: one block can have `title = "x"` accepted and `archived = true` refused.

A refusal throws and unwinds the whole transaction rather than skipping the refused column. A silently
skipped write — changed on screen, absent from disk — is the exact failure this design exists to avoid.

### A reference is authority

Access is checked where a record is **obtained**: a collection, a lookup, a relation. Once application
code holds a record, reading its fields is unchecked. The alternatives were weighed and rejected: a
per-principal facade costs an allocation and a check on the recomposition hot path and makes the type flowing
through the application a view rather than an entity; requiring a principal in the type of every read
propagates `User` into every composable that touches data.

What makes that survivable is not that it is safe — it is that its failure mode is findable:

1. Nothing ungated reaches application code, and a `:conventions` test holds that line.
2. Relation collections are policy-filtered per read.
3. Writes re-check, because a reference can outlive the check that produced it.
4. There is exactly one escape hatch. It is called `unsafe`, it is greppable, and it logs at WARN every
   time it runs.
5. The leak detector (`-Djetlin.db.leakDetector=true`) records which principals obtained a record and fails
   any field read under a principal that never did, with the acquisition site attached as the exception's
   cause. On in every test in this repository; off in production, where it costs one branch.

---

## 5. Reactive authorization

The framework's most distinctive property, and it is emergent rather than built.

A policy reads live state. Reading a filtered view subscribes the composition to whatever the policy
touched. So revocation is just a write:

```kotlin
// Alice, in her own session, takes a todo back off the team.
with(alice) { todo.update { team = null } }
```

Bob is looking at `/` in another session. His page iterated `db.todos`, whose filter read
`row.team == principal.team`. Alice's write invalidates exactly the compositions that read that, Bob's list
recomposes, and the row leaves his page. Nothing subscribed, nothing broadcast, no invalidation code
anywhere in the application.

It works for guards too, because a guard is also a read of live state:

```kotlin
// An admin demoting someone who is sitting on /admin/users.
with(root) { user.update { admin = false } }
```

That user's `Guarded` re-evaluates, resolves to `NotFound`, and they are off the page. No polling, no
logout broadcast. Same for entity-bound routes: unshare a project and whoever is holding one of its todos
open lands on not-found.

This is easy to lose by accident — by caching a policy result per entity instead of per (entity, principal),
or by snapshotting the principal at login — which is why there are tests for it in
`jetlin-db/PolicyTest.kt`, `jetlin-db/EntityRouteTest.kt`, `jetlin-testing/GuardTest.kt` and
`samples/teams/TeamsAppTest.kt` rather than only a paragraph here.

---

## 6. Storage and residency

SQLite through `org.xerial:sqlite-jdbc`, in WAL mode, `synchronous=NORMAL`, `foreign_keys=ON`, a
`busy_timeout`. One file, in process, no server. Litestream is the backup story and needs no code.

At boot, every table is read into the identity map and references are resolved against what is already
resident — which is why load order is part of the generated schema rather than the application's problem.

One process writes. An outside writer is detected rather than tolerated: `PRAGMA data_version` is
unchanged by our own commits and bumped by anyone else's, so it is checked before every flush and a
mismatch refuses the write, loudly. An exclusive lock would prevent it outright, but in WAL mode an
exclusive lock shuts out readers too — including the backup tool — so detection one commit late is the
trade taken.

Transactions are serialized per database. One JDBC connection cannot carry two at once, and serializing
them also makes commit order and apply order the same order, which is what makes last-write-wins in
memory equal to what is on disk. Reads are not serialized and never block.

**Memory is the ceiling.** The working set is resident and shares a budget with the live session
compositions. Two benchmarks measure the two halves:

```
./gradlew :samples:teams:benchmark          # the graph, over a table worth measuring
rows:               20000 resident records
graph:              1096 bytes per record (20 MB total)

./gradlew :samples:demo:benchmark           # sessions; PAGE=real for an application's own page
page:               synthetic          | the application's todo list
nodes per session:  113                | 42
live:               129 kB per session | 65 kB per session
```

**A resident record costs about 1.1 kB** — a `Todo` with four columns, one reference and strings of
ordinary length. That figure barely moves between 2,000 rows and 20,000, so a hundred thousand rows is
around 110 MB and a million is a gigabyte: residency stops being free somewhere in the hundreds of
thousands of rows, not the thousands.

**A session costs what its page costs**, at roughly 1.5 kB a node in both of those measurements — the
virtual DOM and the composition around it, not anything the database adds. So a page that lists a large
collection is the thing to watch rather than the collection itself: rows are charged once to the graph,
and again per session for each row a session actually *renders*.

Reading a policy-filtered collection does add to a composition's read set, because the policy is evaluated
per row and a page filtering a big table subscribes to cells in every row it scanned. Measured, that is
about 30 bytes per scanned row per session: real, and an order of magnitude below the cost of rendering a
row.

There is an exit if it is ever needed, and it needs no change to the model: a cell that starts absent
renders a placeholder instead of blocking, so cold tables can move off the resident graph. Residency is an
optimization, not a foundation. It is not used for the database in v1 — a microsecond SQLite read should
not produce a placeholder flicker.

---

## 7. Migrations

KSP writes the schema the entities declare into the build output. `db/schema.json` is the copy this
repository has recorded. Everything else follows from comparing the two.

```
./gradlew dbDiff --name=share_todos_by_team   # writes db/migrations/0002_share_todos_by_team.sql
./gradlew dbMigrate                           # applies pending migrations
./gradlew dbVerify                            # fails if entities and db/schema.json disagree (CI)
```

A migration is a SQL file a human reads and may edit, named `0001_what_it_does.sql`, applied in order and
recorded in a `jetlin_migrations` table. What is in the file is what runs.

SQLite's `ALTER TABLE` can add a column, drop a column, rename a column and rename a table. A type, a
nullability or a foreign key requires building a new table, copying the rows, dropping the old one and
renaming — the twelve-step procedure SQLite documents. The generator emits that; the runner does the parts
that need a live connection:

- foreign keys off for the duration, because the rebuild drops a table other tables reference;
- `PRAGMA foreign_key_check` before committing, so a new constraint that orphans rows fails rather than
  warns;
- every index, trigger and view that existed is still there afterwards. A rebuild takes them with it, and
  a generator that cannot see a particular database cannot know what to re-create. So the runner notices
  and stops, naming what was lost. A view over a rebuilt table is the classic version of this trap.

A destructive migration carries a marker line that has to be deleted before it will run. An acknowledgement
in the file shows up in a review forever; a `--force` flag is invisible the moment it has been typed.

`Db.open` creates tables that do not exist and then **verifies** the ones that do: a column missing, a
type or nullability that disagrees, a foreign key that is not there, or a stored column no entity declares
all refuse to boot, naming the table and column. That is what makes an un-applied migration a startup
failure instead of a failed insert during a deploy.

---

## 8. What is missing

In the order it would stop you shipping.

**A leaked reference is authority.** Chosen knowingly; see section 4. The leak detector makes it findable
in development, not impossible.

**Transitive visibility on write is not caught.** Moving a project to another team changes who may read
its todos, but only the project's own policy is consulted — nothing declares that a todo's visibility
depends on its project's team, so the write does not fan out. *Reads are always correct*: the next time a
todo's policy runs it gives the right answer. What is missing is the invalidation, and the case where it
shows is a page that was already open when the project moved. Catching it needs policies to declare their
visibility dependencies.

**No defense in depth.** SQLite has no row-level security, so the framework is the only enforcement layer.
Arbitrary Kotlin policies do not translate to Postgres RLS, so an application that later needs a second
layer will not get one for free.

**Memory is the ceiling.** Section 6.

**No indexes.** Every ad-hoc query is a linear scan over resident objects. Fine at the target scale;
revisit with a measurement rather than a hunch.

**A reference cycle cannot be loaded.** References resolve against what is already resident, so
`User.team` / `Team.owner` pointing at each other is a build error naming both. A fixup pass over nullable
references is the obvious fix and does not change the model.

**One process.** The resident graph is per-process: a second node would hold a second copy and the two
would diverge. That is a harder blocker for multi-node than the session store, and it is recorded next to
it in `architecture.md` §13.

**In-session navigation to an entity-bound route shows the route's fallback title.** The title travels in
the navigate message, which is filled from the route table before the new view has resolved anything. Page
loads and hibernation wakes are correct. Fixing it means a protocol change.

---

## 9. Where the pieces live

```
:jetlin-db          Record, cells, the identity map, View, Policy, the gate, transactions, SQLite
:jetlin-db-ksp      the processor: tables, column objects, drafts, gated accessors, schema snapshot
:jetlin-db-gradle   dbDiff, dbMigrate, dbVerify, and the migration engine they share
:samples:teams      a two-login sample exercising all three access shapes
```

`Record`, `Policy`, `View`, `Id` and the cell delegate contain no database concepts — no SQL, no
connection, no transaction, no table. That is deliberate: the read side and the authorization model
generalize to data that is not in a database at all, and keeping the boundary honest now is the
difference between that extraction being a file move and being a redesign.

`:jetlin-db` depends on `:jetlin-runtime` for the snapshot machinery and **not** on `:jetlin-html`. Route
guards are routing, not storage, so `Access`, `Guard`, `Principals` and `Guarded` live in `:jetlin-html` and
work for an application whose principal is not a record.
