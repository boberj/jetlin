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
| `build` | `./gradlew build` — compilation and the unit tests |
| `client` | TypeScript type check, and that the checked-in `jetlin.js` matches its source |
| `e2e` | Playwright against the running demo |

## A guard worth keeping

**The client bundle must not go stale.** `jetlin.js` is committed so that consumers of the Gradle
build never need npm. That only holds if it is rebuilt whenever the TypeScript changes, so CI
rebuilds it and fails on any diff.

The other rule this pipeline used to enforce — that no test is silently dropped by JUnit for
returning a value instead of `Unit` — now lives in `:conventions` as an ordinary test, so it runs on
`./gradlew build` locally as well as in CI. See `conventions/src/test/.../TestConventionsTest.kt`.
