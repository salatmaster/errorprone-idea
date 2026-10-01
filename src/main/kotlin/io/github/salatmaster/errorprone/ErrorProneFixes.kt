package io.github.salatmaster.errorprone

import com.intellij.analysis.AnalysisScope
import com.intellij.analysis.BaseAnalysisAction
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInspection.JavaSuppressionUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.PatchSyntaxException
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.vcs.changes.patch.ApplyPatchAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import com.intellij.psi.codeStyle.PackageEntry
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Marks a Gradle execution that writes Error Prone's fixes, with the tasks it patches. Those run the
 * patched checks and nothing else, so their diagnostics would wipe the others'; the Gradle hook keeps them
 * out, and keeps what the tasks they depend on report. Before [TREE_PATHS] every task is patched.
 */
internal val PATCH_BUILD: Key<Set<String>> = Key.create("errorprone.patch")

/**
 * Error Prone writes its fixes as one unified diff per compile task, every path relative to the
 * directory the patch is in. Returns the sections of [patch] for the files [keep] accepts, with
 * paths relative to [projectDir] as the IDE's Apply Patch expects them, or "" when none is kept.
 * Both directories must be real paths: Error Prone relativised against the one it was given. The
 * non-ASCII text Error Prone escaped in the lines it adds is written out again (see [unescapeNonAscii]),
 * unless the lines it replaces had the same escape: those the code already had stay as written.
 */
internal fun rebasePatch(patch: String, patchDir: Path, projectDir: Path, keep: (Path) -> Boolean = { true }): String {
    val out = StringBuilder()
    val lines = patch.lines()
    var keeping = false
    // The escapes in the removed lines of the change at hand; a context line ends a change.
    val removedEscapes = HashSet<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // ponytail: a header is "--- " followed by "+++ ", which a removed line starting "-- " followed
        // by an added one starting "++ " would imitate; counting hunk lines is the upgrade if Java
        // code ever does that at column 0.
        if (line.startsWith("--- ") && lines.getOrNull(i + 1)?.startsWith("+++ ") == true) {
            val file = patchDir.resolve(line.removePrefix("--- ").trim()).normalize()
            keeping = keep(file)
            if (keeping) {
                val relative = projectDir.relativize(file).toString().replace('\\', '/')
                out.append("--- ").append(relative).append('\n').append("+++ ").append(relative).append('\n')
            }
            i += 2
            continue
        }
        if (keeping && (line.isNotEmpty() || i < lines.lastIndex)) {
            val added = line.startsWith("+")
            if (line.startsWith("-")) UNICODE_ESCAPE.findAll(line).forEach { removedEscapes += it.groupValues[2].lowercase() }
            else if (!added) removedEscapes.clear()
            out.append(if (added) unescapeNonAscii(line, removedEscapes) else line).append('\n')
        }
        i++
    }
    return out.toString()
}

/**
 * Alt+Enter on an Error Prone highlight: silence the check with `@SuppressWarnings`, which Error Prone reads
 * itself, on the narrowest declaration the diagnostic is in (a variable, a method or field, a class); the
 * wider ones are its options. Every diagnostic of the check in that declaration goes with it, at once.
 * [targets] names each declaration, narrowest first, as they were when the highlight was made; [level]
 * is the one this suppresses in.
 */
