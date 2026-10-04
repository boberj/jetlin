# jetlin-db

`jetlin-db` is the storage layer for Jetlin applications. Entities are ordinary Kotlin objects kept in
memory, backed by Compose snapshot state, and stored in SQLite. Reading a field subscribes the
composable that read it. Writing a field commits the change to disk, then recomposes every session
that read it. You declare who can see and change each entity's records, usually with rules that
read like sentences, and the framework enforces them whenever application code reads or writes.

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

An entity is a Kotlin class whose objects are stored in the database. Each stored object is a
*record*. Every entity also declares a *policy*: the rules that decide who can see its records and
who can change them.

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

    companion object : Policy<Todo, User> by policy({
        principal() equalTo record(Todo::owner) implies canEdit()
    })
}
```

A column is either a delegated property or a primary-constructor property. A plain `var` on an entity
is a compile error. Nothing would record writes to it, so a change would appear on screen but never
reach the database, and nobody would notice until a restart lost it.

From this declaration, KSP generates the table definition, a `Todos` object that holds the column
objects, a `TodoDraft` type for writes, the policy-checked accessors, and a schema snapshot for the
migration tooling. An `@Entity` without a policy fails the build.

### What a policy does

Most applications show different data to different people. Alice sees her own todos, not Bob's. Bob
can see a todo that Alice shared with his team, but he can't edit it. Only an admin can archive one.
Rules like these are called *access control*, and a policy is where an entity's access control lives.

You write the rules once, on the entity, and the framework applies them everywhere. You don't check
permissions in your pages. Every way of getting records goes through the policy, and so does every
write:

| When your code…                          | the policy decides…                                     |
|------------------------------------------|---------------------------------------------------------|
| reads `db.todos` or follows a relation   | which records it contains                               |
| looks up a record by ID                  | whether it finds the record or gets `null`              |
| calls `db.todos.add(todo)`               | whether the new record can be stored                    |
| calls `todo.update { … }`                | whether the change is allowed                           |
| calls `todo.delete()`                    | whether the record can be deleted                       |

The rules are always about a *principal*: the signed-in user that the code is acting for. §3 shows
how the principal gets into a page. In the rules, "the principal" always means that user.

When a policy refuses something, two different things happen:

- **A refused read hides the record.** A record the principal can't see is missing from every list,
  and a lookup returns `null`, exactly as if it didn't exist. An error would tell them that it does.
- **A refused write throws `AccessDenied`.** Nothing is saved. The message says who was refused, what
  they tried, and which conditions would have allowed it:

  ```
  User#2 may not change Todo#7: archived can only be changed if the principal is an admin
  ```

  A well-built page never offers a write the principal can't make, so this exception usually means a
  bug in a page, or someone sending events that the page never showed them. "Ask before you offer a
  control" in §4 shows how to avoid offering one.

### Write a policy from grants

`policy { }` builds a policy from *grants*. A grant is one line that says: if this condition holds,
the principal can do this.

```kotlin
principal() equalTo record(Todo::owner) implies canEdit()
```

Read it as "if the principal is the todo's owner, they can edit it". The part before `implies` is a
*condition*. The part after it is a *permission*.

Two principles hold throughout:

- **Nothing is allowed without a grant.** A policy with no grants lets nobody see, add, change, or
  delete anything.
- **Grants add up.** If two lines grant the same thing, either condition is enough. That's how you
  write "or": as two lines.

This section builds the policy for `samples/teams`' `Todo` one grant at a time.

#### Let the owner edit a todo

```kotlin
companion object : Policy<Todo, User> by policy({
    principal() equalTo record(Todo::owner) implies canEdit()
})
```

A condition compares values. Two values start every condition:

- `principal()` is the principal: the user the application is acting for.
- `record(Todo::owner)` is a field of the todo the framework is asking about: here, its owner.

`equalTo` holds when the two are the same user. `canEdit()` lets the principal add, change, and
delete todos.

The framework checks the grant at every point where it matters, so this one line refuses all of
these:

| Who     | Tries to                                      | Result                                                               |
|---------|-----------------------------------------------|----------------------------------------------------------------------|
| Bob     | change the title of Alice's todo              | Refused: it can only be updated if the principal is its owner        |
| Bob     | delete Alice's todo                           | Refused: it can only be deleted if the principal is its owner        |
| Alice   | add a todo whose owner is Bob                 | Refused: it can only be created if the principal is its owner        |
| Alice   | change a todo's owner from herself to Bob     | Refused: afterwards, it could only be updated if the principal is its owner |
| Bob     | see Alice's todo in `db.todos`                | It isn't there                                                       |

The fourth row is the one that's easy to miss if you check permissions by hand. A check that only
asks "is Alice the owner?" before the change allows it, because she is. The framework also checks
the todo as it would be *after* the change, so nobody can change a record into one they couldn't
change. The last row follows from the others: a principal who can change or delete a record can
always see it.

#### Grant each action separately

`canEdit()` is shorthand for three permissions, which you can also grant separately:

| Permission      | Lets the principal…     | Checked against                                   |
|-----------------|-------------------------|---------------------------------------------------|
| `canCreate()`   | add records             | the new record                                    |
| `canUpdate()`   | change records          | the record before the change, and after it        |
| `canDelete()`   | delete records          | the record before it's deleted                    |
| `canRead()`     | see records             | each record, every time a list or lookup runs     |

For example, to let owners change their todos but only admins delete them:

```kotlin
principal() equalTo record(Todo::owner) implies canCreate()
principal() equalTo record(Todo::owner) implies canUpdate()
principal() map User::admin implies canDelete()
```

Principals who can update or delete a record can see it, so they don't need `canRead()` too. Adding
a record doesn't let the principal see it. That's useful for a feedback form that anyone can submit
and only admins can read. `anyone` is a condition that always holds:

```kotlin
anyone implies canCreate()
principal() map User::admin implies canRead()
```

#### Let admins edit every todo

Add another line:

```kotlin
companion object : Policy<Todo, User> by policy({
    val admin = (principal() map User::admin) describedAs "the principal is an admin"

    principal() equalTo record(Todo::owner) implies canEdit()
    admin implies canEdit()
})
```

`map` reaches a field of a value: `principal() map User::admin` is the principal's `admin` field. A `Boolean`
field is a condition on its own, which holds when the field is true.

Refusal messages describe conditions automatically. Without `describedAs`, this one would read "the
principal's admin is true". With it, Bob now sees "it can only be updated if the principal is its
owner, or if the principal is an admin". Naming a condition with `val` also lets you reuse it.

To require two conditions at once, join them with `and`, and put each comparison in parentheses:

```kotlin
(principal() equalTo record(Todo::owner)) and not(principal() map User::suspended) implies canEdit()
```

There's no `or`. Write two grants instead.

#### Share a todo with a team

A team member should see a todo that's shared with their team, without being able to change it:

```kotlin
principal() map User::team equalTo record(Todo::team) implies canRead()
```

Read it as "if the principal's team is its team, they can read it".

A missing value matches nothing. If a todo isn't shared (`team` is `null`), no team can see it. If a
user is on no team, they're a member of nothing. In particular, a user on no team can't see an
unshared todo, even though both teams are `null`. Hand-written Kotlin makes this mistake easily,
because `null == null` is true. Conditions never make it: a comparison with a missing value never
holds.

If users can belong to several teams, give them `var teams: Set<Team>`, and use `contains`:

```kotlin
principal() map User::teams contains record(Todo::team) implies canRead()
```

#### Let only admins archive a todo

```kotlin
admin implies canChange(Todo::archived)
```

A column permission does two things:

- **It lets the principal change that column on any record they can see**, even if they can't
  change the rest of it. With `admin implies canRead()` and this grant, but no `admin implies
  canEdit()`, admins could archive any todo without being able to change its title.
- **It makes grants like it the only way to change that column.** The owner's `canEdit()` no longer
  covers `archived`, so owners can change everything about their todos except whether they're
  archived. To let owners archive too, grant it to them as well:
  `principal() equalTo record(Todo::owner) implies canChange(Todo::archived)`.

The column is named with `Todo::archived`, and it must be a `var`. Setting a column to the value it
already has isn't a change, so `update { archived = archived }` needs no grant for `archived`. The
permission isn't checked when a record is added. To control the value a new record starts with, use
`onlyAllows`.

#### Limit the values a column can take

Without another rule, an owner could share a todo with any team, including teams they aren't on:

```kotlin
Todo::team.onlyAllows("the principal's own team") { team, principal ->
    team == principal.team || principal.admin
}
```

The lambda receives the new value and the principal, and returns whether the value is allowed. The
framework checks it when a todo is added, and whenever a change gives `team` a new value, whichever
grant allowed the change. The description completes the refusal message: "team can only be set to
the principal's own team".

The rule is never asked about `null`, so `team` in the lambda is a `Team`, never `null`. `null`
means "no team", which isn't a value to allow or forbid:

- Whether a todo can have no team is decided by the property's type. `var team: Team?` allows it.
  `var team: Team` wouldn't.
- Who can take a todo's team away is decided by the grants, like any other change. Here, anyone who
  can update a todo can unshare it with `update { team = null }`.

#### The complete policy

```kotlin
companion object : Policy<Todo, User> by policy({
    val admin = (principal() map User::admin) describedAs "the principal is an admin"

    principal() equalTo record(Todo::owner) implies canEdit()
    admin implies canEdit()
    principal() map User::team equalTo record(Todo::team) implies canRead()
    admin implies canChange(Todo::archived)
    Todo::team.onlyAllows("the principal's own team") { team, principal ->
        team == principal.team || principal.admin
    }
})
```

Read aloud, it's the specification: "If the principal is its owner, they can edit it. If the
principal is an admin, they can edit it. If the principal's team is its team, they can read it. If
the principal is an admin, they can change archived. Team can only be set to the principal's own
team."

### Two ways to write a grant

Every grant so far puts the condition first: `condition implies permission`. You can also put the
subject first, and call the permission on it:

```kotlin
companion object : Policy<Todo, User> by policy({
    val admin = usersWhere(User::admin, "the principal is an admin")

    userIn(Todo::owner).canEdit()
    admin.canEdit()
    membersOf(record(Todo::team), membership = User::team).canRead()
    admin.canChange(Todo::archived)
    Todo::team.onlyAllows("the principal's own team") { team, principal ->
        team == principal.team || principal.admin
    }
})
```

Read aloud: "Its owner can edit it. An admin can edit it. Members of its team can read it. An admin
can change archived."

This is the same policy as the one above, not a different kind. Two small pieces make it work:

- **Shorthand conditions.** `userIn`, `usersWhere`, and `membersOf` return ordinary conditions,
  already described for refusal messages:

  | Shorthand                                                  | Is the condition                                           |
  |------------------------------------------------------------|------------------------------------------------------------|
  | `userIn(Todo::owner)`                                      | `principal() equalTo record(Todo::owner)`                  |
  | `usersWhere(User::admin)`                                  | `principal() map User::admin`                              |
  | `membersOf(record(Todo::team), membership = User::team)`   | `principal() map User::team equalTo record(Todo::team)`    |
  | `membersOf(record(Todo::team), memberships = User::teams)` | `principal() map User::teams contains record(Todo::team)`  |

- **Permissions called on a condition.** `condition.canEdit()` is exactly
  `condition implies canEdit()`. Every permission has this form, including `canChange`,
  `canReassign`, and `canOffer`, and it works on any condition, not only the shorthands:
  `anyone.canRead()`.

So both styles add up the same way, refuse with the same messages, and can be mixed in one policy.
Which to use is a matter of reading:

- **Subject first** reads best for the common shapes: an owner, a flag on the principal, a group.
  Each line says who can do what.
- **Condition first** reads best when the condition is its own sentence: it compares something other
  than the principal's own fields, reaches through several fields, or joins conditions with `and` or
  `not`.

To call a permission on a condition you wrote out, put the condition in parentheses. A dot binds
more tightly than an infix word, so without them, `principal() equalTo record(Todo::owner).canEdit()`
calls `canEdit()` on the owner instead, and doesn't compile:

```kotlin
((principal() equalTo record(Todo::owner)) and not(principal() map User::suspended)).canEdit()
```

`membersOf` has two forms, for one group and for several, and the argument's name picks between
them. Write `membership =` for a field that holds one group, and `memberships =` for a field that
holds a collection.

The rest of this section writes grants condition first, because that shows every condition in full.
Each one can be written subject first.

### Hand a record to someone else

`canUpdate()` refuses any change after which the principal couldn't update the record. That's what
stops Alice from dumping a todo on Bob, but some applications need records to change hands. Two
permissions allow it.

#### Reassign to a candidate

Suppose a task belongs to whoever it's assigned to, and the assignee can pass it to a teammate:

```kotlin
@Entity
class Task(@Owner assignee: User, title: String) : Record() {
    var assignee: User by reference(assignee)
    var title: String by column(title)

