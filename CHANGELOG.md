# Changelog

All notable changes to this plugin are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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
