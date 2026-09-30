package io.github.salatmaster.errorprone

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.Callable

/** What a finished Gradle task means for the diagnostics it reported before. */
enum class CompileOutcome {
    /** Compiled every file: what it reported now is the whole truth. */
    FULL,

    /** Compiled some files, incrementally: only what it reported is new. */
    PARTIAL,

    /**
     * Failed. What it reported is new, and its silence about the rest means nothing: Error Prone
     * reports nothing at all once javac finds an error, which a file saved half-way through an edit has.
     */
    FAILED,

    /** Compiled nothing: up to date, loaded from the build cache, or skipped. */
    NONE,
}

/**
 * A diagnostic and the range it covers in its file right now. [marker] keeps tracking edits after
 * [range] was read, for whoever needs the position later (the Problems tab navigates with it).
 * [task] is the store's key for what reported it: the build root and the task path, split by `|`.
 */
data class Located(val diagnostic: ErrorProneDiagnostic, val range: TextRange, val marker: RangeMarker, val task: String)

fun interface ErrorProneDiagnosticsListener {
    fun diagnosticsChanged()
}

/**
 * The Error Prone diagnostics of one project, as its Gradle builds last reported them.
 *
 * Kept per task, because a task is what Gradle reports on as a unit: a compile task that ran in
 * full replaces everything it said before, one that ran incrementally only adds to it (see
 * [mergeTask]). Each diagnostic is anchored to a RangeMarker as it arrives, so an edit made after
 * the build moves the highlight with the code instead of leaving it on the wrong token.
 *
 * Kept across restarts in the project's cache (not under .idea): the first build after a restart
 * usually compiles nothing, and would otherwise leave the editor blank until Run Error Prone.
 */
@Service(Service.Level.PROJECT)
@State(name = "ErrorProneDiagnostics", storages = [Storage(StoragePathMacros.CACHE_FILE)])
class ErrorProneDiagnostics(private val project: Project) : PersistentStateComponent<ErrorProneDiagnostics.Saved> {

    /** What the last session left, as the platform stores it; see [getState]. */
    class Saved {
        var files: MutableList<SavedFile> = ArrayList()
    }

    class SavedFile {
        var task: String = ""
        var url: String = ""
        var timeStamp: Long = 0
        var diagnostics: MutableList<SavedDiagnostic> = ArrayList()
    }

    /** A diagnostic with where its marker was when saved: [start] and [end] are document offsets. */
    class SavedDiagnostic {
        var start: Int = 0
        var end: Int = 0
        var path: String = ""
        var line: Int = 0
        var column: Int = 0
        var length: Int = 0
        var check: String = ""
        var severity: String = ""
        var message: String = ""
        var link: String? = null
        var suggestion: String? = null
        var fixable: Boolean = false
    }

    /** [line] is the diagnostic's line as the build saw it, whitespace aside; see [shown]. */
    private class Anchored(val diagnostic: ErrorProneDiagnostic, val marker: RangeMarker, val line: String) {
        /** Settled by an edit before the next build, which can be undone; see [dismiss]. */
        @Volatile
        var dismissed = false
    }

    private class FileEntry(val file: VirtualFile, val recordedAt: Long, val items: List<Anchored>)

    private val lock = Any()

    /** Task path → file path → diagnostics. Replaced, never mutated, so readers need no lock. */
    @Volatile
    private var byTask: Map<String, Map<String, FileEntry>> = emptyMap()

    /** Loaded from the last session and not put back yet; [restore] does that once the project is open. */
    @Volatile
    private var saved: Saved? = null

    /** The task (the store's key for it) that last changed something, and when; null until a build does this session. */
    @Volatile
    var lastUpdate: Pair<String, Long>? = null
        private set

    override fun getState(): Saved = Saved().apply {
        for ((task, entries) in byTask) {
            for (entry in entries.values) {
                // Offsets in a document with unsaved edits do not fit the file the next session reads.
                // The IDE saves documents before it saves state on close, so nothing is lost for good.
                if (!entry.file.isValid || FileDocumentManager.getInstance().isFileModified(entry.file)) continue
                val items = entry.shown().map { it.toSaved() }
                if (items.isEmpty()) continue
                files += SavedFile().also {
                    it.task = task
                    it.url = entry.file.url
                    it.timeStamp = entry.file.timeStamp
                    it.diagnostics = items.toMutableList()
                }
            }
        }
    }

    override fun loadState(state: Saved) {
        saved = state
    }