    companion object : Policy<Task, User> by policy({
        val assignee = principal() equalTo record(Task::assignee)

        assignee implies canEdit()
        assignee implies canReassign(Task::assignee) { candidate ->
            candidate map User::team equalTo (principal() map User::team)
        }
    })
}

with(alice) { task.update { assignee = bob } }
```

The block says which candidates the task can go to. It receives `candidate`, the user the task
would be assigned to, as a value, and returns a condition about them: "the candidate's team is the
principal's team". `candidate` has the column's type, so the compiler checks that `User::team` is a
field of the candidate.

| Alice (team Acme) tries to            | Result                                                                                   |
|---------------------------------------|------------------------------------------------------------------------------------------|
| assign her task to Bob (Acme)         | Allowed. Bob can change it now, and Alice can't.                                         |
| assign it to Carol (Globex)           | Refused: assignee can only be reassigned if the candidate's team is the principal's team |
| add a task already assigned to Bob    | Allowed, because she could add it for herself and then reassign it                       |
| reassign Bob's task                   | Refused: it can only be updated if the principal is its assignee                         |

If Alice is on no team, the condition never holds, so she can't reassign her tasks to anyone.

A reassignment relaxes only the reassigned column. The framework checks the rest of the change as
if the task hadn't changed hands. If only members of a task's team could update it, reassigning the
task to Bob while moving it to a team Alice isn't on would still be refused.

Once a column has a `canReassign` grant, reassigning is the only way it gets a new value. `canUpdate()`
no longer covers it. That matters for grants such as `admin implies canUpdate()`. "The principal is
an admin" holds before and after any change, so without this rule, an admin could set a task's
assignee to anyone, even though nobody granted them reassignment. To let admins reassign tasks,
grant it to them explicitly. `canUpdate()` can still clear the column, setting it to `null`, because
that hands the task to nobody.

The candidate condition can compare the candidate with anything: the principal, as above, or the
record. To keep a task within its own team, whoever reassigns it:

```kotlin
assignee implies canReassign(Task::assignee) { candidate ->
    candidate map User::team equalTo record(Task::team)
}
```

The condition before `implies` says who can reassign, so it doesn't have to be the assignee. To let
admins triage tasks, moving any task to someone on their own team without being able to edit it:

```kotlin
admin implies canRead()
admin implies canReassign(Task::assignee) { candidate -> candidate map User::team equalTo (principal() map User::team) }
```

#### Offer, and let the recipient accept

To hand a document on only with the recipient's agreement, give it a field for pending offers:

```kotlin
@Entity
class Doc(@Owner owner: User, text: String) : Record() {
    var owner: User by reference(owner)
    var offeredTo: User? by reference()
    var text: String by column(text)

