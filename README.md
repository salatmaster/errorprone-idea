# Error Prone

**Error Prone's findings in the IntelliJ IDEA editor, on the code they are about.**

[Error Prone](https://errorprone.info) already runs in your Gradle build. Its findings end up in the
build log, as text with a file name and a line number. This plugin puts each one on the code it
concerns, the way the IDE shows its own inspections.

Not affiliated with Google or the Error Prone project.

---

## What it does

| Where | What you get |
|---|---|
| Editor | An underline on the exact token, with the check, the message, Error Prone's suggested fix and a link to the check's documentation in the tooltip |
| Problems tool window | An **Error Prone** tab with every diagnostic in the project; the **File** tab shows the current file's |
| Inspections | A paired **Error Prone** inspection: turn it off in the profile to hide the highlighting, or run **Code \| Inspect Code** to list the diagnostics |
| Alt+Enter | **Suppress** a check with `@SuppressWarnings` on the method, field or class, and **Apply Error Prone fix** — Error Prone's own fix, imports included. Both are one undoable edit, and the highlight goes at once |
| Build menu | **Run Error Prone** recompiles every Java source set in full, so every file is analysed again; **Apply All Error Prone Fixes…** gathers every fix Error Prone has into one patch to review file by file |
| Settings \| Tools \| Error Prone | **Recompile after editing code Error Prone reported on** (on by default): two seconds after you stop typing near a diagnostic, once the file has no errors, it is saved and Gradle compiles its source set in the background, with no build output |

## How it works

Your build stays the source of truth. The plugin runs no checks of its own and changes nothing about
how Error Prone is configured.

When the IDE runs a Gradle build — Build Project (delegated to Gradle, the default), a Gradle task,
a run configuration — Gradle reports each javac diagnostic through its Problems API. The plugin
listens to those events over the Tooling API and keeps the ones Error Prone produced, with their
file, line and column. No console output is parsed.

Diagnostics update with every build:

- a compile task that ran in full replaces everything it reported before, so a warning you fixed
  disappears;
- an incremental compile updates the files it recompiled and keeps the rest;
- a failed compile only adds what it reported: Error Prone says nothing at all once javac finds an
  error, so a file saved half-way through an edit keeps its diagnostics;
- an up-to-date or cached compile changes nothing.

Between builds, the highlights follow your edits — and they stay while the file has other errors,
since those say nothing about what the last build found. A diagnostic whose line you change (delete,
comment out, rewrite; reindenting does not count) is hidden at once, and comes back if the next
compile still reports it.

Only javac can tell whether a warning is gone, so an edit on a diagnostic's line, or in the method or
field that holds it, has the file saved and its source set compiled two seconds after you stop typing.
Not while the file has an error the IDE can see: javac would stop at it, and Error Prone then reports
nothing. The compile shows only as progress in the status bar. Turn it off in Settings | Tools |
Error Prone to wait for your own builds instead. They also survive an IDE restart, so the
first build after it, which usually compiles nothing, does not leave the editor blank.

## Requirements

- IntelliJ IDEA 2026.1 or newer.
- Gradle 8.14 or newer, with Error Prone set up in the build — for example with the
  [`net.ltgt.errorprone`](https://github.com/tbroyer/gradle-errorprone-plugin) Gradle plugin.
  Older Gradle versions do not report javac diagnostics to the IDE in a form the plugin can use;
  builds run as usual, and Run Error Prone says why nothing is shown.
- Builds run from the IDE.

## Limitations

- **Builds outside the IDE are not seen.** A `./gradlew build` in a terminal does not reach the
  plugin; run the build from the IDE, or use Run Error Prone.
- **A file changed while the IDE was closed loses its diagnostics.** They are kept across restarts,
  but a pull or checkout in between moves the code they point at; the next build that compiles the
  file, or Run Error Prone, brings them back.
- **Gradle only.** Maven and the IDE's own build system (JPS) are not supported.
- **Included builds need Gradle 9.7.** Before 9.7, Gradle reports an included build's diagnostics
  under the root build's task of the same name, so they can disappear when only one of the two builds
  recompiles. Run Error Prone does not reach included builds on any Gradle version; their
  diagnostics appear when the IDE builds them.
- **Local projects only.** A build that runs in WSL, Docker or on a remote host reports paths the
  plugin cannot open.
- **javac reports at most 100 warnings per compile task** unless the build raises its `-Xmaxwarns`.
  The plugin says so when a task reaches the limit; to see every warning, add
  `options.compilerArgs.addAll(listOf("-Xmaxwarns", "10000"))` to the compile tasks.
- **Fixes come from a build.** Error Prone keeps its fixes to itself until a build writes them out,
  so applying one runs a short Gradle build first. Writing fixes needs the
  [`net.ltgt.errorprone`](https://github.com/tbroyer/gradle-errorprone-plugin) Gradle plugin, and
  Error Prone writes all of a check's fixes in a file together, so "Apply Error Prone fix" covers the
  whole file. Error Prone applies the first fix it suggests, and a few of those use Guava
  (`Splitter`, `ImmutableList`): a fix that uses a class the module does not have, or whose lines
  were edited while it was being written, is not applied; a notification says why and offers it in
  Apply Patch.
- **Gradle's problems report grows.** To receive more than 15 Error Prone warnings, the plugin raises
  Gradle's internal limit for IDE builds (`org.gradle.internal.problem.summary.threshold`), so the
  HTML problems report Gradle writes lists every warning as well.
- **Recompiling after an edit waits for other Gradle builds.** It starts nothing while a Gradle task
  of the project runs, so a long-running one (`bootRun`, a Gradle test run) holds it off until it
  ends. It only follows edits near a diagnostic: a new warning in clean code shows after the next
  build.
- **Incremental builds can leave a stale warning** on a file that did not change but was recompiled
  because a file it depends on did. Run Error Prone clears it.

## Building from source

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

[Apache License 2.0](LICENSE).
