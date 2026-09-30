package io.github.salatmaster.errorprone

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanel
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanelProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewState
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.analysis.problemsView.toolWindow.Root
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.pom.Navigatable
import com.intellij.ui.SimpleTextAttributes
import java.util.function.Supplier
import javax.swing.Icon

/**
 * The "Error Prone" tab of the Problems tool window: every stored diagnostic of the project, grouped
 * by file. The platform's own tabs sit on an internal collector, so this one brings its own Root over
 * ErrorProneDiagnostics.
 */
class ErrorProneProblemsTabProvider(private val project: Project) : ProblemsViewPanelProvider {

    override fun create(): ProblemsViewTab {
        val panel = ProblemsViewPanel(project, TAB_ID, ProblemsViewState(), Supplier { "Error Prone" })
        panel.treeModel.root = ErrorProneRoot(panel)
        panel.tree.emptyText.apply {
            text = "No Error Prone diagnostics yet"
            appendLine("Run Error Prone", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runErrorProne(project) }
        }
        return panel
    }

    companion object {
        const val TAB_ID = "ErrorProne"
    }
}

private class ErrorProneRoot(panel: ProblemsViewPanel) : Root(panel) {

    private val store = ErrorProneDiagnostics.getInstance(panel.project)

    private val provider = object : ProblemsProvider {
        override val project: Project = panel.project
    }

    init {
        Disposer.register(this, provider)
        panel.project.messageBus.connect(this).subscribe(
            ErrorProneDiagnostics.TOPIC,
            ErrorProneDiagnosticsListener {
                // Published on the Gradle event thread; the tree changes on the EDT.
                ApplicationManager.getApplication().invokeLater({ structureChanged() }, panel.project.disposed)
            },
        )
    }

    override fun getProblemCount(): Int = store.count()

    override fun getProblemFiles(): Collection<VirtualFile> = store.files()

    override fun getFileProblemCount(file: VirtualFile): Int = getFileProblems(file).size

    override fun getFileProblems(file: VirtualFile): Collection<Problem> = nonBlockingRead {
        store.forFile(file).map { ErrorProneProblem(provider, file, it.marker, it.diagnostic.text, it.diagnostic.severity) }
    }

    override fun getOtherProblemCount(): Int = 0

    override fun getOtherProblems(): Collection<Problem> = emptyList()
}

/**
 * The tree is rebuilt only when a build reports, not on every keystroke, so the position is read from
 * the marker whenever it is asked for: clicking a problem after editing the file still lands on the
 * token (ProblemNode navigates through a Navigatable problem rather than its cached line).
 */
private data class ErrorProneProblem(
    override val provider: ProblemsProvider,
    override val file: VirtualFile,
    private val marker: RangeMarker,
    override val text: String,
    private val severity: ErrorProneSeverity,
) : FileProblem, Navigatable {

    private val offset: Int get() = marker.startOffset.coerceAtMost(marker.document.textLength)

    override val line: Int get() = marker.document.getLineNumber(offset)

    override val column: Int get() = offset - marker.document.getLineStartOffset(line)

    override fun navigate(requestFocus: Boolean) {
        OpenFileDescriptor(provider.project, file, offset).navigate(requestFocus)
    }

    override fun canNavigate(): Boolean = marker.isValid

    override fun canNavigateToSource(): Boolean = canNavigate()

    override val icon: Icon
        get() = when (severity) {
            ErrorProneSeverity.ERROR -> HighlightDisplayLevel.ERROR.icon
            ErrorProneSeverity.WARNING -> HighlightDisplayLevel.WARNING.icon
            ErrorProneSeverity.NOTE -> HighlightDisplayLevel.WEAK_WARNING.icon
        }
}
