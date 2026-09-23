# jetlin-db

`jetlin-db` is the persistence layer for Jetlin applications. Entities are ordinary Kotlin objects kept
in memory, backed by Compose snapshot state and stored in SQLite. Reading a field subscribes the
composable that read it. Writing a field commits the change to disk and then recomposes every session
that read it. Access control is declared per entity as Kotlin functions, and it is enforced whenever
application code obtains a record.

Status: **built and tested, but not yet used by a production application.** `samples/teams` shows it
in use. Section 8 lists what is missing, ordered by how likely each gap is to block a release.

`samples/demo` intentionally keeps its own in-memory store. It was ported to `jetlin-db` and then
reverted. The port worked: its 16 application tests and its browser tests passed without changes. But
the demo exists to show the view layer, and it is clearer without a database. Keeping one sample on
plain `mutableStateOf` also shows that `jetlin-db` is optional. The decision log in
`db-framework-plan.md` §13 records what the port involved, which is a useful estimate for porting an
existing application.

---

## 1. How it works

The design rests on three mechanisms. The second is the least obvious.

**The identity map.** Every stored row is loaded into memory as a single live object, and stays there
for the life of the process. Following a relation is a field access, so there is no N+1 problem, no
lazy loading, and, most importantly, no read that can block the single thread a session composes on.

**Cells.** A record's mutable fields are stored in named snapshot state, called cells. Reading a cell
in a composable subscribes that composable. Writing a cell invalidates every composable that read it,
in every session, because the identity map is shared across the process and cells are ordinary Compose
state.

**Transactions are snapshots.** Compose snapshots provide MVCC: isolated reads, atomic apply, and
conflict detection on merge. That is the same model as a database transaction, so `transact` uses a
snapshot.

```
 db.transact { todo.update { done = true } }
      │
      ├─ 1. take a mutable snapshot          ← reads isolated, writes not yet visible
      ├─ 2. run the block                    ← cells record which columns changed
      ├─ 3. BEGIN; UPDATE todos …; COMMIT    ← SQLite accepts or rejects the write
      └─ 4. snapshot.apply()                 ← only now can other sessions see it
                │
                └──► compositions that read those cells recompose ──► patches
```

The order of steps 3 and 4 is the key point. If the database rejects a write (a constraint
violation, a policy denial, or an exception in the block), the change never becomes visible to any
composition. No session renders a value the database refused, so nothing has to be reverted on screen.
The common approach of updating the UI optimistically and reverting on failure briefly shows a wrong
value; this approach never does.

It also means every kind of failure is handled the same way. `AccessDenied`, a SQLite error and an
exception from your own code all discard the snapshot, and none of them produce a patch.

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

A column is either a delegated property or a primary-constructor property. A plain `var` on an entity
is a compile error: nothing would record writes to it, so a change would appear on screen but never
reach the database, and the difference wouldn't be noticed until a restart lost it.

From this declaration, KSP generates the table definition, a `Todos` object holding the column objects,
a `TodoDraft` type used for writes, the policy-checked accessors, and a schema snapshot for the
migration tooling. An `@Entity` without a policy fails the build.

### The three access patterns

All three are used in `samples/teams`, so you can see them working.

```kotlin
// 1. Owner only.
companion object : Policy<Note, User> by owned(Note::owner)

// 2. Shared through a related record, and writable by the owner.
override fun canRead(record: Todo, principal: User): Boolean =
    record.owner == principal || (record.team != null && record.team == principal.team)
override fun canWrite(record: Todo, principal: User): Boolean = record.owner == principal

// 3. Widely readable, with one restricted column.
override fun canWrite(record: Todo, column: Column<Todo>, principal: User): Boolean = when (column) {
    Todos.archived -> principal.admin
    else -> canWrite(record, principal)
}
```

Because all records are in memory, a policy is ordinary Kotlin code operating on live objects. It
doesn't need to be translated to SQL, so there is no expression tree and no second representation to
keep consistent with the schema. That is the main benefit of keeping data in memory, and section 5
describes a further one.

Two things to know before writing a policy:

- **Policies run during recomposition.** A filtered collection evaluates its policy for each record on
  every read, and caches nothing. Keep policies cheap, pure and free of IO. A `:conventions` test
  enforces the last of these.
- **The principal is a normal parameter here.** Only the framework calls policies, so there is nothing
  to enforce at this level. Context parameters are used in the application-facing API instead, where
  they make a write without a principal in scope a compile error.

---

## 3. Getting a principal, and protecting routes

The principal is added to a session by `attributes { }`. That block runs for the initial HTTP request
and again when a WebSocket wakes a hibernated session, so the principal is recomputed from the new
connection instead of being restored from a snapshot that may be minutes old.

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