internal class SuppressErrorProneFix(
    private val check: String,
    private val marker: RangeMarker,
    private val targets: List<String> = emptyList(),
    private val level: Int = 0,
) : IntentionActionWithOptions, PriorityAction {

    override fun getText(): String =
        targets.getOrNull(level)?.let { "Suppress '$check' for $it" } ?: "Suppress '$check' with @SuppressWarnings"

    // The submenu lists the wider declarations narrowest first, not by name.
    override fun getPriority(): PriorityAction.Priority = when (level) {
        0, 2 -> PriorityAction.Priority.NORMAL
        1 -> PriorityAction.Priority.HIGH
        else -> PriorityAction.Priority.LOW
    }

    override fun getFamilyName(): String = "Suppress Error Prone check"

    override fun getOptions(): List<IntentionAction> =
        if (level > 0) emptyList() else (1 until targets.size).map { SuppressErrorProneFix(check, marker, targets, it) }

    // The wider declarations, not the inspection's own options, which suppress nothing Error Prone reads.
    override fun getCombiningPolicy() = IntentionActionWithOptions.CombiningPolicy.IntentionOptionsOnly

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean =
        file != null && marker.isValid && suppressionTargets(file, marker.startOffset).size > level

    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val owner = file?.let { suppressionTargets(it, marker.startOffset).getOrNull(level) } ?: return
        // The preview runs this on a copy of the file; only the real edit settles diagnostics.
        val real = !IntentionPreviewUtils.isPreviewElement(file)
        val settled = if (!real) emptyList() else file.virtualFile?.let { ErrorProneDiagnostics.getInstance(project).forFile(it) }.orEmpty()
            .filter { it.diagnostic.check == check && owner.textRange.contains(it.range) }
            .map { it.marker }
        // Adds to an existing @SuppressWarnings rather than writing a second one.
        JavaSuppressionUtil.addSuppressAnnotation(project, owner, owner, check)
        if (real) dismissUndoably(project, marker.document, (settled + marker).distinct())
    }
}

/**
 * Suppresses each of [diagnostics] in the narrowest declaration around it, as one command: each
 * declaration gets its check once, however many of its diagnostics it holds. Returns how many it left
 * alone, outside any declaration (an import, say), which stay shown.
 */
internal fun suppressAll(project: Project, diagnostics: List<Pair<VirtualFile, Located>>): Int {
    val documents = PsiDocumentManager.getInstance(project)
    documents.commitAllDocuments()
    val byFile = diagnostics.groupBy({ it.first }, { it.second })
        .mapNotNull { (file, located) -> PsiManager.getInstance(project).findFile(file)?.let { it to located } }
    var placed = 0
    WriteCommandAction.writeCommandAction(project, *byFile.map { it.first }.toTypedArray())
        .withName("Suppress Error Prone Diagnostics")
        .withGlobalUndo()
        .run<RuntimeException> {
            for ((psi, located) in byFile) {
                // Found before any is annotated, while the offsets still fit the tree.
                val owned = located.mapNotNull { l -> suppressionTargets(psi, l.marker.startOffset).firstOrNull()?.let { it to l } }
                owned.map { (owner, l) -> owner to l.diagnostic.check }.distinct()
                    .forEach { (owner, check) -> JavaSuppressionUtil.addSuppressAnnotation(project, owner, owner, check) }
                documents.getDocument(psi)?.let { dismissUndoably(project, it, owned.map { (_, l) -> l.marker }) }
                placed += owned.size
            }
        }
    return diagnostics.size - placed
}

/**
 * The declarations around [offset] that `@SuppressWarnings` can go on, narrowest first. Not a lambda's
 * parameter, whose type may not be written out, nor an anonymous class, which has no modifiers.
 */
internal fun suppressionTargets(file: PsiFile, offset: Int): List<PsiModifierListOwner> =
    generateSequence(file.findElementAt(offset)) { it.parent }.takeWhile { it !is PsiFile }
        .filter {
            it is PsiLocalVariable || it is PsiMethod || it is PsiField ||
                (it is PsiParameter && it.declarationScope !is PsiLambdaExpression) ||
                (it is PsiClass && it !is PsiAnonymousClass && it !is PsiTypeParameter)
        }
        .map { it as PsiModifierListOwner }
        .toList()

/** How the suppression's text names [owner]: `method 'toString'`. */
internal fun describeTarget(owner: PsiModifierListOwner): String {
    val kind = when (owner) {
        is PsiParameter -> "parameter"
        is PsiLocalVariable -> "variable"
        is PsiMethod -> if (owner.isConstructor) "constructor" else "method"
        is PsiField -> "field"
        else -> "class"
    }
    return "$kind '${(owner as? PsiNamedElement)?.name}'"
}

/**
 * Alt+Enter on an Error Prone highlight that Error Prone has a fix for. The fix exists only inside
 * Error Prone, so a build of the task that reported it writes it out (see [fixWithErrorProne]). Error
 * Prone writes the fixes of a check for a whole file as one patch, imports included, so they cannot be
 * told apart per occurrence; the text says "in this file" for that reason.
 */