    companion object : Policy<Doc, User> by policy({
        val owner = principal() equalTo record(Doc::owner)

        owner implies canEdit()
        owner implies canOffer(Doc::owner, via = Doc::offeredTo)
    })
}

with(alice) { doc.update { offeredTo = bob } }                 // Alice offers it to Bob.
with(bob) { doc.update { owner = bob; offeredTo = null } }     // Bob accepts it.
```

Only the owner can set `offeredTo`: setting it offers the document, and setting it back to `null`
withdraws the offer. While a document is offered to Bob, he can see it, so he can decide. The only
change he can make is accepting: setting the owner to himself and clearing the offer, in one
`update { }`, and nothing else. Nobody can push a document onto someone else: Alice can offer it, but
only Bob can make it his.

As with `canReassign`, accepting an offer becomes the only way the owner changes. Even a principal
whose `canUpdate()` grant holds for every document, such as an admin, can't change it directly.

### Shut someone out of everything

`alwaysRequires` adds a condition to every check: reading, adding, changing, and deleting. Nobody
can see or change a record unless it holds, whatever the grants say:

```kotlin
alwaysRequires(not(principal() map User::suspended))
```

For a change, the framework checks it before and after, so a condition about the record, such as
`alwaysRequires(principal() map User::team equalTo record(Doc::team))`, also stops a principal from moving a
record out of their own team.

### Reference

Values:

| Value                          | Means                                                       |
|--------------------------------|-------------------------------------------------------------|
| `principal()`                  | the principal                                               |
| `record(Todo::owner)`          | a field of the record the framework is asking about         |
| `value map User::team`         | a field of another value                                    |
| `candidate`                    | in a `canReassign` block: who the record would go to        |

Conditions:

| Condition                      | Holds when                                                  |
|--------------------------------|-------------------------------------------------------------|
| `a equalTo b`                  | `a` and `b` are the same, and neither is missing            |
| `a contains b`                 | the collection `a` includes `b`                             |
| a `Boolean` value              | it's true                                                   |
| `a and b`                      | both hold                                                   |
| `not(a)`                       | `a` doesn't hold                                            |
| `anyone`                       | always                                                      |
| `a describedAs "…"`            | `a` holds; refusal messages use the text                    |

Permissions, granted with `condition implies permission`:

| Permission                         | Lets the principal…                                          | Checked                                      |
|------------------------------------|--------------------------------------------------------------|----------------------------------------------|
| `canRead()`                        | see records                                                  | on every read                                |
| `canCreate()`                      | add records                                                  | on add, against the new record               |
| `canUpdate()`                      | change records, except columns with their own permissions, and columns that `canReassign` or `canOffer` hand on | before and after every change |
| `canDelete()`                      | delete records                                               | before deleting                              |
| `canEdit()`                        | add, change, and delete records                              | as the three above                           |
| `canChange(column)`                | change one column on records they can see                    | when the column gets a new value             |
| `canReassign(column) { … }`        | hand a record to a candidate the block accepts               | when the column gets a new value, and on add |
| `canOffer(column, via)`            | offer a record, which the recipient can accept               | when either column gets a new value          |

Shorthand conditions, for writing grants subject first:

| Shorthand                                                  | Holds when                                                  |
|------------------------------------------------------------|-------------------------------------------------------------|
| `userIn(Todo::owner)`                                      | the principal is the user in that field                     |
| `usersWhere(User::admin, "…")`                             | the principal's `Boolean` field is true                     |
| `membersOf(record(Todo::team), membership = User::team)`   | the principal's group is that group                         |
| `membersOf(record(Todo::team), memberships = User::teams)` | the principal's groups include that group                   |

Every permission can be written as `condition implies canX(…)` or as `condition.canX(…)`.

Rules that limit the grants:

| Rule                                   | Means                                                    | Checked                                      |
|----------------------------------------|----------------------------------------------------------|----------------------------------------------|
| `column.onlyAllows(text) { … }`        | The column can only take values the lambda allows. Never asked about `null`. | On add, and when the column gets a new value |
| `alwaysRequires(condition)`            | Nobody for whom it doesn't hold can use any record.      | Everywhere, before and after a change        |

### Mistakes the policy catches

Most mistakes in a condition are compile errors:

```kotlin
// A bare property, without saying whose field it is:
principal() equalTo Todo::owner implies canEdit()
// error: Inapplicable candidate(s): val owner: User

