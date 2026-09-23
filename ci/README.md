# CI

`github-actions.yml` defines the CI pipeline, and it isn't enabled yet. To enable it, move it into
`.github/workflows/`:

```bash
mkdir -p .github/workflows && git mv ci/github-actions.yml .github/workflows/ci.yml
```

The file lives here because the token that pushed this branch didn't have GitHub's `workflow` scope,
and GitHub rejects a push that adds or changes a workflow file without it. After you move the file,
no other setup is needed.

## Jobs

| Job | What it checks |
|---|---|
| `build` | `./gradlew build`: compilation, unit tests, the conventions, the migration tooling (an included build that the root `build` task also runs), and `dbVerify` |
| `client` | TypeScript type checking, and that the committed `jetlin.js` matches its source |
| `e2e` | Playwright tests against the running demo |

## Checks that prevent drift

### The committed client bundle matches its source

`jetlin.js` is committed so that the Gradle build doesn't need npm. That works only if the bundle is
rebuilt every time the TypeScript changes, so CI rebuilds it and fails if the result differs from the
committed file.

### The recorded schema matches the entities

`:samples:teams` applies the `jetlin.db` plugin, which makes `check` depend on `dbVerify`. If an
entity no longer matches the committed `db/schema.json`, the build fails and lists the differences.
This matters because a migration generated from an outdated snapshot would be wrong. The check runs
as part of `./gradlew build`, so it needs no separate CI step.

### JUnit runs every test

JUnit skips a test method that returns a value instead of `Unit`, without a warning. An ordinary test
in `:conventions`, `TestConventionsTest.kt`, fails the build if any test method would be skipped this
way. Because it's a test, it also runs locally with `./gradlew build`.