internal class ApplyErrorProneFix(private val located: Located, private val file: VirtualFile) : IntentionAction {

    private val check = located.diagnostic.check
    private val task = located.task

    /** Whether a build is writing this fix already, as of the last [isAvailable]. */
    private var writing = false

    override fun getText(): String =
        if (writing) "Error Prone is writing its fix for '$check'…" else "Apply Error Prone fix for '$check' in this file"

    override fun getFamilyName(): String = "Apply Error Prone fix"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        writing = ErrorProneBuilds.getInstance(project).isFixing(this.file, check)
        return true
    }

    override fun startInWriteAction(): Boolean = false

    /**
     * The line javac's "Did you mean" gave for the fix Error Prone applies: the whole fix exists only once
     * a build has written it. It is the first line the fix changes, usually the flagged one, and shown as
     * a change of that line when it reads like one.
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val fix = located.diagnostic.fixes.firstOrNull() ?: return IntentionPreviewInfo.EMPTY
        val document = editor.document
        val marker = located.marker
        if (!marker.isValid || marker.startOffset > document.textLength) return IntentionPreviewInfo.EMPTY
        val line = document.getLineNumber(marker.startOffset)
        val flagged = document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).trim()
        if (fix.isEmpty() || isEditOf(flagged, fix)) return IntentionPreviewInfo.CustomDiff(file.fileType, file.name, flagged, fix)
        return IntentionPreviewInfo.Html(
            HtmlBuilder().append("Error Prone's fix changes a line to").br()
                .append(HtmlChunk.tag("code").addText(fix)).toFragment()
        )
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val target = realPath(Path.of(this.file.path)) ?: return
        // The build takes seconds, and a second one would write a fix the first has already applied.
        val builds = ErrorProneBuilds.getInstance(project)
        val started = builds.startFix(this.file, check)
        editor?.let { HintManager.getInstance().showInformationHint(it, "Error Prone is writing its fix for '$check'…") }
        if (!started) return
        val documents = FileDocumentManager.getInstance()
        // The build reads the file from disk.
        documents.getDocument(this.file)?.let(documents::saveDocument)
        val root = task.substringBeforeLast('|')
        val name = "Error Prone fix for '$check' in ${this.file.name}"
        // Written until it is applied, or until it turns out there is nothing to apply.
        val done = { builds.finishFix(this.file, check) }
        try {
            fixWithErrorProne(project, root, listOf(task.substringAfterLast('|')), setOf(check), { it == target }, name, done) { patch ->
                // Read off the EDT and once indexing is over: the imports are looked up in the indexes, which
                // the classes the build just wrote may be refreshing.
                ReadAction.nonBlocking<FilePatch?> {
                    PsiManager.getInstance(project).findFile(this.file)?.let { readFix(it, VfsUtilCore.loadText(patch)) }
                }
                    .inSmartMode(project)
                    .expireWith(project)
                    .finishOnUiThread(ModalityState.nonModal()) { fix ->
                        try {
                            apply(project, patch, fix)
                        } finally {
                            done()
                        }
                    }
                    .submit(AppExecutorUtil.getAppExecutorService())
            }
        } catch (e: Throwable) {
            done()
            throw e
        }
    }

    /** Applies [fix], or says why not and leaves [patch] to review: never a dialog nobody asked for. */
    private fun apply(project: Project, patch: VirtualFile, fix: FilePatch?) {
        val why = when {
            fix == null -> "Error Prone's fix for '$check' could not be read"
            fix.missingClass != null -> "Error Prone's fix for '$check' uses ${fix.missingClass}, which this module does not have"
            !applyFix(project, file, check, fix) -> "The code Error Prone's fix for '$check' changes was edited while the fix was being written"
            else -> return
        }
        notifyErrorProne(
            project, "$why, so it was not applied.", NotificationType.INFORMATION,
            NotificationAction.createSimpleExpiring("Review in Apply Patch…") { showApplyPatch(project, patch) },
        )
    }
}

/** Whether [new] reads as an edit of [old] rather than of another line: at least half of the shorter is kept at either end. */
private fun isEditOf(old: String, new: String): Boolean {
    val prefix = old.commonPrefixWith(new).length
    val kept = prefix + old.substring(prefix).commonSuffixWith(new.substring(prefix)).length
    return kept * 2 >= minOf(old.length, new.length)
}