// Two comparisons joined with `and`, without parentheses:
principal() equalTo record(Todo::owner) and principal() map User::team equalTo record(Todo::team) implies canEdit()
// error: Argument type mismatch: actual type is 'Value<Todo, User, User>', but 'Value<Todo, User, Boolean>' was expected.

// A column permission for a column that can't change:
principal() equalTo record(Todo::owner) implies canChange(Todo::owner)
// error: Inapplicable candidate(s): val owner: User
```

The second one happens because Kotlin reads every infix word, such as `equalTo`, `and`, `map`, and
`implies`, from left to right with the same priority. The compiler refuses the result, so put each
comparison in parentheses when you join them.

Two mistakes compile, and the policy refuses them the first time it's used, with an error that says
what to fix:

- **A permission without a condition.** `canDelete()` on a line of its own grants nothing. The error
  says to write it as `condition implies canDelete()`.
- **Comparing values of different kinds.** `principal() map User::team equalTo record(Todo::title)` compiles,
  because Kotlin treats a team and a string both as `Any`. The first time the policy compares a real
  team with a real title, it throws instead of quietly never matching.

### Write a policy by hand

Two other ways to write a policy suit some entities better.

If only the owner can do anything with a record, `owned` is the shortest:

```kotlin
companion object : Policy<Note, User> by owned(Note::owner)
```

If grants don't fit, implement `Policy` yourself. Only `canWrite` is required. Everything else has
a default that builds on it:

```kotlin
companion object : Policy<User, User> {
    // Users can change their own record, and admins can change anyone's.
    override fun canWrite(record: User, principal: User) = record == principal || principal.admin

    // Everyone can see every user.
    override fun canRead(record: User, principal: User) = true

    // But only admins can change what someone is allowed to do.
    override fun canWrite(record: User, column: Column<User>, principal: User) = when (column) {
        Users.admin, Users.team -> principal.admin
        else -> canWrite(record, principal)
    }
}
```

| Method                             | Default                | Asked when                                          |
|------------------------------------|------------------------|-----------------------------------------------------|
| `canWrite(record, principal)`      | none: you write it     | a change starts, and a record is deleted            |
| `canRead(record, principal)`       | `canWrite`             | a record is read                                    |
| `canWrite(record, column, principal)` | `canWrite`          | a change gives that column a new value              |
| `canCreate(record, principal)`     | `canWrite`             | a record is added, and after every change           |
| `canDelete(record, principal)`     | `canWrite`             | a record is deleted                                 |
| `canChange(change, principal)`     | the three checks below | every `update { }`                                  |

By default, `canChange` allows a change if all of these hold:

1. The principal can change the record as it is now (`canWrite`).
2. The principal can change each column that gets a new value (`canWrite` for the column).
3. The principal could add the record as it would be afterwards (`canCreate`).

The third check is what stops Alice from changing a todo's owner to Bob: she couldn't have added a
todo owned by Bob. It also makes `canCreate` the place for rules about values:

```kotlin
// A todo can only be shared with the principal's own team.
override fun canCreate(record: Todo, principal: User) =
    canWrite(record, principal) && (record.team == null || record.team == principal.team)