    /**
     * Puts back what the last session saved, except for files changed since (a pull or a checkout
     * while the IDE was closed): their offsets no longer fit. A build that finished first wins.
     */
    fun restore() {
        val state = saved ?: return
        saved = null
        val restored = nonBlockingRead {
            val byTaskRestored = HashMap<String, MutableMap<String, FileEntry>>()
            for (savedFile in state.files) {
                val file = VirtualFileManager.getInstance().findFileByUrl(savedFile.url) ?: continue
                if (!file.isValid || file.timeStamp != savedFile.timeStamp) continue
                val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
                val items = savedFile.diagnostics.mapNotNull { it.toAnchored(document) }
                if (items.isNotEmpty()) {
                    byTaskRestored.getOrPut(savedFile.task) { HashMap() }[file.path] = FileEntry(file, savedFile.timeStamp, items)
                }
            }
            byTaskRestored
        }
        val changed = HashSet<VirtualFile>()
        synchronized(lock) {
            for ((task, entries) in restored) {
                if (task in byTask) {
                    entries.values.forEach { entry -> entry.items.forEach { it.marker.dispose() } }
                } else {
                    byTask = byTask + (task to entries)
                    entries.values.forEach { changed += it.file }
                }
            }
        }
        refresh(changed)
    }

    /**
     * Records what [task] reported when it finished. The Tooling API delivers events one at a time
     * on one thread, which is what keeps commits in build order.
     */
    fun commit(task: String, outcome: CompileOutcome, diagnostics: Map<VirtualFile, List<ErrorProneDiagnostic>>) {
        if (outcome == CompileOutcome.NONE) return
        // Every task of every build ends up here, and nearly all of them never reported anything.
        if (diagnostics.isEmpty() && task !in byTask) return

        val now = System.currentTimeMillis()
        val fresh = nonBlockingRead { anchor(diagnostics, now) }
        val changed = HashSet<VirtualFile>()
        synchronized(lock) {
            val old = byTask[task].orEmpty()
            // ponytail: a file saved during the build, after javac read it, looks unchanged here and
            // keeps diagnostics the next incremental build no longer reports. Run Error Prone (FULL)
            // clears it; per-file content hashes would be the upgrade.
            val merged = mergeTask(old, fresh, outcome) { !it.file.isValid || it.file.timeStamp > it.recordedAt }
            for (path in old.keys + merged.keys) {
                val before = old[path]
                val after = merged[path]
                if (before === after) continue
                before?.let { entry ->
                    entry.items.forEach { it.marker.dispose() }
                    changed += entry.file
                }
                after?.let { changed += it.file }
            }
            byTask = if (merged.isEmpty()) byTask - task else byTask + (task to merged)
        }
        if (changed.isNotEmpty()) lastUpdate = task to now
        refresh(changed)
    }

    /** The diagnostics of [file] whose code still exists, at their current ranges. Call in a read action. */
    fun forFile(file: VirtualFile): List<Located> =
        byTask.flatMap { (task, entries) -> entries[file.path]?.shown().orEmpty().map { task to it } }
            .map { (task, item) -> Located(item.diagnostic, item.marker.textRange, item.marker, task) }

    /** Where the diagnostics of [file] are, those an edit has hidden included: what an edit near them re-checks. */
    fun markersIn(file: VirtualFile): List<RangeMarker> =
        byTask.values.mapNotNull { it[file.path] }.flatMap { it.items }.filter { it.marker.isValid && !it.dismissed }.map { it.marker }

    /** Tells the Problems tab that an edit may have hidden or shown diagnostics again. */
    fun editedNear() {
        project.messageBus.syncPublisher(TOPIC).diagnosticsChanged()
    }

    /** The checks Error Prone has a fix for somewhere in the project. */
    fun fixableChecks(): Set<String> =
        byTask.values.flatMap { it.values }.flatMap { it.shown() }.filter { it.diagnostic.fixable }.map { it.diagnostic.check }.toSet()

    /**
     * Hides the diagnostics at [markers] without waiting for a build, or with [dismissed] false shows
     * them again: an edit Error Prone would agree with (a suppression, its own fix) settles them, and
     * undoing that edit brings them back. The next build of their task replaces them either way.
     */
    fun dismiss(markers: Collection<RangeMarker>, dismissed: Boolean = true) {
        val changed = HashSet<VirtualFile>()
        for (entry in byTask.values.flatMap { it.values }) {
            for (item in entry.items) {
                if (markers.none { it === item.marker } || item.dismissed == dismissed) continue
                item.dismissed = dismissed
                changed += entry.file
            }
        }
        refresh(changed)
    }

    fun files(): Set<VirtualFile> =
        byTask.values.flatMap { it.values }.filter { it.file.isValid && it.shown().isNotEmpty() }.map { it.file }.toSet()

    fun count(): Int = byTask.values.sumOf { entries -> entries.values.sumOf { it.shown().size } }

    /**
     * What to show: a diagnostic goes while its line reads differently from the build's (deleted,
     * commented out, rewritten), until a build says what holds now. A deletion that starts at the
     * token leaves the marker valid, on the next line's code, so validity alone would move it there.
     */
    private fun FileEntry.shown(): List<Anchored> =
        items.filter { it.marker.isValid && !it.dismissed && lineAt(it.marker.document, it.marker.startOffset) == it.line }

    @TestOnly
    fun clear() {
        saved = null
        lastUpdate = null
        synchronized(lock) {
            byTask.values.forEach { entries -> entries.values.forEach { entry -> entry.items.forEach { it.marker.dispose() } } }
            byTask = emptyMap()
        }
    }

