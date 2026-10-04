# jetlin-db: judge writes by their before and after state

This plan is a proposal. None of it is implemented. It replaces the way `jetlin-db` decides whether a
write is allowed, and leaves reads, grants, and pages as they are.

- Audience: Claude Code, working in the Jetlin repository.
- Background reading: `docs/db.md` §2 and §4, then `jetlin-db/src/main/kotlin/jetlin/db/Policy.kt`,
  `Rules.kt`, `Gate.kt`, `Change.kt`, `Draft.kt`, and `Db.kt`.
  [`db-framework-plan.md`](db-framework-plan.md) §2.3 explains why transactions are snapshots,
  which is what this plan builds on.

## 1. The problem

A policy decides a write today through several separate questions, each with a default that builds
on another:

| The application does   | The framework asks                                                              |
|------------------------|---------------------------------------------------------------------------------|
| `db.todos.add(todo)`   | `canCreate(record)`, which defaults to `canWrite(record)`                       |
| `todo.update { … }`    | `canChange(change)`, which defaults to `canWrite(record)`, then `canWrite(record, column)` for each changed column, then `canCreate` on the record as it would be afterwards |
| `todo.delete()`        | `canDelete(record)`, which defaults to `canWrite(record)`                       |

Answering "as it would be afterwards" takes machinery that exists only for policies:

- `update { }` writes to a generated `Draft`, which holds the new values back instead of writing
  them (`Draft.kt`).
- `Change.afterwards { }` takes a nested snapshot, stores the draft's values in it, runs the check,
  and throws the snapshot away (`Trials` in `Change.kt`). `RulesPolicy.refuseChange` in `Rules.kt`
  does this once for each change, and a variant with `keeping = …` checks a change as if some of its
  columns hadn't moved.
- `Gate.update` (`Gate.kt`) judges each `update { }` call on its own, in the middle of the
  transaction, before the block's later writes have happened.

Three costs follow:

1. **Hand-written policies are easy to get wrong.** Six methods with chained defaults are hard to
   reason about, and an override of `canChange` that looks only at the new values lets anyone who
   holds a record rewrite it into one they're allowed to have. The `Policy` KDoc warns about this
   instead of preventing it.
2. **A transaction is judged in pieces.** A transaction that moves every todo out of a project and
   then deletes the project is judged one step at a time, so a rule about the result, such as "a
   project with todos can't be deleted", can see an intermediate state that never reaches the
   database.
3. **The simulation duplicates something the framework already has.** `db.transact` already runs
   inside a mutable snapshot whose contents are exactly "the database as it would be afterwards".

## 2. The design

### 2.1 Before and after are two snapshots

`Db.transact` takes a read-only snapshot just before it takes the mutable one:

```kotlin
public fun <T> transact(block: () -> T): T = writeLock.withLock {
    val before = Snapshot.takeSnapshot()          // the database as it was
    val after = Snapshot.takeMutableSnapshot()    // the database as the block leaves it
    try {
        val result = after.enter {
            Transactions.running(writes) {
                block().also {
                    judge(writes, before)         // new: every touched record, once, at the end
                    commit(writes)
                }
            }
        }
        after.apply().check()
        result
    } catch (t: Throwable) {
        after.dispose()
        throw t
    } finally {
        before.dispose()
    }
}
```

`writeLock` already serializes transactions, and stored records' cells are only written inside a
transaction, so the read-only snapshot is exactly the state the block started from. Reading a
record's fields inside `before.enter { }` gives its old values. Reading them anywhere else in the
transaction gives its new ones.

### 2.2 One question for every write

```kotlin
public class RecordChange<T : Record> internal constructor(
    public val record: T,
    public val before: Values<T>?,        // null: the record is being created
    public val after: Values<T>?,         // null: the record is being deleted
    public val columns: Set<Column<T>>,   // the columns whose value changes; empty for create and delete
) {
    /** Runs [read] against the database as it was when the transaction started. */
    public fun <R> before(read: () -> R): R
}

public interface Policy<T : Record, P : Principal> {
    public fun canRead(record: T, principal: P): Boolean
    public fun allows(change: RecordChange<T>, principal: P): Boolean
}
```

`Values<T>` is an immutable copy of one record's columns. KSP generates a typed class for each entity,
such as `TodoValues(owner, title, done, team, archived)`, so a hand-written policy reads
`change.before?.owner` with no casts.

`judge` builds one `RecordChange` for each record in the `WriteSet` (`Writes.kt`) and asks the
record's policy once. Inserts get `before = null`, deletes get `after = null`, and updates get both,
with `columns` taken from the cells the transaction wrote, minus those set back to their old value.
A refusal throws `AccessDenied`, which rolls back the whole transaction as it does today.