**The principal is nullable.** Pages such as a login page or a marketing page must be reachable
without signing in. Authentication is a requirement of individual routes, not of having a session.

`db.authenticate(User::class) { it.email == email }` is the framework's one privileged entry point. It
looks up a record without a principal, because otherwise there would be no way to establish the first
principal. A `:conventions` test lists it as an allowed exception, and fails if another unchecked
entry point is added without the same justification.

`WithPrincipal` works around a language limitation, not a design choice. Context parameters are
lexically scoped and aren't passed through a `@Composable () -> Unit`, so each page brings the
principal back into scope at its root.

### Guards return a value

```kotlin
public sealed interface Access {
    public data object Allow : Access
    public data object NotFound : Access
    public data class Redirect(val to: String) : Access
}
```

A view that throws ends the session and reloads the page. That is right for a bug but wrong for an
ordinary case like "you need to sign in", so guards return a value instead of throwing. **A failed role
check returns `NotFound`, not a forbidden page.** A 403 on `/admin/users` would confirm that an admin
panel exists. Revealing that should be an explicit choice, never the default.

### Entity-bound routes

This is the most important part of route protection. The route looks up its own subject:

```kotlin
view(
    "/todo/{id}",
    subject = { request -> db.todoFor(request) },   // the policy-checked lookup
    title = { todo -> "${todo.title} · Teams" },
    requires = Principals.signedIn,
) { todo -> WithPrincipal { TodoDetailPage(db, todo) } }
```

`db.todoFor` uses `Todos.find`, which is policy-checked, so it returns null for a record this principal
may not read. A null subject shows the not-found page *before* the body is composed and before the title
is set. This removes a whole class of bug instead of just guarding against it. The view never receives
the path parameter, so it can't look up an arbitrary id by hand.

The title matters more than it might seem. `<head>` is rendered before the body, so a title computed
from a record would reveal the record even if the body refused to show it. For that reason, every route
test in this repository checks the title separately.

### Three ways to reach a route, all tested

| Entry | What happens |
|---|---|
| Deep link | The guard runs in the HTTP layer. A redirect is a 302 and no session is created; a refusal is a 404. |
| Navigation within a session | No page load. The guard runs in the composition and redirects on the client. |
| Waking from hibernation | `attributes { }` runs again, so the principal is recomputed, and the guard for the **current** route is re-evaluated, not only guards on routes being entered. A role revoked while a laptop was asleep takes effect when it wakes. |

**Guards are not the security boundary; record policies are.** A guard improves the user experience and
avoids rendering a page that would be empty. If a guard is ever the only protection for some data,
forgetting that guard leaks it. Keep the layers in order: `find` is policy-checked, traversal is
policy-checked, `update` is policy-checked, and guards are added on top, never as a replacement.

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

`db.todos` is a generated `View<Todo>`: a live list over the identity map, filtered by the policy.
Queries use the standard library's `filter`, `sortedBy` and `groupBy`. There is intentionally no query
DSL. At this scale, a linear scan of in-memory objects is cheaper than parsing a query, and it can't get
out of sync with the schema.

Writes go through a draft:

```kotlin
db.todos.add(Todo(principal, draft.value.trim()))
todo.update { done = !done }
todo.delete()
```

`update` takes `context(principal: User)`, so a write without a principal in scope doesn't compile. A
context parameter is a real parameter in the compiled code, so this can be checked in the bytecode,
which is what `PolicyTest` does.

**Why `update { }` instead of `todo.done = true`?** A property setter can't take a context parameter,
so plain assignment could only check a thread-local principal at runtime. `update { }` is slightly
longer to write, but keeps the check at compile time. The draft also makes column-level policies
possible, because each assignment in the block is checked separately: `title = "x"` can be allowed
while `archived = true` is refused.

A refused column throws and rolls back the whole transaction, instead of skipping just that column. A
silently skipped write, shown on screen but not saved, is exactly the failure this design is meant to
prevent.

### Holding a reference grants access

Access is checked when a record is **obtained**: from a collection, a lookup or a relation. Once
application code has a record, reading its fields isn't checked. The alternatives were considered and
rejected. A per-principal wrapper would add an allocation and a check to every recomposition, and would
mean the application handles a wrapper instead of the entity. Requiring a principal in the type of
every read would spread `User` into every composable that touches data.

This approach isn't leak-proof, but leaks can be found, for these reasons:

1. No unchecked lookup is reachable from application code, and a `:conventions` test enforces that.
2. Relation collections are filtered by policy on every read.
3. Writes are checked again, because a reference can outlive the check that produced it.
4. There is exactly one way to bypass the checks. It is called `unsafe`, it is easy to search for, and
   it logs a warning every time it runs.
