# Contributing

Thanks for considering a contribution. This document assumes no prior knowledge of the codebase.

## Building and testing

```bash
./gradlew build          # compile and run tests
./gradlew test           # tests only
./gradlew buildPlugin    # produce the plugin ZIP in build/distributions/
./gradlew verifyPlugin   # check compatibility with target IDEs
./gradlew runIde -PsampleProject=src/test/testData/sample   # sandbox IDE on the fixture project
```

The build provisions its own JVM 21 toolchain. The first build downloads the IntelliJ Platform and
takes a few minutes.

`ErrorProneEndToEndTest` runs real Gradle builds of `src/test/testData/sample` through the Tooling
API, with the Gradle that runs the build. It needs the network the first time, to resolve Error Prone.
To run it against another Gradle, pass `-PtestGradleVersion=8.14`; tests run on the IDE's own
runtime, which Gradle 8 cannot use, so add `-PtestJavaHome=/path/to/jdk21` as well. On Gradle before
9.7, `files the diagnostics of an included build under its own task` fails by design — see the
README's limitations.

## Layout

One module, one package, `io.github.salatmaster.errorprone`:

| File | Contains |
|---|---|
| `ErrorProneDiagnostic.kt` | the diagnostic model, parsing from a Gradle problem, javac's tab-expanded columns |
| `ErrorProneDiagnostics.kt` | the per-project store: merge rules, RangeMarker anchoring, change topic |
| `ErrorProneGradleExtension.kt` | the Gradle hook, the Tooling API listener, notifications |
| `ErrorProneExternalAnnotator.kt`, `ErrorProneInspection.kt` | editor highlighting and its paired inspection |
| `ErrorProneProblemsTab.kt` | the Problems tool window tab |
| `RunErrorProneAction.kt` | Build \| Run Error Prone and its init script |

Pure logic — parsing, column conversion, merge rules — is kept apart from the platform glue so it can
be tested without it.

## Rules that are not negotiable

- **Never fail the user's build.** Everything Gradle or the Tooling API calls catches its exceptions.
- **Never parse console output.** Diagnostics come from structured Problems API events or not at all.
- **Never run a check of our own.** Error Prone, configured in the user's build, is the source of truth.
- **Never block the EDT.**

## Testing expectations

Test pure logic without the platform. `BasePlatformTestCase` reuses one light project across the
tests of a class, so the store is cleared in `setUp` and `tearDown` (see `ErrorProneLightTestCase`).
Anything that depends on how Gradle behaves belongs in `ErrorProneEndToEndTest`.

## Releasing

Releases are cut from the Actions tab, not by pushing a tag: run the **Release** workflow and give it
a version such as `0.1.0`.

The workflow moves everything under `## [Unreleased]` in `CHANGELOG.md` into a section for that
version, then tests, verifies against the target IDEs and builds — and only if all of that passes
does it commit the changelog to `main`, tag it and publish the GitHub release. A release with an
empty `[Unreleased]` section is refused outright.

Publishing to the JetBrains Marketplace happens only when the repository variable
`PUBLISH_TO_MARKETPLACE` is `true`.

## Commits and pull requests

Explain why a change is needed, not only what it does. If you worked around a platform or Gradle
behaviour, say which one — that context is what makes the code maintainable later.
