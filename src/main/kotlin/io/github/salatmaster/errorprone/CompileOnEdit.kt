package io.github.salatmaster.errorprone

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemExecuteTaskTask
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiField
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.execution.build.CachedModuleDataFinder
import org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/**
 * Hears every edit and acts on those near an Error Prone diagnostic: on its line, or in the method,
 * field or initializer that holds it, where a fix often goes (an @Override above, a missing case
 * below). Such an edit may hide diagnostics or show them again (their line changed, see
 * ErrorProneDiagnostics), and has the file recompiled while [ErrorProneSettings.compileOnEdit] is on.
 * Once a file is due, every edit in it counts: the typing that started it goes on past its line.
 */
class ErrorProneEditListener : DocumentListener {
    override fun beforeDocumentChange(event: DocumentEvent) {
        val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
        for (project in ProjectManager.getInstance().openProjects) {
            if (project.isDisposed) continue
            val store = project.serviceIfCreated<ErrorProneDiagnostics>() ?: continue
            val markers = store.markersIn(file)
            if (markers.isEmpty()) continue
            val near = markers.any { zoneOf(project, it).intersects(event.offset, event.offset + event.oldLength) }
            if (near) store.editedNear()
            if (!ErrorProneSettings.getInstance().compileOnEdit) continue
            val compile = CompileOnEdit.getInstance(project)
            if (near || file in compile.pending) compile.schedule(listOf(file))
        }
    }
}

/** The line of [marker], and the method, field or initializer around it when the tree is up to date. */
private fun zoneOf(project: Project, marker: RangeMarker): TextRange {
    val document = marker.document
    val line = document.getLineNumber(marker.startOffset)
    val lineRange = TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))
    val documents = PsiDocumentManager.getInstance(project)
    // Only a committed tree has the document's offsets; while typing runs ahead of it, the line does.
    if (!documents.isCommitted(document)) return lineRange
    val holder = documents.getPsiFile(document)?.findElementAt(marker.startOffset)?.let {
        PsiTreeUtil.getParentOfType(it, PsiMethod::class.java, PsiField::class.java, PsiClassInitializer::class.java)
    }
    return holder?.textRange?.union(lineRange) ?: lineRange
}

/**
 * Saves the files [schedule]d and compiles their source sets, once editing has paused for [QUIET] and
 * no other Gradle build of the project is running: a second build at the same time would start a
 * second Gradle daemon, and the other build may well compile the files itself. The build is an
 * ordinary incremental one, so its diagnostics arrive like any other's, but it shows nowhere except
 * in the status bar: it runs because of typing, not because anyone asked for a build.
 */
@Service(Service.Level.PROJECT)
internal class CompileOnEdit(private val project: Project, scope: CoroutineScope) {

    /** Edited and not compiled yet. */
    val pending: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet()

    private val edits = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        @OptIn(FlowPreview::class)
        scope.launch {
            edits.debounce(QUIET).collect {
                // ponytail: waits out any Gradle task of the project, a long-running one (bootRun) too;
                // telling a compile from the rest is the upgrade if that bites.
                while (isGradleBusy()) delay(QUIET)
                compile()
            }
        }
    }

    fun schedule(files: Collection<VirtualFile>) {
        pending += files
        edits.tryEmit(Unit)
    }

    private suspend fun compile() {
        val files = pending.toList()
        pending -= files.toSet()
        val ready = readAction { compilable(files.filter { it.isValid }) }
        // Still pending, so the edit that fixes them brings them back here.
        pending += files.filter { it.isValid && it !in ready }
        val tasks = readAction { ready.mapNotNull { compileTaskOf(it) } }
        if (tasks.isEmpty()) return
        withContext(Dispatchers.EDT) {
            // Gradle compiles what is on disk.
            val documents = FileDocumentManager.getInstance()
            ready.mapNotNull { documents.getCachedDocument(it) }.forEach { documents.saveDocument(it) }
        }
        for ((root, paths) in tasks.groupBy({ it.first }, { it.second })) compileQuietly(root, paths.distinct())
    }

    /**
     * The [files] without an error the IDE can see, in them or elsewhere in their module. javac stops
     * at one, anywhere in the source set, and Error Prone then says nothing about anything: compiling
     * code half-way through an edit only fails. Call in a read action.
     */
    fun compilable(files: List<VirtualFile>): List<VirtualFile> {
        val problems = WolfTheProblemSolver.getInstance(project)
        val index = ProjectFileIndex.getInstance(project)
        return files.filter { file ->
            val psi = PsiManager.getInstance(project).findFile(file)
            val module = index.getModuleForFile(file)
            psi != null && !PsiTreeUtil.hasErrorElements(psi) &&
                !(if (module != null) problems.hasProblemFilesBeneath(module) else problems.isProblemFile(file))
        }
    }

    /**
     * Runs [tasks] of the Gradle build at [root] as Build Project would, but past the Build and Run
     * tool windows, which only an execution started through ExternalSystemUtil reaches. Returns when
     * the build has ended, however it ended.
     */
    private suspend fun compileQuietly(root: String, tasks: List<String>) {
        val configuration = ExternalSystemRunConfiguration(
            GradleConstants.SYSTEM_ID, project, GradleExternalTaskConfigurationType.getInstance().factory, "Error Prone",
        )
        configuration.settings.externalProjectPath = root
        configuration.settings.taskNames = tasks
        withBackgroundProgress(project, "Error Prone: compiling edited code", cancellable = false) {
            withContext(Dispatchers.IO) {
                ExternalSystemExecuteTaskTask(project, configuration.settings, null, configuration).execute(EmptyProgressIndicator())
            }
        }
    }

    /** The directory to run Gradle in and the path of the task that compiles [file], or null when Gradle does not. */
    private fun compileTaskOf(file: VirtualFile): Pair<String, String>? {
        val index = ProjectFileIndex.getInstance(project)
        if (!index.isInSourceContent(file)) return null
        val module = index.getModuleForFile(file) ?: return null
        val sourceSet = GradleProjectResolverUtil.getSourceSetName(module) ?: return null
        val gradle = CachedModuleDataFinder.getGradleModuleData(module) ?: return null
        return gradle.directoryToRunTask to gradle.getTaskPath(compileTaskName(sourceSet))
    }

    private fun isGradleBusy(): Boolean {
        val manager = ExternalSystemProcessingManager.getInstance()
        return manager.hasTaskOfTypeInProgress(ExternalSystemTaskType.EXECUTE_TASK, project) ||
            manager.hasTaskOfTypeInProgress(ExternalSystemTaskType.RESOLVE_PROJECT, project)
    }

    companion object {
        private val QUIET = 2.seconds

        fun getInstance(project: Project): CompileOnEdit = project.service()
    }
}

/** The task compiling the Java code of [sourceSet], as Gradle names it for a plain name. */
internal fun compileTaskName(sourceSet: String): String =
    if (sourceSet == "main") "compileJava" else "compile${sourceSet.replaceFirstChar { it.uppercase() }}Java"