5. The leak detector (`-Djetlin.db.leakDetector=true`) records which principals obtained each record,
   and throws when a field is read by a principal that never obtained it. The exception's cause is the
   stack trace where the record was obtained. It is enabled for every test in this repository. In
   production it is off and costs one branch per read.

---

## 5. Reactive authorization

This is the framework's most distinctive feature, and it follows from the design rather than being
built separately.

A policy reads live state, and reading a filtered view subscribes the composition to everything the
policy read. So revoking access is just a write:

```kotlin
// Alice, in her own session, unshares a todo from the team.
with(alice) { todo.update { team = null } }
```

Bob has `/` open in another session. His page iterated `db.todos`, whose filter read
`record.team == principal.team`. Alice's write invalidates exactly the compositions that read it, Bob's
list recomposes, and the todo disappears from his page. There are no subscriptions, broadcasts or
invalidation code anywhere in the application.

Guards work the same way, because a guard also reads live state:

```kotlin
// An admin removes the admin role from someone who is viewing /admin/users.
with(root) { user.update { admin = false } }
```

That user's `Guarded` re-evaluates to `NotFound`, and they are moved off the page, without polling or a
logout broadcast. Entity-bound routes behave the same way: if a project is unshared, anyone viewing one
of its todos is moved to the not-found page.

This behaviour is easy to break by accident, for example by caching a policy result per entity instead
of per (entity, principal) pair, or by copying the principal at sign-in. That is why it is covered by
tests in `jetlin-db/PolicyTest.kt`, `jetlin-db/EntityRouteTest.kt`, `jetlin-testing/GuardTest.kt` and
`samples/teams/TeamsAppTest.kt`, not just described here.

---

## 6. Storage and memory

Storage uses SQLite through `org.xerial:sqlite-jdbc`, with WAL mode, `synchronous=NORMAL`,
`foreign_keys=ON` and a `busy_timeout`. It is a single file accessed in process, with no database
server. For backups, use Litestream, which requires no code changes.

At startup, every table is loaded into the identity map, and references are resolved against records
that are already loaded. That is why the load order is part of the generated schema instead of
something the application has to manage.

Only one process may write to the database. Writes by another process are detected, not supported.
SQLite's `PRAGMA data_version` doesn't change for this connection's own commits but does change when
another connection commits, so it is checked before every write, and a change makes the write fail with
an error. An exclusive lock would prevent outside writes entirely, but in WAL mode it also blocks
readers, including the backup tool. Detecting an outside write one commit late was chosen as the better
trade-off.

Transactions run one at a time per database. A single JDBC connection can't run two transactions at
once, and serializing them also makes commits and snapshot applies happen in the same order. That is
what keeps last-write-wins in memory consistent with what is on disk. Reads are not serialized and
never block.

**Memory is the limit.** All records are held in memory, in the same heap as the live session
compositions. Two benchmarks measure the two parts:

```
./gradlew :samples:teams:benchmark          # the record graph, with a large table
records:            20000 resident
graph:              1096 bytes per record (20 MB total)

./gradlew :samples:demo:benchmark           # sessions; PAGE=real for the application's own page
page:               synthetic          | the application's todo list
nodes per session:  113                | 42
live:               129 kB per session | 65 kB per session
```

**A record in memory costs about 1.1 kB.** That was measured for a `Todo` with four columns, one
reference and strings of typical length. The figure stays nearly constant between 2,000 and 20,000
records, so 100,000 records would take about 110 MB and a million about 1 GB. Memory becomes a real
concern somewhere in the hundreds of thousands of records, not the thousands.

**A session costs roughly what its page costs**, about 1.5 kB per node in both measurements. That cost
is the virtual DOM and the composition around it; the database adds nothing to it. So a page that
renders a large collection matters more than the size of the collection. Each record is counted once in
the graph, and then again in each session for each record that session actually *renders*.

Reading a policy-filtered collection does increase a composition's read set. The policy is evaluated
for every record, so a page that filters a large table subscribes to cells in every record it scanned.
The measured cost is about 30 bytes per scanned record per session. That is a real cost, but about an
order of magnitude less than rendering the record.

If memory ever becomes a problem, there is a way out that doesn't change the model. A cell that starts
out empty can render a placeholder instead of blocking, so rarely used tables could be moved out of
memory. Keeping everything in memory is an optimization, not a requirement of the design. v1 doesn't do
this for the database, because a SQLite read that takes microseconds shouldn't cause a placeholder to
flicker on screen. That kind of cell does exist, as `jetlin.runtime.Fetch`, and applications use it for
data they don't own. `docs/architecture.md` §8 describes it.