/**
 * Apply All Error Prone Fixes, under Build and Analyze: every fix Error Prone has for what it reports in
 * a scope chosen as for Inspect Code, generated code aside, in Apply Patch, where files can be left out
 * and each one diffed.
 */
class ApplyAllErrorProneFixesAction(
    /** Only these checks' fixes, when the Error Prone tab asks for one check's. */
    private val checks: Set<String>? = null,
) : BaseAnalysisAction("Error Prone Fixes", "Error Prone fixes"), DumbAware {

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project ?: return
        e.presentation.isVisible = GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()
        // Not the base class's verdict, which is off while indexing: nothing here needs the indexes.
        e.presentation.isEnabled = ErrorProneDiagnostics.getInstance(project).hasFix { !isGeneratedCode(project, it) }
    }

    /** Called on the EDT once the scope is chosen; a module or directory scope lists its files when first asked, so that happens off it. */
    public override fun analyze(project: Project, scope: AnalysisScope) {
        val inScope = { file: VirtualFile -> scope.contains(file) }
        ReadAction.nonBlocking<Map<String, FixRun>> { fixRuns(project, checks, inScope) }
            .expireWith(project)
            .finishOnUiThread(ModalityState.nonModal()) { runs -> fix(project, scope, inScope, runs) }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    private fun fix(project: Project, scope: AnalysisScope, inScope: (VirtualFile) -> Boolean, runs: Map<String, FixRun>) {
        if (runs.isEmpty()) {
            val what = checks?.joinToString() ?: "what it reports"
            notifyErrorProne(project, "Error Prone has no fix for $what in ${scope.displayName}.", NotificationType.INFORMATION)
            return
        }
        // Error Prone fixes the whole of every task it compiles; only the files in scope are kept.
        val keep = { path: Path ->
            nonBlockingRead { LocalFileSystem.getInstance().findFileByNioFile(path)?.let { inScope(it) && !isGeneratedCode(project, it) } == true }
        }
        for ((root, run) in runs) {
            fixWithErrorProne(project, root, run.tasks.toList(), run.checks, keep) { showApplyPatch(project, it) }
        }
    }
}

/** What one Gradle build compiles to write fixes: the [tasks] that reported them, and their [checks]. */
internal data class FixRun(val tasks: Set<String>, val checks: Set<String>)

/**
 * The fixes Error Prone has for the files [inScope] accepts, generated code aside, and for [checks] if
 * given, by the root of the Gradle build that reported them. Call in a read action.
 */
internal fun fixRuns(project: Project, checks: Set<String>? = null, inScope: (VirtualFile) -> Boolean): Map<String, FixRun> {
    val store = ErrorProneDiagnostics.getInstance(project)
    return store.files().filter { inScope(it) && !isGeneratedCode(project, it) }
        .flatMap(store::forFile)
        .filter { it.diagnostic.fixable && (checks == null || it.diagnostic.check in checks) }
        .groupBy { it.task.substringBeforeLast('|') }
        .mapValues { (_, located) -> FixRun(located.mapTo(HashSet()) { it.task.substringAfterLast('|') }, located.mapTo(HashSet()) { it.diagnostic.check }) }
}

/**
 * Whether [file] is regenerated rather than edited, so a fix there would not last: the IDE knows it as
 * generated (annotation processing, the idea plugin's generatedSourceDirs), or its source root sits in an
 * excluded folder, as a code generator's output in Gradle's build directory does. Call in a read action.
 */
internal fun isGeneratedCode(project: Project, file: VirtualFile): Boolean {
    if (GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)) return true
    val index = ProjectFileIndex.getInstance(project)
    return index.getSourceRootForFile(file)?.parent?.let(index::isExcluded) == true
}

/**
 * How Error Prone lays out the imports of its fixes, as near the project's Java code style as its choices
 * come: static imports first (Google's style) or IntelliJ's default layout. A fix that adds an import
 * reprints the whole block, so any other layout would move every import in the file.
 */
internal fun patchImportOrder(project: Project): String {
    val first = JavaCodeStyleSettings.getInstance(project).IMPORT_LAYOUT_TABLE.entries.firstOrNull { it != PackageEntry.BLANK_LINE_ENTRY }
    return if (first?.isStatic == true) "static-first" else "idea"
}

