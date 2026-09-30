package io.github.salatmaster.errorprone

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInspection.JavaSuppressionUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
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
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vcs.changes.patch.ApplyPatchAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.concurrency.AppExecutorUtil
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Marks a Gradle execution that only writes Error Prone's fixes. Such a build runs the patched checks
 * and nothing else, so its diagnostics would wipe the others'; the Gradle hook leaves it alone.
 */
internal val PATCH_BUILD: Key<Boolean> = Key.create("errorprone.patch")

/**
 * Error Prone writes its fixes as one unified diff per compile task, every path relative to the
 * directory the patch is in. Returns the sections of [patch] for the files [keep] accepts, with
 * paths relative to [projectDir] as the IDE's Apply Patch expects them, or "" when none is kept.
 * Both directories must be real paths: Error Prone relativised against the one it was given.
 */
internal fun rebasePatch(patch: String, patchDir: Path, projectDir: Path, keep: (Path) -> Boolean = { true }): String {
    val out = StringBuilder()
    val lines = patch.lines()
    var keeping = false
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
        if (keeping && (line.isNotEmpty() || i < lines.lastIndex)) out.append(line).append('\n')
        i++
    }
    return out.toString()
}

/**
 * Alt+Enter on an Error Prone highlight: silence the check with `@SuppressWarnings`, which Error Prone
 * reads itself, on the method, field or class the diagnostic is in.
 */
internal class SuppressErrorProneFix(private val check: String, private val marker: RangeMarker) : IntentionAction {

    override fun getText(): String = "Suppress '$check' with @SuppressWarnings"

    override fun getFamilyName(): String = "Suppress Error Prone check"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = file != null && marker.isValid

    override fun startInWriteAction(): Boolean = true

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val element = file?.findElementAt(marker.startOffset) ?: return
        val owner = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, PsiField::class.java, PsiClass::class.java)
            as? PsiModifierListOwner ?: return
        // Adds to an existing @SuppressWarnings rather than writing a second one.
        JavaSuppressionUtil.addSuppressAnnotation(project, owner, owner, check)
        // The preview runs this on a copy of the file; only the real edit settles the diagnostic.
        if (!IntentionPreviewUtils.isPreviewElement(file)) dismissUndoably(project, marker.document, listOf(marker))
    }
}

/**
 * Alt+Enter on an Error Prone highlight that Error Prone has a fix for. The fix exists only inside
 * Error Prone, so a build of the task that reported it writes it out (see [fixWithErrorProne]). Error
 * Prone writes the fixes of a check for a whole file as one patch, imports included, so they cannot be
 * told apart per occurrence; the text says "in this file" for that reason.
 */
internal class ApplyErrorProneFix(
    private val check: String,
    private val task: String,
    private val file: VirtualFile,
) : IntentionAction {

    override fun getText(): String = "Apply Error Prone fix for '$check' in this file"

    override fun getFamilyName(): String = "Apply Error Prone fix"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean = true

    override fun startInWriteAction(): Boolean = false

    // Nothing to preview before the build has run.
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo = IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val target = realPath(Path.of(this.file.path)) ?: return
        val documents = FileDocumentManager.getInstance()
        // The build reads the file from disk.
        documents.getDocument(this.file)?.let(documents::saveDocument)
        val root = task.substringBeforeLast('|')
        fixWithErrorProne(project, root, listOf(task.substringAfterLast('|')), setOf(check), { it == target }) { patch ->
            // Read off the EDT and once indexing is over: the imports are looked up in the indexes, which
            // the classes the build just wrote may be refreshing.
            ReadAction.nonBlocking<FilePatch?> {
                PsiManager.getInstance(project).findFile(this.file)?.let { readFix(it, VfsUtilCore.loadText(patch)) }
            }
                .inSmartMode(project)
                .expireWith(project)
                .finishOnUiThread(ModalityState.nonModal()) { fix -> apply(project, patch, fix) }
                .submit(AppExecutorUtil.getAppExecutorService())
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
        NotificationGroupManager.getInstance().getNotificationGroup("Error Prone")
            .createNotification("$why, so it was not applied.", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring("Review in Apply Patch…") { showApplyPatch(project, patch) })
            .notify(project)
    }
}

/**
 * Build | Apply All Error Prone Fixes: every fix Error Prone has for the checks it currently reports,
 * across every linked Gradle build, in Apply Patch, where files can be left out and each one diffed.
 */
class ApplyAllErrorProneFixesAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()
        e.presentation.isEnabled = project != null && ErrorProneDiagnostics.getInstance(project).fixableChecks().isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val checks = ErrorProneDiagnostics.getInstance(project).fixableChecks()
        if (checks.isEmpty()) return
        for (linked in GradleSettings.getInstance(project).linkedProjectsSettings) {
            fixWithErrorProne(project, linked.externalProjectPath, listOf(ERROR_PRONE_TASK), checks, { true }) {
                showApplyPatch(project, it)
            }
        }
    }
}

private val log = Logger.getInstance("io.github.salatmaster.errorprone.ErrorProneFixes")

/**
 * Runs a build of [tasks] in the Gradle build at [root] that writes Error Prone's fixes for [checks],
 * then hands those for the files [keep] accepts to [onPatch], on a background thread, as one patch file. The build
 * compiles in full, like Run Error Prone, and is kept away from the diagnostics store: it runs only
 * [checks].
 */
internal fun fixWithErrorProne(
    project: Project,
    root: String,
    tasks: List<String>,
    checks: Collection<String>,
    keep: (Path) -> Boolean,
    onPatch: (VirtualFile) -> Unit,
) {
    // Real paths throughout: Error Prone writes paths relative to the directory it was given.
    val patchDir = FileUtil.createTempDirectory("errorprone-fixes", null).toPath().toRealPath()
    // In the background, as a step of a quick fix.
    runGradle(project, root, tasks, "Error Prone fixes", errorProneInitScript(checks, patchDir), PATCH_BUILD) {
        ApplicationManager.getApplication().executeOnPooledThread { collectFixes(project, root, patchDir, checks, keep, onPatch) }
    }
}

private fun collectFixes(
    project: Project,
    root: String,
    patchDir: Path,
    checks: Collection<String>,
    keep: (Path) -> Boolean,
    onPatch: (VirtualFile) -> Unit,
) {
    // Read whether or not the build succeeded: a compile that fails still writes what it found.
    val patch = try {
        collectPatch(patchDir, realPath(Path.of(project.basePath ?: root)) ?: patchDir, keep)
    } catch (e: IOException) {
        log.warn("Could not read Error Prone's fixes from $patchDir", e)
        ""
    }
    if (patch.isBlank()) {
        NotificationGroupManager.getInstance().getNotificationGroup("Error Prone")
            .createNotification(
                "Error Prone wrote no fix for ${checks.joinToString()}. Writing fixes needs the " +
                    "net.ltgt.errorprone Gradle plugin, and a build that gets as far as Error Prone.",
                NotificationType.WARNING,
            )
            .notify(project)
        return
    }
    val combined = patchDir.resolve("Error Prone fixes.patch").apply { writeText(patch) }
    // Found here rather than on the EDT, where a refresh is a slow operation.
    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(combined)?.let(onPatch)
}

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