---

## 7. Migrations

KSP writes the schema declared by the entities into the build output. `db/schema.json` is the copy
checked into the repository. The migration tasks work by comparing the two.

```
./gradlew dbDiff --name=share_todos_by_team   # writes db/migrations/0002_share_todos_by_team.sql
./gradlew dbMigrate                           # applies pending migrations
./gradlew dbVerify                            # fails if the entities and db/schema.json differ (CI)
```

A migration is a SQL file that is meant to be read and, if needed, edited. Files are named like
`0001_what_it_does.sql`, applied in order, and recorded in a `jetlin_migrations` table. The SQL in the
file is exactly what runs.

SQLite's `ALTER TABLE` can add, drop and rename columns and rename tables. Changing a column's type,
nullability or foreign key requires creating a new table, copying the rows, dropping the old table and
renaming the new one: the twelve-step procedure in SQLite's documentation. The generator writes that
SQL. The runner handles the steps that need a live connection:

- It disables foreign keys during the migration, because the rebuild drops a table that other tables
  reference.
- It runs `PRAGMA foreign_key_check` before committing, so a new constraint that orphans existing rows
  fails the migration instead of producing a warning.
- It checks that every index, trigger and view that existed before still exists afterwards. A rebuild
  drops everything attached to the old table, and the generator can't know which of these objects a
  given database has. So the runner detects the loss and stops, naming what was lost. A view over a
  rebuilt table is the most common case.

A migration that destroys data contains a marker line, and `dbMigrate` refuses to run it until the
line is deleted. Deleting a line in the file shows up in code review permanently; a `--force` flag
would leave no trace once typed.

`Db.open` creates any missing tables and **verifies** the existing ones. A missing column, a
mismatched type or nullability, a missing foreign key, or a stored column no entity declares all make
startup fail, with the table and column named. That turns a forgotten migration into a startup error
instead of a failed insert during a deploy.

---

## 8. What is missing

Ordered by how likely each gap is to block a release.

**Holding a reference grants access.** This was a deliberate choice; see section 4. The leak detector
makes leaks detectable during development, but can't prevent them.

**Visibility changes through relations aren't propagated on write.** Moving a project to another team
changes who can read its todos, but only the project's own policy is evaluated for that write. Nothing
declares that a todo's visibility depends on its project's team, so the change doesn't propagate.
*Reads are always correct*: the next time a todo's policy runs, it gives the right answer. What's
missing is invalidation, and the problem only shows on a page that was already open when the project
moved. Fixing it requires policies to declare what their visibility depends on.

**No second layer of enforcement.** SQLite has no row-level security, so the framework's policies are
the only enforcement. Arbitrary Kotlin policies can't be translated to Postgres RLS, so an application
that later needs a database-level layer would have to write one separately.

**Memory is the limit.** See section 6.

**No indexes.** Every ad-hoc query is a linear scan over in-memory objects. That is fine at the
intended scale. Revisit it based on measurements, not guesses.

**Reference cycles can't be loaded.** References are resolved against records already loaded, so two
entities that reference each other with non-null references (such as `User.team` and `Team.owner`) are
a build error that names both. A second pass that fills in nullable references after loading would fix
this without changing the model.

**Single process only.** The in-memory graph belongs to one process. A second node would hold its own
copy, and the two would drift apart. This is a harder obstacle to running on multiple nodes than the
session store, and it is listed alongside it in `architecture.md` §13.

**Navigating within a session to an entity-bound route shows the route's fallback title.** The title is
sent in the navigation message, which is built from the route table before the new view has looked up
its record. Page loads and waking from hibernation show the correct title. Fixing this requires a
protocol change.

---

## 9. Modules

```
:jetlin-db          Record, cells, the identity map, View, Policy, the gate, transactions, SQLite
:jetlin-db-ksp      the processor: tables, column objects, drafts, policy-checked accessors, schema snapshot
:jetlin-db-gradle   dbDiff, dbMigrate, dbVerify, and the migration engine they share
:samples:teams      a sample with two users, using all three access patterns
```

`Record`, `Policy`, `View`, `Id` and the cell delegate contain nothing specific to databases: no SQL,
connections, transactions or tables. This is deliberate. The read side and the authorization model
could also apply to data that isn't in a database, and keeping the boundary clean now means that
extracting them later would be a matter of moving files, not redesigning.

`:jetlin-db` depends on `:jetlin-runtime` for the snapshot system, and **not** on `:jetlin-html`. Route
guards are part of routing, not storage, so `Access`, `Guard`, `Principals` and `Guarded` live in
`:jetlin-html`. They also work for applications whose principal isn't a stored record.