private val log = Logger.getInstance("io.github.salatmaster.errorprone.ErrorProneFixes")

/**
 * Runs a build of [tasks] in the Gradle build at [root], named [name], that writes Error Prone's fixes for
 * [checks], then hands those for the files [keep] accepts to [onPatch], on a background thread, as one
 * patch file, or calls [onNoPatch] when there is none. The build compiles [tasks] in full, like Run Error
 * Prone, and is kept away from the diagnostics store: it runs only [checks].
 */
internal fun fixWithErrorProne(
    project: Project,
    root: String,
    tasks: List<String>,
    checks: Collection<String>,
    keep: (Path) -> Boolean,
    name: String = "Error Prone fixes",
    onNoPatch: () -> Unit = {},
    onPatch: (VirtualFile) -> Unit,
) {
    val patchDir = patchDirectory()
    // In the background, as a step of a quick fix.
    runGradle(project, root, tasks, name, errorProneInitScript(checks, patchDir, patchImportOrder(project), targets = tasks), PATCH_BUILD, tasks.toSet()) {
        ApplicationManager.getApplication().executeOnPooledThread {
            // Read whether or not the build succeeded: a compile that fails still writes what it found.
            val patch = try {
                collectPatch(patchDir, realPath(Path.of(project.basePath ?: root)) ?: patchDir, keep)
            } catch (e: IOException) {
                log.warn("Could not read Error Prone's fixes from $patchDir", e)
                ""
            }
            if (patch.isBlank()) {
                notifyErrorProne(
                    project,
                    "Error Prone wrote no fix for ${checks.joinToString()}. Writing fixes needs the " +
                        "net.ltgt.errorprone Gradle plugin, and a build that gets as far as Error Prone.",
                    NotificationType.WARNING,
                )
                onNoPatch()
                return@executeOnPooledThread
            }
            val combined = patchDir.resolve("Error Prone fixes.patch").apply { writeText(patch) }
            // Found here rather than on the EDT, where a refresh is a slow operation.
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(combined)?.let(onPatch) ?: onNoPatch()
        }
    }
}

/**
 * Where one fix build writes its patches; a real path, as Error Prone writes paths relative to it. Named
 * afresh each time: the IDE's file system remembers what it read at a path in an earlier session and,
 * finding the file there again, hands back that content for a new patch.
 */
internal fun patchDirectory(): Path =
    FileUtil.createTempDirectory("errorprone-fixes-${UUID.randomUUID().toString().take(8)}", null).toPath().toRealPath()

private fun showApplyPatch(project: Project, patch: VirtualFile) {
    ApplicationManager.getApplication().invokeLater({ ApplyPatchAction.showApplyPatch(project, patch) }, project.disposed)
}

/**
 * Error Prone's fix for one file: the [changes] to make, the lines by index that the patch expects to
 * find around and under them ([before]), and the first class an import it adds names that the file
 * cannot see, if any: some of Error Prone's fixes use Guava whether the project has it or not.
 */
internal class FilePatch(val changes: List<Change>, val before: Map<Int, String>, val missingClass: String?)

/**
 * Reads Error Prone's single-file [patch] for [file], or null when it is not one. Looks the imports up
 * in the indexes, so call in a read action off the EDT, in smart mode.
 */
internal fun readFix(file: PsiFile, patch: String): FilePatch? {
    val hunks = try {
        PatchReader(patch).readTextPatches().singleOrNull()?.hunks
    } catch (_: PatchSyntaxException) {
        null
    } ?: return null
    val before = HashMap<Int, String>()
    for (hunk in hunks) {
        var line = hunk.startLineBefore
        for (patchLine in hunk.lines) if (patchLine.type != PatchLine.Type.ADD) before[line++] = patchLine.text
    }
    val missing = hunks.asSequence().flatMap { it.lines }.filter { it.type == PatchLine.Type.ADD }
        .firstNotNullOfOrNull { missingClass(file, it.text) }
    return FilePatch(changesOf(hunks), before, missing)
}