```

Override `canChange` only when a rule needs the old and new values together. It receives a
`Change`: the record as it is now, the new values, and `afterwards { }`, which runs code against the
record as it would be after the change, then discards it:

```kotlin
// Tasks can be marked done, but never undone.
override fun canChange(change: Change<Task>, principal: User) =
    !(change.record.done && !change.newValue(Task::done)) && super.canChange(change, principal)
```

When you override it, check both the old and the new state, for example by calling
`super.canChange`. A rule that only looks at the new values lets anyone who holds a record rewrite it
into one they're allowed to have, for example by setting its owner to themselves.

### Keep policies fast and free of side effects

Policies run while pages render. Reading `db.todos` runs the policy for every todo, every time the page
reads the list, and the framework caches nothing, because a cached answer could outlive the data it
was based on. So a policy must be quick, and must only read: no I/O, no suspending calls, and no
changes to any state. A `:conventions` test checks for blocking calls in policies.

This is also why a policy can be ordinary Kotlin. All records are in memory, so a rule doesn't need
to be translated to SQL, and there's no second version of it to keep consistent with the schema. §5
describes the other benefit: when the data a rule reads changes, open pages update by themselves.

### Test your policies for loopholes

A policy can be wrong in ways that ordinary tests don't catch, because ordinary tests check the cases
you thought of. `jetlin-db-testing` looks for the cases you didn't. It builds many small, random
databases, tries everything each user could do in them, and reports patterns that are usually
mistakes, such as a user who can make themselves an admin.

Add the module to your tests:

```kotlin
dependencies {
    testImplementation(project(":jetlin-db-testing"))
}
```

Then write one test that checks every policy in your schema:

```kotlin
@Test
fun `the policies have no loopholes`(): Unit {
    checkPolicies(JetlinSchema)
}
```

`JetlinSchema` is the object KSP generates, which lists every entity and its policy. The test fails
with an `AssertionError` that describes each problem it found.

#### What it looks for

| Check                  | Finds                                                                   | Example                                            |
|------------------------|-------------------------------------------------------------------------|----------------------------------------------------|
| `SelfEscalation`       | A user gains access to other records by changing, adding, or deleting a record | A user sets their own `admin` field          |
| `TakeOver`             | A user gains control of a record by changing it                          | A user sets a document's owner to themselves       |
| `PushOntoOthers`       | One user's action gives another user access, without their involvement   | Alice makes Bob the owner of a todo                |
| `NotCreatable`         | A change produces a record the user couldn't have added directly         | Editing a todo that's shared with someone else's team |
| `MissingValuesMatch`   | Access depends on two missing values counting as the same                | A user on no team sees todos shared with no team   |
| `DeadGrant`            | A grant whose condition never holds, so it does nothing                  | A condition that compares a team with a name       |
| `Orphan`               | A change leaves a record nobody can see                                  | Archiving a note hides it from its owner too       |
| `CannotUndo`           | A user can make a change they can't reverse                              | An admin removes their own admin role              |

Each world is a real database with a few records of every entity. Values come from small pools,
such as "a" or "b" for text and `true` or `false` for a flag, plus `null` for a nullable field, so
that collisions, such as two users on the same team, happen often. In each world, the check tries
every change, addition, and deletion that each user could make to the records they can reach.

A user who can already do the same to every other record of an entity doesn't count as gaining
anything. An admin who can edit every todo doesn't gain access when someone adds one.

#### Read a report

When the check finds a problem, it shrinks the world to the fewest records that still show it, and
describes that world step by step:

```
Self-escalation: User: changing admin can give a principal access to other records
  A principal gains access to other records by changing, adding, or deleting a record.

  In this world:
    User1(name = "a", email = "a", admin = false, team = null)
    User2(name = "a", email = "a", admin = false, team = null)
  User2 changes User2: admin false → true. The policy allows it.
  Afterwards, User2 can also: update User1, delete User1, change User1.name, change User1.admin

  If this is intended, allow it:
    allow(Check.SelfEscalation, User::admin, because = "…")
