# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- Alt+Enter previews Error Prone's fix: the line as the fix will write it, before the build that writes
  the whole fix runs.
- Settings | Tools | Error Prone can have any edit of Java code recompiled, not only one near a
  diagnostic, so new code gets Error Prone's findings without a build. Off by default.
- Changed Lines Only, in the Error Prone tab's filter, shows the diagnostics on lines version control
  sees changed. A commit with Error Prone diagnostics on the lines it changes asks first and links to
  them; it reads what the last builds reported, compiling nothing, and can be turned off in the commit
  options.
- A diagnostic's tooltip and details say which compile reported it and when, and that it may be out of
  date once a later compile of that task failed. The Error Prone tab's status line counts failed
  compiles and tasks where javac stopped at its 100-warning limit.
- An empty Error Prone tab says why: no Gradle build, a Gradle older than 8.14, no Error Prone in the
  build as of the last sync, IntelliJ IDEA's own builder, only up-to-date or incremental compiles so far,
  a failed compile, or a clean one. It offers Run Error Prone only where that can help.

### Changed

- In the Error Prone tab, Next and Previous Occurrence step through the diagnostics, from the editor
  too; Alt+Enter on a row shows what can be done with it; Copy puts every diagnostic under the selection
  on the clipboard as `path:line: [Check] message`. Files show their package, and the filter matches
  package and module names.
- The Error Prone tab copies the Gradle line that turns a check off or makes it an error, in the build's
  DSL, for the root build script.
- A check or a file in the Error Prone tab can be acted on as a whole: Apply Fixes has Error Prone fix
  the files shown without asking for a scope, and Suppress All adds `@SuppressWarnings` for every
  diagnostic under it, each in its own declaration, after asking. A check says how many of its
  diagnostics have a fix.
- Run Error Prone cannot be started twice at once. The Error Prone tab shows it running, then how many
  diagnostics the run added or removed, and comes forward in place of the Build window once the run
  succeeds.
- Recompiling after an edit says in the Error Prone tab when it waits, for another Gradle build or for
  its module to have no errors.
- After a build, only the files open in an editor are highlighted again: a build that reports on
  thousands of files no longer loads each of them to do it.
- Applying a fix recompiles only the task that reported it, rather than every module it depends on in
  full, which in a large build was most of the wait.
- Suppress names the declaration it annotates and picks the narrowest, a local variable or a parameter
  included, with the wider ones in its submenu. The check's other diagnostics in that declaration go at
  once, rather than at the next build.
- While Error Prone writes a fix, a hint at the caret says so and the build is named after the check and
  the file. Pressing the fix again meanwhile starts no second build.

### Fixed

- The first fix of a session no longer fails with "could not be read" after an earlier session applied
  one: each fix build now writes to a directory of its own.
- A check that offers several fixes no longer shows them glued into one: the tooltip and the Error Prone
  tab list each on its own, the one Apply Fix writes first.
- A fix that adds an import no longer reorders the others: Error Prone lays the imports out as the
  project's Java code style does, static imports first or IntelliJ's default.

## [0.2.0] - 2026-09-30

### Changed

- The Error Prone tab in the Problems tool window groups diagnostics by check or by file, filters them,
  and shows the details of the selection, with its fix, its suppression and its documentation a click
  away.
- Apply All Error Prone Fixes asks for a scope as Inspect Code does, compiles only what that scope
  needs, and is also under Analyze in the Project view's context menu.
- Every notification of the plugin is titled Error Prone. The one about javac's 100-warning limit
  copies the line that raises it, in the build's DSL.

### Fixed

- Fixes keep non-ASCII text as written: Error Prone's Javadoc fixes turned it into `\u` escapes.
- Fixes are no longer offered or applied in generated code, which the next generation would undo.

## [0.1.0] - 2026-09-30

### Added

- Error Prone diagnostics from Gradle builds run in the IDE are underlined in the editor, on the
  token Error Prone points at, with the check, the message, Error Prone's suggested fix and a link to
  the check's documentation in the tooltip. Checks from Error Prone plugins such as NullAway too.
- An Error Prone tab in the Problems tool window lists every diagnostic in the project.
- A paired Error Prone inspection turns the highlighting off from the inspection profile and lists
  the diagnostics in Code | Inspect Code.
- Build | Run Error Prone recompiles every Java source set in full, so every file is analysed again.
- Diagnostics are kept across IDE restarts, except for files that changed while the IDE was closed.
- Alt+Enter on a diagnostic suppresses its check with `@SuppressWarnings`, or applies Error Prone's own
  fix, imports included, as one undoable edit.
- Build | Apply All Error Prone Fixes gathers every fix Error Prone has for what it reports into one
  patch, to review and apply file by file.
- An edit near a diagnostic (its line, or the method or field that holds it) has the file saved and
  its source set compiled quietly in the background once typing stops and the file has no errors,
  so fixed warnings go without a Build. On by default, in Settings | Tools | Error Prone.
- A diagnostic whose line was deleted, commented out or rewritten is hidden at once instead of
  sliding onto the next line's code.

[unreleased]: https://github.com/salatmaster/errorprone-idea/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/salatmaster/errorprone-idea/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/salatmaster/errorprone-idea/releases/tag/v0.1.0
