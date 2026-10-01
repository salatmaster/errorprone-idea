<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="src/main/resources/META-INF/pluginIcon_dark.svg">
    <img src="src/main/resources/META-INF/pluginIcon.svg" width="80" alt="">
  </picture>
</p>

<h1 align="center">Error Prone for IntelliJ IDEA</h1>

<p align="center">
  <b>Error Prone's findings in the editor, on the code they are about — straight from your Gradle build.</b>
</p>

<p align="center">
  <a href="https://plugins.jetbrains.com/plugin/34671-error-prone"><img src="https://img.shields.io/jetbrains/plugin/v/34671?label=Marketplace" alt="Marketplace version"></a>
  <a href="https://plugins.jetbrains.com/plugin/34671-error-prone"><img src="https://img.shields.io/jetbrains/plugin/d/34671" alt="Downloads"></a>
  <a href="https://github.com/salatmaster/errorprone-idea/actions/workflows/build.yml"><img src="https://github.com/salatmaster/errorprone-idea/actions/workflows/build.yml/badge.svg" alt="Build"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/salatmaster/errorprone-idea" alt="License"></a>
</p>

[Error Prone](https://errorprone.info) already runs in your build, but its findings end up in the build
log as a file name and a line number. This plugin puts each one on the code it concerns, the way the IDE
shows its own inspections, and lets you fix or suppress it from there. It uses your build's own Error
Prone, flags and plugins, and runs no checks of its own: your build stays the source of truth.

## Features

- **In the editor.** An underline on the exact token, with the check, the message, Error Prone's
  suggested fix and a link to the check's documentation in the tooltip.
- **Fix or suppress with Alt+Enter.** Apply Error Prone's own fix, imports included, with a preview of
  the line it writes. Or add `@SuppressWarnings` to the narrowest declaration around the diagnostic — a
  variable, a method, a class — or a wider one from the submenu. Either is one undoable edit, and the
  highlights it settles go at once.
- **A tab in the Problems tool window.** Every diagnostic in the project, grouped by check or by file,
  with a filter, severity toggles, and the details of the selection with its fix, suppression and
  documentation a click away. <kbd>Ctrl+Alt+↓</kbd> steps to the next diagnostic even from the editor,
  <kbd>Alt+Enter</kbd> on a row shows what can be done, and <kbd>Ctrl+C</kbd> copies the selection as
  `path:line: [Check] message` lines.
- **All fixes at once, by scope.** Apply All Error Prone Fixes asks for a scope as Inspect Code does —
  the project, a module, a directory — and gathers every fix into one patch to review file by file.
- **What your change brings in.** *Changed Lines Only* in the tab's filter shows the diagnostics on
  lines version control sees changed, and a commit whose changed lines have any asks first, from what
  the last builds reported, without compiling anything.
- **Current after every edit.** Two seconds after you stop typing near a diagnostic, the file is
  compiled quietly in the background, so a warning you fixed goes away without a build.
- **Plugins included.** Checks from Error Prone plugins such as [NullAway](https://github.com/uber/NullAway)
  show up like the built-in ones.

## Getting started

1. **Install the plugin**: <kbd>Settings</kbd> → <kbd>Plugins</kbd> → <kbd>Marketplace</kbd>, search
   for *Error Prone*. Or download the zip from
   [Releases](https://github.com/salatmaster/errorprone-idea/releases) and use *Install Plugin from
   Disk…*.
2. **Have Error Prone in your Gradle build**, for example with the
   [`net.ltgt.errorprone`](https://github.com/tbroyer/gradle-errorprone-plugin) plugin:

   ```kotlin
   plugins {
       java
       id("net.ltgt.errorprone") version "5.1.0"
   }

   dependencies {
       errorprone("com.google.errorprone:error_prone_core:2.50.0")
   }
   ```

3. **Run Error Prone once**: <kbd>Build</kbd> → <kbd>Run Error Prone</kbd> recompiles every Java source
   set, so every file is analysed, and shows the results. From then on, the builds the IDE runs keep them
   current. If the Error Prone tab stays empty, it says why.

**Requirements:** IntelliJ IDEA 2026.1 or newer, Gradle 8.14 or newer, builds run from the IDE.

## Where to find things

| Where | What |
|---|---|
| <kbd>Alt+Enter</kbd> on a highlight | Apply Error Prone fix, Suppress with `@SuppressWarnings` |
| <kbd>View</kbd> → <kbd>Tool Windows</kbd> → <kbd>Problems</kbd> → **Error Prone** | Every diagnostic, grouped and filterable |
| <kbd>Build</kbd> → <kbd>Run Error Prone</kbd> | Recompile every Java source set in full, then show the Error Prone tab |
| <kbd>Build</kbd> → <kbd>Apply All Error Prone Fixes…</kbd> | Every fix in a scope, as one patch; also under <kbd>Code</kbd> → <kbd>Analyze Code</kbd> and the Project view's <kbd>Analyze</kbd> |
| <kbd>Settings</kbd> → <kbd>Tools</kbd> → <kbd>Error Prone</kbd> | Recompile in the background after edits near a diagnostic, after any edit of Java code, or never |
| The commit options | *Check Error Prone diagnostics* on the lines a commit changes |
| <kbd>Settings</kbd> → <kbd>Editor</kbd> → <kbd>Inspections</kbd> → **Error Prone** | Turn the highlighting off; <kbd>Code</kbd> → <kbd>Inspect Code</kbd> lists the diagnostics |

## How it works

When the IDE runs a Gradle build — Build Project, a Gradle task, a run configuration — Gradle reports
every javac diagnostic through its Problems API. The plugin listens to those events over the Tooling
API and keeps Error Prone's, with their file, line and column; no console output is parsed.

Each build updates what you see: a full compile replaces what its task reported before, an incremental
one updates the files it recompiled, and an up-to-date one changes nothing. A failed compile only adds:
Error Prone reports nothing once javac finds an error, so its silence proves nothing. Between builds the
highlights follow your edits, a diagnostic whose line you delete or rewrite is hidden at once, and all
of them survive an IDE restart.

## FAQ

<details>
<summary><b>Nothing shows up.</b></summary>

The Error Prone tab says why when it is empty: no Gradle build linked, a Gradle older than 8.14, no Error
Prone in the build as of the last sync, IntelliJ IDEA's own builder doing Build Project, or compiles that
were up to date and so reported nothing. Builds run in a terminal (`./gradlew build`) do not reach the
IDE at all. <kbd>Build</kbd> → <kbd>Run Error Prone</kbd> compiles everything again.
</details>

<details>
<summary><b>It says javac stopped at 100 warnings.</b></summary>

javac reports at most 100 warnings per compile task. The notification copies the line that raises the
limit, in your build's DSL, for the root build script. The plugin does not add it itself: a compiler
argument that differs between IDE and terminal builds would make every switch between them recompile
everything.
</details>

<details>
<summary><b>How do I turn a check off, or make it an error?</b></summary>

In the build, where Error Prone's configuration lives: the plugin never edits build scripts. Right-click
the check in the Error Prone tab and choose *Copy Gradle Line That Turns the Check Off* (or *Makes the
Check an Error*), then paste it into the root build script; it is written in the build's DSL. To silence
one place instead, suppress it with <kbd>Alt+Enter</kbd>.
</details>

<details>
<summary><b>It reports on generated code.</b></summary>

Error Prone checks everything javac compiles. To leave generated sources out, set
`options.errorprone.excludedPaths = ".*/build/generated/.*"` (or `disableWarningsInGeneratedCode = true`
for code marked `@Generated`). Either way the plugin never applies a fix to generated code, which the
next generation would undo.
</details>

<details>
<summary><b>A fix was not applied.</b></summary>

Error Prone keeps its fixes to itself until a build writes them out, so applying one runs a short
Gradle build that needs the `net.ltgt.errorprone` plugin. A fix that uses a class the module does not
have (a few use Guava), or whose code changed while the build ran, is left out; the notification says
why and offers the patch to review.
</details>

<details>
<summary><b>Gradle's HTML problems report lists every warning now.</b></summary>

Gradle forwards only 15 warnings of a kind to the IDE unless told otherwise, and every Error Prone
warning is one kind. The plugin raises that limit for the builds the IDE runs, and the report Gradle
writes follows it.
</details>

## Known limitations

- **Gradle only**, and **local builds only**: Maven, the IDE's own build system, and builds in WSL,
  Docker or on a remote host are not supported.
- **Included builds need Gradle 9.7**: before it, Gradle files their diagnostics under the root build's
  task of the same name. Run Error Prone recompiles an included build only when the root build depends on
  it; one it does not depend on updates when the IDE builds it.
- **A file changed while the IDE was closed** (a pull, a checkout) loses its diagnostics until the next
  build compiles it.
- **An incremental build can leave a stale warning** on a file that was recompiled only because a file
  it depends on changed. Run Error Prone clears it.
- **Recompiling after an edit** follows edits near a diagnostic unless set to follow any edit, and
  waits while another Gradle task of the project runs, a running application or its tests included; the
  Error Prone tab says so.
- **A few checks read `@SuppressWarnings` on the class only** (InconsistentCapitalization, for one): when
  a narrower suppression does not hold, the next build shows the diagnostic again, and the class is in the
  Suppress submenu.
- **Fixes cover a whole file**: Error Prone writes all of a check's fixes in a file together, so
  Apply Error Prone fix applies them together.

## Contributing

Building, testing and releasing are described in [CONTRIBUTING.md](CONTRIBUTING.md). Bugs and ideas go to
[issues](https://github.com/salatmaster/errorprone-idea/issues).

## License

[Apache License 2.0](LICENSE). Not affiliated with Google or the Error Prone project.