`canRead` doesn't change. Reads have no before or after, and filtering collections stays a check on
each record, every time, with no caching.

### 2.3 What a hand-written policy looks like

The same rules as `samples/teams`' `Todo`, written out:

```kotlin
companion object : Policy<Todo, User> {
    override fun canRead(record: Todo, principal: User) =
        record.owner == principal || principal.admin || (record.team != null && record.team == principal.team)

    override fun allows(change: RecordChange<Todo>, principal: User): Boolean {
        val b = change.before
        val a = change.after
        val owns = { v: TodoValues -> v.owner == principal }
        return when {
            b == null -> owns(a!!) && teamAllowed(a, principal)                       // create: only as yourself
            a == null -> owns(b) || principal.admin                                   // delete
            else ->                                                                    // update
                (owns(b) && owns(a) || principal.admin) &&
                    (Todos.archived !in change.columns || principal.admin) &&
                    (Todos.team !in change.columns || teamAllowed(a, principal))
        }
    }

    private fun teamAllowed(v: TodoValues, p: User) = v.team == null || v.team == p.team || p.admin
}
```

Both sides are in front of the author, so "the owner can't hand the todo to someone else" is the
visible `owns(b) && owns(a)`, not a consequence of a default three methods away.

### 2.4 Grants don't change

Policies built with `policy { }` keep their syntax. `RulesPolicy` implements `allows` instead of
today's methods, and evaluates each condition in the snapshot the permission calls for:

| Grant                         | Judged against                                                               |
|-------------------------------|------------------------------------------------------------------------------|
| `canCreate()`                 | `after`, when `before == null`                                               |
| `canUpdate()`                 | `before` and `after`, for the columns no column grant covers                 |
| `canDelete()`                 | `before`, when `after == null`                                               |
| `canChange(column)`           | `before`, when that column is in `columns`, and the record must be readable  |
| `canReassign(column) { … }`   | the candidate in `after`; the rest of the change as if the column hadn't moved |
| `canOffer(column, via)`       | as today, from `before` and `after`                                          |
| `column.onlyAllows(…) { … }`  | the new value, when the column is in `columns` or the record is created      |
| `alwaysRequires(…)`           | `before` and `after`                                                          |

Conditions such as `principal() equalTo (record() map Todo::owner)` read the live record, so judging
one against `before` means evaluating it inside `change.before { }`. No condition or permission
changes meaning, and refusal messages keep their wording.

`canReassign` is the one grant that still needs a state that never exists: the change with the
reassigned column put back. It keeps a nested snapshot for that case, reduced from today's general
`Trials` mechanism to this single use. See §7.

### 2.5 `update { }` and its previews

`update { }` stays. A property setter can't take a context parameter, so `todo.done = true` couldn't
require a principal at compile time, and that requirement is worth keeping (`docs/db.md` §4).

What changes is what the block writes to. It writes the record's cells directly, inside the
transaction's snapshot, instead of holding values in a draft. The generated draft types become thin
wrappers that exist only to scope the block, and their pending-value storage goes away.

The generated previews keep their signatures:

- `todo.canUpdate { … }` runs the block in a mutable snapshot nested in the current one, judges the
  result exactly as `transact` would, and disposes of the snapshot. Nothing is written, and no page
  sees the change.
- `todo.canUpdate()` and `todo.canUpdate(column)` ask whether *some* change could be allowed. They
  have no values to judge, so they keep today's meaning, computed from the grants: whether any
  `canUpdate` or column grant holds for the record as it is.

### 2.6 Relations

`after` holds the changed record's own columns. Following a reference from it, such as
`change.after?.team?.name`, reads the related record as it is inside the transaction, which is usually
what a rule means. `change.before { }` reads anything as it was when the transaction started, for the
rare rule that needs a related record's old state.

## 3. Behavior that changes

- **Refusals surface at the end of the transaction.** `AccessDenied` is thrown from `transact` after
  the block returns, not from the `update { }` call. In an event handler, both are inside the same
  `transact`, so the page sees the same result. Code that catches `AccessDenied` inside a transaction
  block to try something else stops working, and has to ask `canUpdate { }` first instead. A search
  of the repository finds no such code today.
- **Only the final state is judged.** A transaction can pass through states its policies would
  refuse, as long as it ends in one they allow. That's what allows a rule about several records,
  such as "a project with todos can't be deleted", to be judged correctly.
- **A record inserted and deleted in the same transaction is never judged.** It never reaches the
  database, and `WriteSet` already drops it.
