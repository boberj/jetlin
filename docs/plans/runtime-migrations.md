# jetlin-db: apply migrations when the application starts

This plan is a proposal. None of it is implemented. It moves the migration runner from the Gradle
plugin into `jetlin-db`, so an application brings its own database up to date when it starts. It
also closes a gap in how a database's migration history begins.

- Audience: Claude Code, working in the Jetlin repository.
- Background reading: `docs/db.md` §6 and §7. Then `jetlin-db-gradle/src/main/kotlin/jetlin/db/gradle/`
  (`Migrations.kt` for the runner, `Sql.kt` and `Diff.kt` for the generator, and
  `JetlinDbPlugin.kt` for the tasks), and `Db.open`, `createSchema`, and `verifySchema` in
  `jetlin-db/src/main/kotlin/jetlin/db/Db.kt`.

## 1. The problem

### 1.1 A deployed application can't migrate its database

`./gradlew dbMigrate` is the only way to apply migrations. It runs Gradle, against a source checkout,
on the machine that has the database file. A deployed application has none of those. It's a
container image holding a JAR and a volume holding the database.

`Db.open` deliberately refuses a database whose columns don't match the entities (db.md §7), so the
first deployment after a schema change fails to start. That's the right failure, but there's no
supported way to avoid it. Each application has to invent one.

Pollster, the first application deployed with `jetlin-db`, hit this on its first schema change. It
now has its own 140-line `Migrations.kt` that applies `db/migrations/*.sql` at startup. It's
compatible with `dbMigrate`'s history table, but it's a copy of the runner without the runner's
checks for lost indexes, triggers, and views. Its Dockerfile copies the migration files into the
image, and an environment variable tells the app where they are. Every application that deploys
would write the same code, and each copy would drift from `dbMigrate` in its own way.

### 1.2 A database created by `Db.open` has no history

`Db.open` creates any missing table from the entities (`createSchema`), and records nothing in
`jetlin_migrations`. A database that started this way has the current schema, and no record of
which migrations produced it. When `dbMigrate` later runs against it, the first migration tries to
create tables that already exist, and fails.

That's the normal sequence in development: run the application, which creates `db/app.db`, then
change an entity and run `dbDiff` and `dbMigrate`. Nobody has hit it yet only because the samples
use a fresh temporary database on every run. Pollster hit it, and worked around it by guessing: "if
there's no history and the tables exist, record the first migration as applied." That guess is right
only when the database was created before the second migration existed.

## 2. Goals

1. An application calls `Db.open` as it does today, and the database it gets is up to date, whatever
   state it was in: empty, created by an older `Db.open` with no history, partly migrated, or
   current.
2. One migration engine. `dbMigrate` and the application run the same code, with the same checks,
   and write the same history.
3. Nothing to copy into an image, and no paths to configure. Migrations travel inside the JAR, like
   the entities they belong to.
4. Startup stays safe. A migration that destroys data still needs to be acknowledged in the file.
   A failed migration leaves the database exactly as it was, and the application doesn't start.

Non-goals: rollbacks (down migrations), and more than one process using the database. db.md §8 lists
"a single process only", and this plan doesn't change that.

## 3. The design

### 3.1 The engine moves into a library both sides can use

The runner (`Migrations`, `Migration`, `applyMigrations`, `statements`, the foreign key and
dependent-object checks, and `ACKNOWLEDGEMENT_MARKER`) moves out of the plugin into a plain Kotlin
module with no Gradle API: **`jetlin-db-migrate`**. It depends only on `sqlite-jdbc`. The generator
(`Sql.kt`, `Diff.kt`, `Schema.kt`) stays in the plugin, because it's only needed at build time.

Where the module lives is constrained by the build. `jetlin-db-gradle` is an included build, because
`:samples:teams` applies the plugin (see the comment in `settings.gradle.kts`). A project of the root
build can depend on a project of an included build, but the plugin can't easily depend on a project
of the root build that includes it. So the recommendation is:

- `jetlin-db-migrate` becomes a second project inside the `jetlin-db-gradle` build.
- The plugin depends on it directly.
- `:jetlin-db` depends on it by coordinates, which Gradle substitutes with the included build's
  project.

Phase 1 must confirm this works, including in Pollster's `includeBuild("../jetlin")` setup. If it
doesn't, the fallback is to compile the same source directory into both (`sourceSets.main.kotlin
.srcDir(...)`), which is uglier but has no dependency edges at all. Record which one was used in §9.

### 3.2 Migrations travel in the JAR

The plugin adds a `dbPackageMigrations` task, wired into `processResources`. It copies `db/migrations/`
into the JAR under `META-INF/jetlin-db/migrations/`, and writes an index next to them, `index.json`:

```json
{
  "migrations": [
    { "name": "0001_initial_schema.sql", "sha256": "…", "schemaAfter": "…" },
    { "name": "0002_lock_links_after_first_visit.sql", "sha256": "…", "schemaAfter": "…" }
  ]
}
```

The index exists because listing a directory on the classpath isn't reliable: it works in a
directory, needs a `FileSystem` inside a JAR, and fails in some shaded or nested layouts. With an
index, the runtime reads exactly the files it lists.

- `sha256` is the hash of the file's content. §3.5 explains what it's for.
- `schemaAfter` is a hash of the schema after this migration. The task computes it by applying
  every migration in order to an in-memory SQLite database, and reading `sqlite_schema` after each
  one. §3.4 uses it.

Replaying the migrations at build time has a second benefit: it's a stronger `dbVerify`. Today,
`dbVerify` compares the entities with `db/schema.json`. That catches a forgotten `dbDiff`, but not a
migration that was edited by hand into something that no longer produces `schema.json`. The task
compares the replayed final schema with the entities and fails on any difference, so a broken
migration fails the build, not a deployment.

### 3.3 `Db.open` migrates first

```kotlin
public fun open(
    path: Path,
    tables: List<Table<out Record>>,
    migrations: MigrationSource = MigrationSource.Classpath,
    onStartup: MigrationMode = MigrationMode.Apply,
): Db
```

- `MigrationSource.Classpath` reads `META-INF/jetlin-db/migrations/index.json`. If it's absent, as in
  `jetlin-db`'s own tests and a quick prototype, there are no migrations, and `Db.open` behaves as
  it does today. `MigrationSource.Directory(path)` is for tools and tests. `MigrationSource.None`
  turns migrations off.
- `MigrationMode.Apply` applies pending migrations, then loads. `MigrationMode.Verify` fails if any
  migration is pending, for operators who want to migrate as a separate, deliberate step, for example
  with `dbMigrate` against a copy first. It's the same check that `verifySchema` makes today, but its
  message names the pending migrations instead of the missing columns.

The order inside `open` becomes: configure the connection, migrate (§3.4), create any table that
still doesn't exist (only when there are no migrations), verify, then load. Migrating happens before
anything is loaded into memory, so the identity map never sees a half-migrated schema.

**Should `Apply` be the default?** Applying migrations at startup is what makes a deployment one
step, and the acknowledgement marker already stops a migration that destroys data. The case against
it is a team that wants a human in the loop for every schema change in production. They have
`Verify`. Defaulting to `Apply` matches the target: single-node applications where the person
deploying is the person who wrote the migration. This is open question 1 in §8.

### 3.4 Where a database's history starts

The runner first works out which migrations a database has already had:

1. **It has a `jetlin_migrations` table.** Use it, as `dbMigrate` does today.
2. **It has no tables at all.** It's new. Apply every migration from the first, which tests the
   migrations on every fresh start, instead of only in production. This replaces `createSchema` for
   applications that have migrations.
3. **It has tables, but no history.** It was created by an older `Db.open`, as in §1.2. Hash its
   schema the same way the build did, and find the migration whose `schemaAfter` matches. Record that
   migration and every earlier one as applied, then apply the rest.
   - If no migration matches, stop with an error. The error shows the stored schema and the closest
     match, and says which tables and columns differ. Guessing here is how a database gets a migration
     applied twice, or skipped.
   - The match ignores column order, and the `jetlin_migrations` table itself, for the same reasons
     `verifySchema` does.

Case 3 is also what `dbMigrate` does, since it's the same engine (§3.1). That fixes the development
sequence in §1.2 without anything new to learn.

### 3.5 Safety

- **One transaction per migration**, foreign keys off during it, and the existing checks before
  commit: `PRAGMA foreign_key_check`, and no lost indexes, triggers, or views. All of this is
  unchanged, and simply runs in more places.
- **Unacknowledged migrations stop startup** before any migration runs, with the message
  `dbMigrate` already gives. Deleting the marker line stays the only way to allow one, and it stays
  visible in code review.
- **A backup before migrating.** When at least one migration is pending, the runner first writes a
  copy of the database with `VACUUM INTO '<file>.before-<first pending migration>'`. It keeps the three
  most recent, and deletes older ones. It's on by default, and can be turned off. A failed migration
  already rolls back, so this is for the migration that succeeds and turns out to be wrong. It costs
  disk space equal to the database, once per deployment that changes the schema. db.md recommends
  Litestream for backups, which is continuous and off-site. This is a local, point-in-time copy for a
  quick undo, and doesn't replace it.
- **Edited migrations are caught.** History gains a `sha256` column. The history table migrates
  itself the first time the new runner sees it, with `ALTER TABLE jetlin_migrations ADD COLUMN sha256
  TEXT` and existing rows `NULL`. If an applied migration's file no longer matches its recorded hash,
  startup fails. Editing a migration after it ran somewhere means that database and a fresh one now
  differ. A `NULL` hash, from before this change, isn't checked.
- **Two processes starting at once.** A rolling deployment can briefly start a new container while
  the old one is still running. The runner takes the write lock with `BEGIN IMMEDIATE` before it
  reads the history, so a second runner waits, then sees the migrations already applied, and applies
  nothing. The old process doesn't know the schema changed. Its next write fails, because
  `jetlin-db` already detects writes by another process (db.md §6). That's the right outcome, but
  it's an error in the old process's log. db.md gets a deployment note: stop the old container before
  starting the new one. In Dokploy and Docker Swarm, that's the "stop first" update order, with one
  replica.

### 3.6 What the application sees

A log line for each migration applied, with its name and how long it took. On failure, the exception
from `Db.open` names the migration, the statement, and SQLite's message, and says that the database
was left as it was. An application with no migrations, or nothing pending, logs nothing.

`jetlin-db` has no logging dependency today. Rather than add one, `Db.open` takes an optional
`onMigration: (MigrationEvent) -> Unit`, which defaults to printing to standard error. Applications
with a logger pass their own.

## 4. What goes away

- In the plugin: the runner's code, which moves to `jetlin-db-migrate` (§3.1). `dbMigrate` becomes a
  thin task that calls the engine with `MigrationSource.Directory`.
- In `samples/teams`' KDoc for `openSeeded`: "A real application would open a persistent file that
  `./gradlew dbMigrate` has migrated." db.md §7 describes `dbMigrate` as the only way to migrate, and
  gets rewritten in phase 5.
- In Pollster: `Migrations.kt`, the `POLLSTER_MIGRATIONS` variable, and the Dockerfile line that
  copies `db/migrations` into the image. Pollster's `main` calls `Db.open` and nothing else.

## 5. Phases

Each phase ends with `./gradlew build` passing, including the included build's tests. The root
build's `build` task already runs them.

### Phase 1: extract the engine

Create `jetlin-db-migrate` (§3.1), move the runner into it unchanged, and point `dbMigrate` at it.
No behavior changes.

- Acceptance: the plugin's existing migration tests pass unchanged, moved to the new module.
  `:jetlin-db` can depend on the module, and so can a project that uses `includeBuild` on the Jetlin
  repository. Pollster is the test for that.

### Phase 2: history that starts correctly

Implement §3.4 in the engine, and the `sha256` history column from §3.5. `dbMigrate` gains both, so
this phase fixes §1.2 before anything runs at startup.

- Acceptance: tests for each case in §3.4. That includes a database created by `Db.open` at the
  first migration and at the second, a database whose schema matches no migration (it fails, and
  names the difference), and an edited migration (it fails).

### Phase 3: package migrations

Implement §3.2: `dbPackageMigrations`, the index, the replayed `schemaAfter` hashes, and the stronger
check against the entities.

- Acceptance: the JAR of `:samples:teams` contains the migrations and the index. A test edits a
  migration so that it no longer produces the entities' schema, and the build fails with a message
  that names the difference.

### Phase 4: migrate in `Db.open`

Implement §3.3, §3.5 (backups, `BEGIN IMMEDIATE`), and §3.6.

- Acceptance: `:samples:teams` opens a persistent file instead of a temporary one, and survives a
  restart after a schema change made in a test. Tests cover an empty database, a current one, one
  with pending migrations, `Verify` mode with pending migrations (fails, names them), an
  unacknowledged migration (fails before anything runs), a failing migration (rolled back, backup
  present, startup fails), and two runners started at once on one file (one applies, one waits and
  applies nothing).

### Phase 5: documentation and Pollster

Rewrite db.md §7 around startup migration, with `dbMigrate` as the tool for development and for
operators who choose `Verify`. Add the deployment note from §3.5. Then remove Pollster's runner
(§4), and check that its tests, its Docker image, and a database created by its current
`Migrations.kt` all still work. That database has history but no `sha256` column, so it exercises the
history table migrating itself.

## 6. Alternatives considered

| Alternative | Why it was rejected |
|---|---|
| Keep migrating with Gradle, and run `dbMigrate` in the deployment pipeline | The pipeline needs the source, Gradle, and the production volume mounted together. That's three things a container platform such as Dokploy doesn't give a build step. It also leaves a window where the new code runs against the old schema. |
| Run migrations from a separate entry point, such as `java -cp app.jar jetlin.db.MigrateKt` | It works, and `Verify` mode leaves room for it. But it's a second process to remember to run before the first, which is the step that gets forgotten. |
| Let `Db.open` alter tables to match the entities automatically, with no migration files | That's how data gets destroyed without review. A dropped column or a type change has to be a file a person read. db.md §7 explains why the SQL is meant to be read. |
| Find migrations by listing the classpath, without an index | Unreliable across JARs, nested JARs, and shaded builds (§3.2). The index is generated, so it costs nothing to maintain. |
| Baseline a database without history by assuming the current schema | Wrong whenever the code is newer than the database, which is exactly when it matters: the first deployment of a version that has a new migration. §3.4's schema hashes find the right starting point instead. |

## 7. Follow-ups this enables

- `dbStatus`: a task, and a `Db.migrationStatus()` function, that list applied and pending
  migrations, for an admin page or a health check.
- A migration written in Kotlin, for data changes that SQL can't express well, run by the same
  engine in the same transaction. It would need its own design: Kotlin migrations can't be replayed at
  build time without running application code.

## 8. Open questions

1. **`Apply` or `Verify` by default** (§3.3). The recommendation is `Apply`.
2. **Backups by default** (§3.5). The recommendation is on, keeping three. Is a copy next to the
   database the right place, given that it's usually on the same volume?
3. **What the schema hash covers.** Hashing `sqlite_schema`'s SQL text is simple, but it depends on
   how SQLite formats it, which can vary between versions. Hashing the column sets that
   `verifySchema` already reads is sturdier, but misses indexes and triggers. The recommendation is the
   column sets, plus the names of indexes, triggers, and views.
4. **Where `jetlin-db-migrate` lives** (§3.1). Decide this in phase 1.

## 9. Decision log

Record corrections to this plan here, with the date and the reason, instead of silently doing
something different.
