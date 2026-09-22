# CI

`github-actions.yml` defines the CI pipeline. **It is not enabled yet.** To enable it, move it into
`.github/workflows/`:

```bash
mkdir -p .github/workflows && git mv ci/github-actions.yml .github/workflows/ci.yml
```

The file is kept here because the token used to push this branch didn't have GitHub's `workflow` scope,
and GitHub rejects pushes that add or modify workflow files without it. No other setup is needed after
the move.

## Jobs

| Job | What it checks |
|---|---|
| `build` | `./gradlew build`: compilation, unit tests, the conventions, the migration tooling (an included build that the root `build` task also runs), and `dbVerify` |
| `client` | TypeScript type checking, and that the committed `jetlin.js` matches its source |
| `e2e` | Playwright tests against the running demo |

## Checks that prevent drift

**The committed client bundle must match its source.** `jetlin.js` is committed so that the Gradle
build doesn't need npm. That only works if the bundle is rebuilt every time the TypeScript changes, so
CI rebuilds it and fails if the result differs from the committed file.

**The recorded schema must match the entities.** `:samples:teams` applies the `jetlin.db` plugin, which
makes `check` depend on `dbVerify`. If an entity no longer matches the committed `db/schema.json`, the
build fails and lists the differences. This matters because a migration generated from an outdated
snapshot would be wrong. The check runs as part of `./gradlew build`, so it needs no separate CI step.

This pipeline used to have one more check: that JUnit doesn't silently skip a test because it returns a
value instead of `Unit`. That check is now an ordinary test in `:conventions`
(`conventions/src/test/.../TestConventionsTest.kt`), so it also runs locally with `./gradlew build`.
