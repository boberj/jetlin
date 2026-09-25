# jetlin-db

`jetlin-db` is the storage layer for Jetlin applications. Entities are ordinary Kotlin objects kept in
memory, backed by Compose snapshot state, and stored in SQLite. Reading a field subscribes the
composable that read it. Writing a field commits the change to disk, then recomposes every session
that read it. You declare access control for each entity as Kotlin functions, and the framework
enforces it whenever application code obtains a record.

`jetlin-db` is built and tested, but no production application uses it yet. `samples/teams` shows it
in use. [§8](#8-whats-missing) lists what's missing, in order of how likely each gap is to block a
release.

`samples/demo` deliberately keeps its own in-memory store. It was ported to `jetlin-db` and then
reverted. The port worked: its 16 application tests and its browser tests passed without changes. But
the demo exists to show the view layer, and it's clearer without a database. Keeping one sample on
plain `mutableStateOf` also shows that `jetlin-db` is optional. The decision log in
`db-framework-plan.md` §13 records what the port involved, which is a useful estimate for porting an
existing application.

## 1. How it works

The design rests on three mechanisms. The second is the least obvious.

### The identity map

Every stored row is loaded into memory as one live object, and stays there for the life of the
process. Following a relation is a field access, so there's no N+1 problem, no lazy loading, and,
most importantly, no read that can block the single thread a session composes on.

### Cells

A record's mutable fields are stored in named snapshot state, called cells. Reading a cell in a
composable subscribes that composable. Writing a cell invalidates every composable that read it, in
every session, because the whole process shares the identity map, and cells are ordinary Compose
state.

### Transactions are snapshots

Compose snapshots provide multiversion concurrency control: isolated reads, atomic apply, and
conflict detection on merge. That's the same model as a database transaction, so `transact` uses a
snapshot:

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

The order of steps 3 and 4 is the key point. If the database rejects a write, because of a
constraint violation, a policy refusal, or an exception in the block, no composition ever sees the
change. No session renders a value that the database refused, so nothing has to be reverted on
screen. The common approach, updating the UI optimistically and reverting on failure, briefly shows a
wrong value. This approach never does.

It also means that every kind of failure is handled the same way. `AccessDenied`, a SQLite error, and
an exception from your own code all discard the snapshot, and none of them produce a patch.

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
is a compile error. Nothing would record writes to it, so a change would appear on screen but never
reach the database, and nobody would notice until a restart lost it.

From this declaration, KSP generates the table definition, a `Todos` object that holds the column
objects, a `TodoDraft` type for writes, the policy-checked accessors, and a schema snapshot for the
migration tooling. An `@Entity` without a policy fails the build.

### The three access patterns

`samples/teams` uses all three, so you can see them working:

```kotlin
// 1. Owner only.
companion object : Policy<Note, User> by owned(Note::owner)

// 2. Shared through a related record, and writable by the owner or an admin.
override fun canWrite(record: Todo, principal: User): Boolean = record.owner == principal || principal.admin
override fun canRead(record: Todo, principal: User): Boolean =
    canWrite(record, principal) || (record.team != null && record.team == principal.team)

// 3. One admin-only column.
override fun canWrite(record: Todo, column: Column<Todo>, principal: User): Boolean = when (column) {
    Todos.archived -> principal.admin
    else -> canWrite(record, principal)
}
```

Because all records are in memory, a policy is ordinary Kotlin code that works on live objects. It
doesn't need to be translated to SQL, so there's no expression tree and no second representation to
keep consistent with the schema. That's the main benefit of keeping data in memory, and §5 describes
another.

Before you write a policy, know two things:

- Policies run during recomposition. A filtered collection runs its policy for each record on every
  read, and caches nothing. Keep policies cheap, pure, and free of I/O. A `:conventions` test
  enforces the last of these.
- The principal is a normal parameter here. Only the framework calls policies, so there's nothing to
  enforce at this level. The application-facing API uses context parameters instead, where they make
  a write without a principal in scope a compile error.

## 3. Getting a principal, and protecting routes

`attributes { }` adds the principal to a session. The block runs for the initial HTTP request, and
again when a WebSocket wakes a hibernated session, so the principal is recomputed from the new
connection instead of restored from a snapshot that might be minutes old.

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

The principal is nullable. Pages such as a sign-in page or a marketing page must be reachable without
signing in. Authentication is a requirement of individual routes, not of having a session.

`db.authenticate(User::class) { it.email == email }` is the framework's one privileged entry point. It
looks up a record without a principal, because otherwise there'd be no way to establish the first
principal. A `:conventions` test lists it as an allowed exception, and fails if someone adds another
unchecked entry point without the same justification.

`WithPrincipal` works around a language limitation, not a design choice. Context parameters are
lexically scoped, and they aren't passed through a `@Composable () -> Unit`, so each page brings the
principal back into scope at its root.

### Guards return a value

```kotlin
public sealed interface Access {
    public data object Allow : Access
    public data object NotFound : Access
    public data class Redirect(val to: String) : Access
}
```

A view that throws ends the session and reloads the page. That's right for a bug, but wrong for an
ordinary case like "you need to sign in," so guards return a value instead of throwing.

A failed role check returns `NotFound`, not a forbidden page. A `403` on `/admin/users` would confirm
that an admin panel exists. Revealing that should be an explicit choice, never the default.

### Routes for one record

This is the most important part of route protection. The route looks up its own subject:

```kotlin
view(
    "/todo/{id}",
    subject = { request -> db.todoFor(request) },   // The policy-checked lookup.
    title = { todo -> "${todo.title} · Teams" },
    requires = Principals.signedIn,
) { todo -> WithPrincipal { TodoDetailPage(db, todo) } }
```

`db.todoFor` uses `Todos.find`, which is policy-checked, so it returns `null` for a record that this
principal can't read. A `null` subject shows the not-found page before the body is composed, and
before the title is set. This removes a whole class of bug, instead of only guarding against it. The
view never receives the path parameter, so it can't look up an arbitrary ID by hand.

The title matters more than it might seem. `<head>` is rendered before the body, so a title computed
from a record would reveal the record even if the body refused to show it. So every route test in
this repository checks the title separately.

### Three ways to reach a route, all tested

| Entry | What happens |
|---|---|
| Deep link | The guard runs in the HTTP layer. A redirect is a `302` response, and no session is created. A refusal is a `404`. |
| Navigation within a session | There's no page load. The guard runs in the composition and redirects on the client. |
| Waking from hibernation | `attributes { }` runs again, so the principal is recomputed, and the guard for the current route runs again, not only guards on routes being entered. A role revoked while a laptop was asleep takes effect when it wakes. |

Guards aren't the security boundary. Record policies are. A guard improves the user experience and
avoids rendering a page that would be empty. If a guard is ever the only protection for some data,
forgetting the guard leaks the data. Keep the layers in order: `find` is policy-checked, traversal is
policy-checked, `update` is policy-checked, and guards are added on top, never as a replacement.

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
Queries use the standard library's `filter`, `sortedBy`, and `groupBy`. There's deliberately no query
DSL. At this scale, a linear scan of in-memory objects is cheaper than parsing a query, and it can't
get out of sync with the schema.

Writes go through a draft:

```kotlin
db.todos.add(Todo(principal, draft.value.trim()))
todo.update { done = !done }
todo.delete()
```

`update` takes `context(principal: User)`, so a write without a principal in scope doesn't compile. A
context parameter is a real parameter in the compiled code, so a test can check it in the bytecode,
which is what `PolicyTest` does.

### Why `update { }` instead of `todo.done = true`

A property setter can't take a context parameter, so plain assignment could only check a
thread-local principal at runtime. `update { }` is slightly longer to write, but it keeps the check
at compile time. The draft also makes column-level policies possible, because each column the
block sets is checked separately: `title = "x"` can be allowed while `archived = true` is refused.

The draft holds the writes back until the block finishes. Reading a field in the block returns what
the block set, but the record itself doesn't change until three checks pass:

1. Before the block, the principal must be able to change the record (`canWrite`).
2. After the block, each column it set must pass the column rule, checked against the record as it
   was before the block. The order of the assignments doesn't matter.
3. After the values are stored, the principal must be able to create the record as it now is
   (`canCreate`).

The third check closes a gap the first two leave open. They only ask "may you change this record as
it is?", never "may it end up like this?". Without it, you could create a record you're allowed to,
then change its owner to someone else, which is exactly what `add` would refuse. The column rule
can't catch that, because it doesn't see the new value. Rules about *values*, such as "a todo can
only be shared with your own team", go in `canCreate`, and every write path enforces them.

A column rule only narrows access. `update { }` checks the record-level `canWrite` before the block
runs, so a column rule is only asked about principals who can already change the record. That's why
the sample's `Todo.canWrite` admits admins for the whole record: with `record.owner == principal`
alone, `Todos.archived -> principal.admin` would only let an admin archive their own todos. To let
someone change one column of a record they otherwise can't, widen the record-level `canWrite` and
narrow the other columns in the column rule.

### Transferring ownership

Because of the third check, `update { owner = bob }` is always refused: a record owned by someone
else is one you couldn't create. Transfers have their own operation. When the `@Owner` column is a
`var` of the principal type, KSP generates `transferTo(to)` and `canTransferTo(to)`, and the policy
decides with `canTransfer`, which refuses everyone by default:

```kotlin
@Entity
class Doc(@Owner owner: User, text: String) : Record() {
    var owner: User by reference(owner)
    var offeredTo: User? by reference()
    var text: String by column(text)

    companion object : Policy<Doc, User> {
        override fun canWrite(record: Doc, principal: User) = record.owner == principal
        override fun canRead(record: Doc, principal: User) =
            canWrite(record, principal) || record.offeredTo == principal
        // Only the person it's offered to can take it, and only for themselves.
        override fun canTransfer(record: Doc, to: User, principal: User) =
            to == principal && record.offeredTo == principal
    }
}

with(alice) { doc.update { offeredTo = bob } }   // an ordinary update: alice still owns it
with(bob) { if (doc.canTransferTo(bob)) doc.transferTo(bob) }
```

`canTransfer` is the whole rule for a transfer. It doesn't also require `canWrite`, so a policy can
let a recipient pull a record, as above, or let an owner push one, with
`record.owner == principal && to.team == principal.team`. With the offer-and-accept rule, someone
can offer you a record, but only you can make it yours.

To decide what a page offers, ask the same checks instead of repeating the policy's logic. Next to
`update { }` and `delete()`, KSP generates `canUpdate()`, `canUpdate(column)`, and `canDelete()`,
which take the principal from context and go through the same gate as the writes:

```kotlin
disabled(!todo.canUpdate(Todos.archived))   // the record-level check, then the column's
if (todo.canDelete()) Button({ onClick { todo.delete() } }) { Text("Delete") }
```

`canUpdate(column)` makes the first two checks `update { }` makes, so it never says yes to a write
those would refuse. It can't foresee the third, because that depends on the value being written. The
answers are reactive like everything else: they read the same cells as the policy, so revoking a role
updates the controls on pages that are already open.

A refused column throws and rolls back the whole transaction, instead of skipping only that column. A
write that's skipped without an error, shown on screen but not saved, is exactly the failure this
design prevents.

### Holding a reference grants access

Access is checked when code obtains a record: from a collection, a lookup, or a relation. Once
application code has a record, reading its fields isn't checked. The alternatives were considered and
rejected. A wrapper for each principal would add an allocation and a check to every recomposition, and
the application would handle a wrapper instead of the entity. Requiring a principal in the type of
every read would spread `User` into every composable that touches data.

This approach isn't leak-proof, but leaks can be found, for these reasons:

1. Application code can't reach an unchecked lookup, and a `:conventions` test enforces that.
2. Relation collections are filtered by policy on every read.
3. Writes are checked again, because a reference can outlive the check that produced it.
4. There's exactly one way to bypass the checks. It's called `unsafe`, it's easy to search for, and it
   logs a warning every time it runs.
5. The leak detector, turned on with `-Djetlin.db.leakDetector=true`, records which principals
   obtained each record, and throws when a principal that never obtained a record reads one of its
   fields. The exception's cause is the stack trace where the record was obtained. It's on for every
   test in this repository. In production, it's off and costs one branch per read.

## 5. Reactive authorization

This is the framework's most distinctive feature, and it follows from the design instead of being
built separately.

A policy reads live state, and reading a filtered view subscribes the composition to everything the
policy read. So revoking access is only a write:

```kotlin
// Alice, in her own session, unshares a todo from the team.
with(alice) { todo.update { team = null } }
```

Bob has `/` open in another session. His page iterated `db.todos`, whose filter read
`record.team == principal.team`. Alice's write invalidates exactly the compositions that read it, so
Bob's list recomposes, and the todo disappears from his page. There are no subscriptions, broadcasts,
or invalidation code anywhere in the application.

Guards work the same way, because a guard also reads live state:

```kotlin
// An admin removes the admin role from someone who is viewing /admin/users.
with(root) { user.update { admin = false } }
```

That user's `Guarded` now returns `NotFound`, and they're moved off the page, without polling or a
logout broadcast. Routes for one record behave the same way: if a project is unshared, anyone viewing
one of its todos is moved to the not-found page.

This behavior is easy to break by accident, for example by caching a policy result for each entity
instead of for each pair of entity and principal, or by copying the principal at sign-in. That's why
tests cover it in `jetlin-db/PolicyTest.kt`, `jetlin-db/EntityRouteTest.kt`,
`jetlin-testing/GuardTest.kt`, and `samples/teams/TeamsAppTest.kt`, instead of it only being
described here.

## 6. Storage and memory

Storage uses SQLite through `org.xerial:sqlite-jdbc`, with WAL mode, `synchronous=NORMAL`,
`foreign_keys=ON`, and a `busy_timeout`. It's a single file accessed in the process, with no database
server. For backups, use Litestream, which requires no code changes.

At startup, every table is loaded into the identity map, and references are resolved against records
that are already loaded. That's why the load order is part of the generated schema, instead of
something the application has to manage.

Only one process may write to the database. The framework detects writes by another process, but
doesn't support them. SQLite's `PRAGMA data_version` doesn't change for this connection's own commits,
but does change when another connection commits. So `jetlin-db` checks it before every write, and a
change makes the write fail with an error. An exclusive lock would prevent outside writes entirely,
but in WAL mode, it also blocks readers, including the backup tool. Detecting an outside write one
commit late is the better trade-off.

Transactions run one at a time for each database. A single JDBC connection can't run two transactions
at once, and running them one at a time also makes commits and snapshot applies happen in the same
order. That's what keeps last-write-wins in memory consistent with what's on disk. Reads aren't
serialized, and never block.

### Memory is the limit

All records are held in memory, in the same heap as the live session compositions. Two benchmarks
measure the two parts:

```
./gradlew :samples:teams:benchmark          # the record graph, with a large table
records:            20000 resident
graph:              1096 bytes per record (20 MB total)

./gradlew :samples:demo:benchmark           # sessions; PAGE=real for the application's own page
page:               synthetic          | the application's todo list
nodes per session:  113                | 42
live:               129 kB per session | 65 kB per session
```

A record in memory costs about 1.1 kB. That was measured for a `Todo` with four columns, one
reference, and strings of typical length. The figure stays nearly constant between 2,000 and 20,000
records, so 100,000 records would take about 110 MB, and a million about 1 GB. Memory becomes a real
concern somewhere in the hundreds of thousands of records, not the thousands.

A session costs roughly what its page costs, about 1.5 kB per node in both measurements. That cost is
the virtual DOM and the composition around it, and the database adds nothing to it. So the size of
what a page renders matters more than the size of the collection. Each record is counted once in the
graph, then again in each session, for each record that the session renders.

Reading a policy-filtered collection does add to a composition's read set. The policy runs for every
record, so a page that filters a large table subscribes to cells in every record it scanned. The
measured cost is about 30 bytes per scanned record per session. That's a real cost, but about an order
of magnitude less than rendering the record.

If memory ever becomes a problem, there's a way out that doesn't change the model. A cell that starts
out empty can render a placeholder instead of blocking, so rarely used tables could move out of
memory. Keeping everything in memory is an optimization, not a requirement of the design. The first
version doesn't do this for the database, because a SQLite read that takes microseconds shouldn't make
a placeholder flicker on screen. That kind of cell does exist, as `jetlin.runtime.Fetch`, and
applications use it for data they don't own. `docs/architecture.md` §8 describes it.

## 7. Migrations

KSP writes the schema that the entities declare into the build output. `db/schema.json` is the copy
checked into the repository. The migration tasks compare the two:

```
./gradlew dbDiff --name=share_todos_by_team   # writes db/migrations/0002_share_todos_by_team.sql
./gradlew dbMigrate                           # applies pending migrations
./gradlew dbVerify                            # fails if the entities and db/schema.json differ (CI)
```

A migration is a SQL file that's meant to be read and, if needed, edited. Files are named like
`0001_what_it_does.sql`, applied in order, and recorded in a `jetlin_migrations` table. The SQL in the
file is exactly what runs.

SQLite's `ALTER TABLE` can add, drop, and rename columns, and rename tables. Changing a column's type,
nullability, or foreign key requires creating a new table, copying the rows, dropping the old table,
and renaming the new one: the twelve-step procedure in SQLite's documentation. The generator writes
that SQL. The runner handles the steps that need a live connection:

- It turns off foreign keys during the migration, because the rebuild drops a table that other tables
  reference.
- It runs `PRAGMA foreign_key_check` before committing, so a new constraint that orphans existing rows
  fails the migration, instead of producing a warning.
- It checks that every index, trigger, and view that existed before still exists afterward. A rebuild
  drops everything attached to the old table, and the generator can't know which of these objects a
  given database has. So the runner detects the loss and stops, naming what was lost. A view over a
  rebuilt table is the most common case.

A migration that destroys data contains a marker line, and `dbMigrate` refuses to run it until someone
deletes the line. Deleting a line in the file shows up in code review permanently. A `--force` flag
would leave no trace once typed.

`Db.open` creates any missing tables, and verifies the existing ones. A missing column, a mismatched
type or nullability, a missing foreign key, or a stored column that no entity declares all make
startup fail, with the table and column named. That turns a forgotten migration into a startup error,
instead of a failed insert during a deployment.

## 8. What's missing

These gaps are listed in order of how likely each one is to block a release.

### Holding a reference grants access

This was a deliberate choice. See [§4](#holding-a-reference-grants-access). The leak detector makes
leaks detectable during development, but can't prevent them.

### Visibility changes through relations aren't propagated on write

Moving a project to another team changes who can read its todos, but only the project's own policy
runs for that write. Nothing declares that a todo's visibility depends on its project's team, so the
change doesn't propagate.

Reads are always correct: the next time a todo's policy runs, it gives the right answer. What's
missing is invalidation, and the problem shows up only on a page that was already open when the
project moved. Fixing it requires policies to declare what their visibility depends on.

### No second layer of enforcement

SQLite has no row-level security, so the framework's policies are the only enforcement. Arbitrary
Kotlin policies can't be translated to Postgres row-level security, so an application that later needs
a database-level layer would have to write one separately.

### Memory is the limit

See [§6](#memory-is-the-limit).

### No indexes

Every ad hoc query is a linear scan over in-memory objects. That's fine at the intended scale. Revisit
it based on measurements, not guesses.

### Reference cycles can't be loaded

References are resolved against records that are already loaded, so two entities that reference each
other with non-null references, such as `User.team` and `Team.owner`, are a build error that names
both. A second pass that fills in nullable references after loading would fix this without changing
the model.

### A single process only

The in-memory graph belongs to one process. A second node would hold its own copy, and the two would
drift apart. This is a harder obstacle to running on several nodes than the session store, and
`architecture.md` §13 lists it next to the session store.

### Navigating to a route for one record shows the route's fallback title

When the user navigates within a session, the title is sent in the navigation message, which is built
from the route table before the new view has looked up its record. Page loads and waking from
hibernation show the correct title. Fixing this requires a protocol change.

## 9. Modules

```
:jetlin-db          Record, cells, the identity map, View, Policy, the gate, transactions, SQLite
:jetlin-db-ksp      the processor: tables, column objects, drafts, policy-checked accessors, schema snapshot
:jetlin-db-gradle   dbDiff, dbMigrate, dbVerify, and the migration engine they share
:samples:teams      a sample with several users, using all three access patterns
```

`Record`, `Policy`, `View`, `Id`, and the cell delegate contain nothing specific to databases: no SQL,
connections, transactions, or tables. That's deliberate. The read side and the authorization model
could also apply to data that isn't in a database, and keeping the boundary clean now means that
extracting them later would be a matter of moving files, not redesigning.

`:jetlin-db` depends on `:jetlin-runtime` for the snapshot system, and not on `:jetlin-html`. Route
guards are part of routing, not storage, so `Access`, `Guard`, `Principals`, and `Guarded` live in
`:jetlin-html`. They also work for applications whose principal isn't a stored record.