    private fun anchor(diagnostics: Map<VirtualFile, List<ErrorProneDiagnostic>>, now: Long): Map<String, FileEntry> {
        val result = HashMap<String, FileEntry>()
        for ((file, list) in diagnostics) {
            if (!file.isValid) continue
            val document = FileDocumentManager.getInstance().getDocument(file) ?: continue
            val items = list.mapNotNull { diagnostic ->
                rangeIn(document, diagnostic)?.let { Anchored(diagnostic, document.createRangeMarker(it), lineAt(document, it.startOffset)) }
            }
            if (items.isNotEmpty()) result[file.path] = FileEntry(file, now, items)
        }
        return result
    }

    private fun refresh(files: Set<VirtualFile>) {
        if (files.isEmpty()) return
        // Inside the read action: restart reads the file's document, which for a file no editor has
        // open means loading it through FileDocumentManager.
        nonBlockingRead {
            val daemon = DaemonCodeAnalyzer.getInstance(project)
            files.filter { it.isValid }
                .mapNotNull { PsiManager.getInstance(project).findFile(it) }
                .forEach { daemon.restart(it, "Error Prone diagnostics changed") }
        }
        project.messageBus.syncPublisher(TOPIC).diagnosticsChanged()
    }

    private fun Anchored.toSaved() = SavedDiagnostic().also {
        it.start = marker.startOffset
        it.end = marker.endOffset
        it.path = diagnostic.path
        it.line = diagnostic.line
        it.column = diagnostic.column
        it.length = diagnostic.length
        it.check = diagnostic.check
        it.severity = diagnostic.severity.name
        it.message = diagnostic.message
        it.link = diagnostic.link
        it.suggestion = diagnostic.suggestion
        it.fixable = diagnostic.fixable
    }

    private fun SavedDiagnostic.toAnchored(document: Document): Anchored? {
        val severity = ErrorProneSeverity.entries.firstOrNull { it.name == severity } ?: return null
        if (start < 0 || start > end || end > document.textLength) return null
        val diagnostic = ErrorProneDiagnostic(path, line, column, length, check, severity, message, link, suggestion, fixable)
        return Anchored(diagnostic, document.createRangeMarker(start, end), lineAt(document, start))
    }

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC: Topic<ErrorProneDiagnosticsListener> = Topic(ErrorProneDiagnosticsListener::class.java)

        fun getInstance(project: Project): ErrorProneDiagnostics = project.service()
    }
}

/**
 * What a task's diagnostics become once it finishes. [old] and [new] map a file path to what the
 * task reported for it; [isStale] tells whether a file changed after its old entry was recorded.
 */
internal fun <V> mergeTask(
    old: Map<String, V>,
    new: Map<String, V>,
    outcome: CompileOutcome,
    isStale: (V) -> Boolean,
): Map<String, V> = when (outcome) {
    CompileOutcome.FULL -> new
    CompileOutcome.NONE -> old
    // A changed file was certainly recompiled, so silence about it now means it is clean. An
    // unchanged file was most likely not recompiled, so its old diagnostics still hold.
    // ponytail: an unchanged file recompiled as a dependent that lost a warning keeps it until the
    // next FULL compile of the task (Run Error Prone always is one).
    CompileOutcome.PARTIAL -> old.filter { (path, entry) -> path !in new && !isStale(entry) } + new
    CompileOutcome.FAILED -> old.filter { (path, _) -> path !in new } + new
}

/**
 * Where [diagnostic] sits in [document], or null when its line no longer exists. A zero length
 * stays zero here; the annotator widens it to the token at that position.
 */
internal fun rangeIn(document: Document, diagnostic: ErrorProneDiagnostic): TextRange? {
    if (diagnostic.line > document.lineCount) return null
    val lineStart = document.getLineStartOffset(diagnostic.line - 1)
    val lineEnd = document.getLineEndOffset(diagnostic.line - 1)
    val column = expandedColumnToIndex(document.charsSequence.subSequence(lineStart, lineEnd), diagnostic.column)
    val start = lineStart + column
    return TextRange(start, (start + diagnostic.length).coerceAtMost(document.textLength))
}

/** The line at [offset] without its whitespace, so that reindenting it changes nothing. */
private fun lineAt(document: Document, offset: Int): String {
    val line = document.getLineNumber(offset.coerceIn(0, document.textLength))
    return document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
        .filterNot(Char::isWhitespace).toString()
}

/**
 * A read action that may run on any thread, including the Gradle event thread, without holding
 * back a pending write action. On the EDT it simply runs.
 */
internal fun <T> nonBlockingRead(compute: () -> T): T =
    ReadAction.nonBlocking(Callable(compute)).executeSynchronously()

/** Puts the last session's diagnostics back once the project is open; see [ErrorProneDiagnostics.restore]. */
class ErrorProneRestoreActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        ErrorProneDiagnostics.getInstance(project).restore()
    }
}