/**
 * Applies [fix] for [check] to [file] as one undoable edit, and hides the diagnostics of [check] it
 * settles until the edit is undone. Only the characters that change are replaced, so the other
 * diagnostics in the file keep their places. Returns false, having changed nothing, when the lines the
 * patch expects are not where it says any more: an edit elsewhere in the file since is fine. Call on
 * the EDT.
 */
internal fun applyFix(project: Project, file: VirtualFile, check: String, fix: FilePatch): Boolean {
    val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
    val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return false
    val fits = fix.before.all { (line, text) ->
        line < document.lineCount &&
            document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))) == text
    }
    if (!fits) return false
    val settled = ErrorProneDiagnostics.getInstance(project).forFile(file)
        .filter { it.diagnostic.check == check && it.diagnostic.fixable }
        .map { it.marker }
    WriteCommandAction.runWriteCommandAction(project, "Apply Error Prone Fix for '$check'", null, {
        fix.changes.asReversed().forEach { it.apply(document) }
        dismissUndoably(project, document, settled)
    }, psiFile)
    return true
}

/** Lines [line] onwards of a file, [removed] and replaced by [added]: one run of a patch hunk. */
internal class Change(val line: Int) {
    val removed = ArrayList<String>()
    val added = ArrayList<String>()

    /** Replaces only what differs, so a marker on a token the change keeps stays valid. */
    fun apply(document: Document) {
        val start = offset(document, line)
        val end = offset(document, line + removed.size)
        val old = document.getText(TextRange(start, end))
        val new = added.joinToString("") { "$it\n" }
        val prefix = old.commonPrefixWith(new).length
        val suffix = old.substring(prefix).commonSuffixWith(new.substring(prefix)).length
        document.replaceString(start + prefix, end - suffix, new.substring(prefix, new.length - suffix))
    }

    private fun offset(document: Document, line: Int) =
        if (line < document.lineCount) document.getLineStartOffset(line) else document.textLength
}

private fun changesOf(hunks: List<PatchHunk>): List<Change> {
    val changes = ArrayList<Change>()
    for (hunk in hunks) {
        var line = hunk.startLineBefore
        var change: Change? = null
        for (patchLine in hunk.lines) {
            when (patchLine.type) {
                PatchLine.Type.CONTEXT -> {
                    change = null
                    line++
                }
                PatchLine.Type.REMOVE -> {
                    (change ?: Change(line).also { changes += it; change = it }).removed += patchLine.text
                    line++
                }
                PatchLine.Type.ADD -> (change ?: Change(line).also { changes += it; change = it }).added += patchLine.text
            }
        }
    }
    return changes
}

private val IMPORT = Regex("""import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;""")

/** The class [line] imports, if it is an import and [file] cannot see that class. */
private fun missingClass(file: PsiFile, line: String): String? {
    val (static, name, star) = IMPORT.matchEntire(line.trim())?.destructured ?: return null
    val className = when {
        static.isNotEmpty() && star.isEmpty() -> name.substringBeforeLast('.')
        static.isEmpty() && star.isNotEmpty() -> return null // a package
        else -> name
    }
    return className.takeIf { JavaPsiFacade.getInstance(file.project).findClass(it, file.resolveScope) == null }
}

/**
 * Hides the diagnostics at [markers] as part of the edit in progress, which settled them: undoing the
 * edit brings them back, redoing it hides them again. Call inside that edit's command.
 */
private fun dismissUndoably(project: Project, document: Document, markers: List<RangeMarker>) {
    if (markers.isEmpty()) return
    val store = ErrorProneDiagnostics.getInstance(project)
    store.dismiss(markers)
    UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(document) {
        override fun undo() = store.dismiss(markers, dismissed = false)
        override fun redo() = store.dismiss(markers)
    })
}

/** Every patch Error Prone wrote under [patchDir], as one, with paths relative to [projectDir]. */
internal fun collectPatch(patchDir: Path, projectDir: Path, keep: (Path) -> Boolean): String =
    Files.walk(patchDir).use { paths -> paths.filter { it.name == "error-prone.patch" }.toList() }
        .joinToString("") { rebasePatch(it.readText(), it.parent, projectDir, keep) }

private fun realPath(path: Path): Path? = try {
    path.toRealPath()
} catch (_: IOException) {
    null
}
