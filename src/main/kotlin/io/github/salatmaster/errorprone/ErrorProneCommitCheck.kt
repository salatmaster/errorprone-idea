package io.github.salatmaster.errorprone

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.changes.ui.BooleanCommitOption
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import com.intellij.openapi.vcs.checkin.CommitCheck
import com.intellij.openapi.vcs.checkin.CommitInfo
import com.intellij.openapi.vcs.checkin.CommitProblem
import com.intellij.openapi.vcs.checkin.CommitProblemWithDetails
import com.intellij.openapi.vcs.ui.RefreshableOnComponent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class ErrorProneCheckinHandlerFactory : CheckinHandlerFactory() {
    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler = ErrorProneCommitCheck(panel.project)
}

/**
 * Before a commit, the Error Prone diagnostics on the lines it changes, as the last builds reported them:
 * nothing is compiled, and a commit without any goes ahead unasked.
 */
class ErrorProneCommitCheck(private val project: Project) : CheckinHandler(), CommitCheck {

    override fun getExecutionOrder() = CommitCheck.ExecutionOrder.EARLY

    override fun isEnabled() = ErrorProneSettings.getInstance().checkBeforeCommit

    override suspend fun runCheck(commitInfo: CommitInfo): CommitProblem? {
        val store = ErrorProneDiagnostics.getInstance(project)
        // Only the committed files with diagnostics: their documents are loaded already, for the markers.
        val documents = readAction {
            commitInfo.committedChanges.mapNotNull { it.virtualFile }.filter { it.isValid }
                .mapNotNull { file -> store.forFile(file).firstOrNull()?.let { file to it.marker.document } }
        }
        if (documents.isEmpty()) return null
        // A changed file no editor shows has no line status tracker: one is made for the check. The commit
        // dialog may be modal, and asking for a tracker is safe in any modality.
        val requester = Any()
        val read = CompletableDeferred<Unit>()
        val edt = Dispatchers.EDT + ModalityState.any().asContextElement()
        withContext(edt) { requestTrackers(project, documents.map { it.second }, requester) { read.complete(Unit) } }
        try {
            withTimeoutOrNull(TRACKERS_TIMEOUT) { read.await() }
            val count = readAction {
                documents.sumOf { (file, document) ->
                    val changed = changedLines(project, file, document)
                    store.forFile(file).count { changed(document.getLineNumber(it.range.startOffset)) }
                }
            }
            return commitProblem(count)
        } finally {
            withContext(NonCancellable + edt) { releaseTrackers(project, documents.map { it.second }, requester) }
        }
    }

    override fun getBeforeCheckinConfigurationPanel(): RefreshableOnComponent =
        BooleanCommitOption(project, "Check Error Prone diagnostics", false, ErrorProneSettings.getInstance()::checkBeforeCommit)
}

/** How long the check waits for version control to read the base revisions of the files it looks at. */
private val TRACKERS_TIMEOUT = 10.seconds

/** What holds up a commit with [count] Error Prone diagnostics on its changed lines, or null for none. */
internal fun commitProblem(count: Int): CommitProblemWithDetails? = if (count == 0) null else object : CommitProblemWithDetails {
    override val text = "$count Error Prone ${StringUtil.pluralize("diagnostic", count)} on lines this commit changes"
    override val showDetailsAction = "Show in Error Prone Tab"
    override fun showDetails(project: Project) = showErrorProneTab(project) { it.showChangedOnly() }
}
