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

## Two guards worth keeping

**The client bundle must not go stale.** `jetlin.js` is committed so that consumers of the Gradle
build never need npm. That only holds if it is rebuilt whenever the TypeScript changes, so CI
rebuilds it and fails on any diff.

**Every declared test must actually run.** A Kotlin test method that returns a value instead of
`Unit` is silently ignored by JUnit rather than failing, so a broken test is indistinguishable from a
passing one. The job compares the number of `@Test` annotations against the number of test cases in
the XML results. This is not hypothetical — it caught a test in this repository that had never run,
because its body ended in `assertIs`, which returns a value.