- **Refusal messages name the record and columns**, as they do now. When one transaction is refused
  for several records, the message names the first, in `WriteSet` order.

## 4. What goes away

- `Trials` and `Change.afterwards`, except the narrow reassignment case in §2.4.
- `Change`, replaced by `RecordChange`.
- The default chain in `Policy`: `canWrite`, `canWrite(column)`, `canCreate`, `canDelete`, and
  `canChange` as overridable methods.
- Pending-value storage in `Draft`: `pendingValue`, `storePending`, and `Pending`.
- `Gate.explainChange`, which works out which default check failed for a hand-written policy. With
  one method there's nothing to work out, and the message says the policy refused the change.

## 5. Migration

Nothing outside this repository depends on `jetlin-db`, so the old interface can be removed instead
of deprecated. The work still goes in three phases, each ending with `./gradlew build` passing, so
that a change in what policies decide is caught when it happens.

### Phase 1: judge at commit, with today's policies

- `transact` takes the `before` snapshot and judges every touched record at the end, through an
  adapter that answers `allows` with today's methods: `canCreate` for inserts, `canDelete` for
  deletes, and `canChange` for updates, with `Change.afterwards` reading the transaction's snapshot
  instead of a trial.
- `Gate.update` stops judging in the middle of the transaction.
- Existing tests must pass unchanged, except tests that assert *where* `AccessDenied` is thrown.

### Phase 2: the new interface

- Add `RecordChange`, the generated `Values` classes, and `Policy.allows`.
- Port `RulesPolicy` to implement `allows`, following §2.4.
- Keep a frozen copy of today's `RulesPolicy` in `jetlin-db-testing`'s test sources as
  `LegacyRulesPolicy`, wrapped by the phase 1 adapter, and prove that the two decide alike for every
  policy in the repository:

  ```kotlin
  comparePolicies(JetlinSchema, Todos.table, old = legacy(todoRules), new = Todos.policy).assertSame()
  ```

- `checkPolicies(JetlinSchema)` in `samples/teams` must still pass with its current `allow(…)` list.

### Phase 3: remove the old machinery

- Delete what §4 lists, and the adapter and `LegacyRulesPolicy`.
- Rewrite the hand-written policies: `Team` and `User` in `samples/teams`, `owned()` in `Policy.kt`,
  and any in tests.
- Update the `Policy` KDoc, `docs/db.md` §2 ("Write a policy by hand") and §4, and the policy
  examples in `README.md`.

## 6. Follow-ups this enables

These are out of scope here. They're listed because the `before` and `after` values that `judge`
computes are exactly what each one needs, so they cost little once this plan is done.

- **A transaction journal.** Each committed transaction's `RecordChange`s, with the principal and a
  timestamp, kept in memory for a limited time.
- **Per-user selective undo.** Undoing one journal entry applies its reverse to the current state as
  a new transaction by the same principal: delete what it inserted, restore what it deleted, and set
  each column it changed back to its `before` value, if that column still has the entry's `after`
  value. Otherwise someone changed it since, and the undo is refused. Another user's work done in
  between is kept, and the undo goes through `allows` like any other write.

## 7. Open questions

- **Is `change.before { }` needed in practice?** None of the repository's policies read a related
  record's old state. If none need it by phase 3, leave it out and add it when a rule does.
- **Can `canReassign` avoid its nested snapshot?** Judging "the rest of the change as if the column
  hadn't moved" needs that state to exist for conditions that read the live record. Evaluating
  conditions against `Values` instead of live records would remove it, but conditions that follow
  references would then need values for related records too.
- **What does the extra snapshot cost?** A read-only snapshot per transaction should be negligible
  next to a SQLite commit. Measure it with `./gradlew :samples:teams:benchmark` before and after
  phase 1.

## 8. Acceptance criteria

- `./gradlew build` passes after each phase.
- Phase 2: `comparePolicies(…).assertSame()` passes for every `policy { }` in the repository, and
  `checkPolicies(JetlinSchema)` passes for `samples/teams`.
- New tests in `jetlin-db`:
  - a create, an update, and a delete each reach `allows` with the right `before` and `after`;
  - a transaction that passes through a refused state and ends in an allowed one is committed;
  - a transaction that ends in a refused state is rolled back, and no page sees any of it;
  - `canUpdate { }` leaves no write behind, in the snapshot, the `WriteSet`, or the database.
- Reactive authorization still works: the tests named in `docs/db.md` §5 pass unchanged.

## 9. Decision log

| Date | Decision | Reasoning |
|---|---|---|