```

Read it from the top: the world, what one user did, and what that let them do. Here, the fix is a
column rule that lets only admins change `admin`.

The same seed always builds the same worlds, so a failure always reproduces.

#### Allow what's intended

Not every finding is a mistake. Sharing a todo with a team is supposed to give the team access. When
a finding is intended, allow it, and say why:

```kotlin
@Test
fun `the policies have no loopholes beyond the intended ones`(): Unit {
    checkPolicies(JetlinSchema) {
        allow(Check.PushOntoOthers, Todo::team, because = "sharing a todo with a team is the point")
        allow(Check.CannotUndo, User::admin, because = "an admin who gives up the role needs another admin to restore it")
    }
}
```

You can allow a check for one column, as above, for a whole entity (`allow(Check.PushOntoOthers,
Announcement::class, because = …)`), or everywhere (`allow(Check.CannotUndo, because = …)`). The
reasons are worth writing well: together, they describe what your policies let users do to each
other. `samples/teams`' `PoliciesTest` has seven of them, and it also shows the check catching the
sample's old self-admin loophole.

#### State your own rules

The built-in checks look for patterns that are usually wrong. To check something specific to your
application, state it:

```kotlin
checkPolicies(JetlinSchema) {
    // Checked for every action a user is allowed to take, in every world.
    never("a non-admin archives a todo") { event ->
        event.changes(Todo::archived) && !(event.principal as User).admin
    }

    // Checked for every todo and every user, in every world.
    always(Todo::class, User::class, "an owner can see their own todos") { todo, user, access ->
        todo.owner != user || access.read
    }
}
```

An `Event` says what a user did (`Action.Create`, `Update`, or `Delete`), who did it, and the
record's values before and after. `Access` says what one user can do with one record.

#### Compare two policies

When you rewrite a policy, for example, from a hand-written one to grants, check that it still
decides the same way:

```kotlin
@Test
fun `the rewritten todo policy decides like the old one`(): Unit {
    comparePolicies(JetlinSchema, Todos.table, old = LegacyTodoPolicy, new = Todos.policy).assertSame()
}
```

Both policies answer the same questions in the same worlds. Each difference is reported with the
smallest world that shows it:

```
Asked "can User1 read Todo1?", the old policy says no, and the new one says yes.
```

If you're changing a policy on purpose, `comparePolicies(…).differences` lists exactly what changed.

#### Tune the worlds

The defaults find most problems in about a second:

```kotlin
checkPolicies(JetlinSchema) {
    worlds = 100                  // Default: 30. More worlds find rarer problems.
    seed = 42                     // Default: 1. Change it to explore different worlds.
    maxActionsPerWorld = 300      // Default: 150.

    entity(User::class) {
        count = 4                                      // Default: 3 for principals, 2 otherwise.
        values(User::role, "viewer", "editor", "owner") // Default: "a" or "b" for text.
    }
}
```

Choose values when a policy compares a field with particular values, such as a role. Otherwise, the
check never tries the values that matter. The values you give are used exactly, so include `null` if
the field should sometimes be empty.

#### Limits

- The check only knows what the patterns and your rules tell it. A policy that consistently does the
  wrong thing passes, unless a rule you state says otherwise.
- Worlds are small. Problems that need many records, or values outside the pools, can be missed.
  That's rarely a problem for access rules, which usually go wrong with two users and one record.
- `DeadGrant` only works for policies built with `policy { }`, because a hand-written policy can't be
  looked inside.
- The check never deletes a record that another record references. The database would refuse that
  anyway.

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
at compile time.

The block also lets the policy judge a change as a whole. Inside the block, you set fields on a
draft, not on the todo. The draft holds the new values back, and reading a field in the block returns
the value you set:

```kotlin
todo.update {
    title = "$title (edited)"   // reads the current title
    title = "$title again"      // reads "… (edited)", the value set on the line above
}
```

When the block finishes, the framework asks the policy about the whole change at once: the todo as
it is, the new values, and the todo as it would be afterwards. If the policy allows it, the values
are saved together. If not, `update` throws `AccessDenied`, and nothing is saved, not even the
columns the policy would have allowed on their own. A change that's partly saved, shown on screen
but not all stored, is exactly the failure this design prevents.

Because the policy sees the whole change, the order of the assignments doesn't matter.
`update { done = true; title = "x" }` and `update { title = "x"; done = true }` get the same answer.

A record that isn't stored yet isn't checked by `update`. Nobody else can have it, and `add` checks
it when it's stored. That lets you set up a new record's fields before you add it.

### Ask before you offer a control

A page shouldn't offer a button that the policy would refuse. Ask the policy instead of repeating its
rules in the page. Next to `update { }` and `delete()`, KSP generates functions that ask the same
questions, with the principal taken from context:

| Function                  | Asks                                                        |
|---------------------------|-------------------------------------------------------------|
| `todo.canUpdate()`        | Can the principal edit this todo at all?                    |
| `todo.canUpdate(column)`  | …and change this column of it?                              |
| `todo.canUpdate { … }`    | Would this exact change be allowed? Nothing is saved.       |
| `todo.canDelete()`        | Can the principal delete it?                                |

```kotlin
// Show the archive checkbox, but only let admins use it.
Input({
    attr("type", "checkbox")
    disabled(!todo.canUpdate(Todos.archived))
    onChange { todo.update { archived = !archived } }
})

// Offer a delete button only to principals who can delete.
if (todo.canDelete()) Button({ onClick { todo.delete() } }) { Text("Delete") }
```

`canUpdate()` and `canUpdate(column)` don't know which value you're going to set, so they can't
answer questions such as "can Alice share this todo with the Globex team?", which depend on the
value. `canUpdate { }` can: it runs the block against a draft, asks the policy exactly as `update`
would, and throws the draft away.

```kotlin
// Offer only the people the task can be assigned to.
val candidates = teammates.filter { person -> task.canUpdate { assignee = person } }
```

The block should only set fields. It runs, but nothing it sets is saved.

The answers are live, like everything else. They read the same fields as the policy, so if an admin
loses their role while a page is open, the page's controls update without a reload.

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
4. There's exactly one way to bypass the checks. It's called `unsafe`, so every use is easy to search
   for and stands out in review.
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
