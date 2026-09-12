# CI

`github-actions.yml` is the CI pipeline. **It is not active yet** — install it with:

```bash
mkdir -p .github/workflows && git mv ci/github-actions.yml .github/workflows/ci.yml
```

It lives here rather than in `.github/workflows/` because the token used to push this branch did not
carry GitHub's `workflow` scope, and GitHub rejects pushes that add or change workflow files without
it. Moving the file is the whole installation.

## What it runs

| Job | Checks |
|---|---|
| `build` | `./gradlew build` — compilation, the unit tests, the conventions, the migration tooling (an included build the root `build` task reaches) and `dbVerify` |
| `client` | TypeScript type check, and that the checked-in `jetlin.js` matches its source |
| `e2e` | Playwright against the running demo |

## A guard worth keeping

**The client bundle must not go stale.** `jetlin.js` is committed so that consumers of the Gradle
build never need npm. That only holds if it is rebuilt whenever the TypeScript changes, so CI
rebuilds it and fails on any diff.

**The schema must not drift.** `:samples:teams` applies the `jetlin.db` plugin, which hooks `dbVerify`
into `check`: if an entity and the checked-in `db/schema.json` disagree, the build fails and names the
change, because a migration generated against a stale snapshot is worse than no migration. Same spirit as
the bundle rule above, and it needs no CI step of its own.

The other rule this pipeline used to enforce — that no test is silently dropped by JUnit for
returning a value instead of `Unit` — now lives in `:conventions` as an ordinary test, so it runs on
`./gradlew build` locally as well as in CI. See `conventions/src/test/.../TestConventionsTest.kt`.
