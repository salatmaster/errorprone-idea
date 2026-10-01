package io.github.salatmaster.errorprone

import com.intellij.analysis.AnalysisScope
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanelProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.CopyProvider
import com.intellij.ide.DataManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.OccurenceNavigator
import com.intellij.ide.OccurenceNavigatorSupport
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.keymap.KeymapUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.*
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.EditSourceOnDoubleClickHandler
import com.intellij.util.EditSourceOnEnterKeyHandler
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
import java.io.File
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/** The Error Prone tab of the Problems tool window; see [ErrorProneTab]. */
class ErrorProneProblemsTabProvider(private val project: Project) : ProblemsViewPanelProvider {
    override fun create(): ProblemsViewTab = ErrorProneTab(project)
}

private const val GETTING_STARTED_URL = "https://github.com/salatmaster/errorprone-idea#getting-started"

/** The node the Error Prone tab has selected, for its context menu. */
private val SELECTED_NODE = DataKey.create<TabNode>("ErrorProne.TabNode")

/**
 * The Error Prone tab of the Problems tool window: the stored diagnostics grouped by check or by file,
 * a filter, the details of the selection and what can be done about it. The platform's own tabs sit on
 * an internal collector and group by file only, hence a panel of the plugin's own: the tool window
 * takes any component that is a ProblemsViewTab.
 */
internal class ErrorProneTab(private val project: Project) :
    OnePixelSplitter(false, "ErrorProne.Tab.Splitter", 0.6f), ProblemsViewTab, UiDataProvider, OccurenceNavigator, Disposable {

    private val properties = PropertiesComponent.getInstance(project)
    private var view = loadView(properties)
    private var items: List<TabItem> = emptyList()

    /** Why there is nothing to show, read with [items] when there is not. */
    private var empty = EmptyState("")

    private val model = DefaultTreeModel(DefaultMutableTreeNode())
    internal val tree = Tree(model).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = TabRenderer()
        // Every action acts on one diagnostic.
        selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
    }
    private val filter = SearchTextField(false)
    internal val status = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(3, 8)
    }
    internal val details: JPanel = WidthTrackingPanel()
    private val autoscroll = object : AutoScrollToSourceHandler() {
        override fun isAutoScrollMode() = view.autoscroll
        override fun setAutoScrollMode(state: Boolean) = change { it.copy(autoscroll = state) }
    }

    /** Next and Previous Occurrence, from the tab or from the editor, step through the diagnostics. */
    private val occurrences = object : OccurenceNavigatorSupport(tree) {
        override fun createDescriptorForNode(node: DefaultMutableTreeNode): Navigatable? =
            (node.userObject as? ItemNode)?.item?.let(::descriptorOf)

        override fun getNextOccurenceActionName() = "Next Error Prone Diagnostic"

        override fun getPreviousOccurenceActionName() = "Previous Error Prone Diagnostic"
    }

    private val contextActions = contextActions()

    init {
        TreeSpeedSearch.installOn(tree)
        EditSourceOnDoubleClickHandler.install(tree)
        EditSourceOnEnterKeyHandler.install(tree)
        autoscroll.install(tree)
        PopupHandler.installPopupMenu(tree, contextActions, "ErrorProneTabPopup")
        // Alt+Enter, as in the editor: what can be done with the selection.
        DumbAwareAction.create { showContextPopup() }
            .registerCustomShortcutSet(KeymapUtil.getActiveKeymapShortcuts(IdeActions.ACTION_SHOW_INTENTION_ACTIONS), tree, this)
        tree.addTreeSelectionListener { showDetails() }
        filter.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                view = view.copy(filter = filter.text)
                rebuild()
            }
        })
        val actionToolbar = ActionManager.getInstance().createActionToolbar("ErrorProneTab", toolbarActions(), false)
        actionToolbar.targetComponent = tree
        firstComponent = SimpleToolWindowPanel(false).apply {
            toolbar = actionToolbar.component
            setContent(JPanel(BorderLayout()).apply {
                add(filter, BorderLayout.NORTH)
                add(ScrollPaneFactory.createScrollPane(tree, true), BorderLayout.CENTER)
                add(status, BorderLayout.SOUTH)
            })
        }
        secondComponent = ScrollPaneFactory.createScrollPane(details, true)
        project.messageBus.connect(this).subscribe(ErrorProneDiagnostics.TOPIC, ErrorProneDiagnosticsListener { refresh() })
        refresh()
    }

    /**
     * Reads the store again off the EDT and rebuilds the tree. The store says it changed from any thread
     * (the Gradle event thread, an edit) and often, hence coalesced.
     */
    fun refresh() {
        ReadAction.nonBlocking<Pair<List<TabItem>, EmptyState?>> {
            val items = collectItems(project)
            items to if (items.isEmpty()) emptyState(setupOf(project), ErrorProneDiagnostics.getInstance(project).records().values) else null
        }
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) { (items, empty) ->
                this.items = items
                empty?.let { this.empty = it }
                rebuild()
            }
            .submit(AppExecutorUtil.getAppExecutorService())
    }

    /**
     * Rebuilds the tree from [items] as [view] shows them, keeping what was expanded and selected. When
     * the selection is gone (fixed, suppressed), the row that took its place is selected: going through
     * a list is one click per diagnostic.
     */
    private fun rebuild() {
        val expanded = TreeUtil.collectExpandedPaths(tree).mapTo(HashSet(), ::keyOf)
        val selected = tree.selectionPath?.let(::keyOf)
        val selectedRow = tree.leadSelectionRow
        val root = buildTree(items, view)
        model.setRoot(root)
        for (node in TreeUtil.treeNodeTraverser(root).preOrderDfsTraversal()) {
            if (node === root) continue
            val path = TreePath((node as DefaultMutableTreeNode).path)
            val key = keyOf(path)
            if (key in expanded) tree.expandPath(path)
            if (key == selected) tree.selectionPath = path
        }
        if (selected != null && tree.selectionPath == null && tree.rowCount > 0) {
            tree.setSelectionRow(selectedRow.coerceIn(0, tree.rowCount - 1))
        }
        val shown = items.filter(view::shows)
        val store = ErrorProneDiagnostics.getInstance(project)
        val failed = store.records().filterValues { it.outcome == CompileOutcome.FAILED }
        val capped = store.records().filterValues { it.capped }
        val builds = ErrorProneBuilds.getInstance(project)
        val run = builds.lastRun
        val update = store.lastUpdate
        status.icon = if (builds.running) AnimatedIcon.Default.INSTANCE else null
        status.text = buildString {
            if (builds.running) append("Running Error Prone… · ")
            project.serviceIfCreated<CompileOnEdit>()?.waiting?.let { append(it).append(" · ") }
            append(if (shown.size == items.size) count(items.size, "diagnostic") else "${shown.size} of ${count(items.size, "diagnostic")}")
            append(" · ").append(count(shown.distinctBy { it.diagnostic.check }.size, "check"))
            if (run != null && (update == null || run.at >= update.second)) {
                append(" · Run Error Prone at ${DateFormatUtil.formatTime(run.at)}: ")
                append(
                    when {
                        run.after < run.before -> "${run.before - run.after} fewer than before"
                        run.after > run.before -> "${run.after - run.before} more than before"
                        else -> "as many as before"
                    }
                )
            } else if (update != null) {
                append(" · updated by ${update.first.substringAfterLast('|')} at ${DateFormatUtil.formatTime(update.second)}")
            }
            if (failed.isNotEmpty()) append(" · ${count(failed.size, "compile")} failed")
            if (capped.isNotEmpty()) append(" · ${count(capped.size, "task")} at javac's limit")
        }
        // What the exceptions are about, which the line has no room for.
        status.toolTipText = (
            failed.map { (task, record) ->
                "${task.substringAfterLast('|')} failed at ${DateFormatUtil.formatTime(record.at)}: Error Prone reports nothing once " +
                    "javac finds an error, so what it reported there before may be out of date."
            } + capped.map { (task, _) ->
                "javac stopped at $JAVAC_MAX_WARNINGS warnings in ${task.substringAfterLast('|')}, so some of Error Prone's findings there are missing."
            }
        ).takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>") { StringUtil.escapeXmlEntities(it) }
        if (items.isEmpty()) {
            tree.emptyText.text = empty.text
            when (empty.action) {
                EmptyAction.RUN -> tree.emptyText.appendLine("Run Error Prone", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { locked { runErrorProne(project) } }
                EmptyAction.GETTING_STARTED ->
                    tree.emptyText.appendLine("How to add it", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { BrowserUtil.browse(GETTING_STARTED_URL) }
                null -> {}
            }
        } else {
            tree.emptyText.text = "Nothing matches"
            tree.emptyText.appendLine("Clear filter", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                filter.text = ""
                change { TabView(grouping = it.grouping, autoscroll = it.autoscroll) }
            }
        }
        // Found rather than handed over: customizeTabContent, which would hand it over, is internal API.
        ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROBLEMS_VIEW)
            ?.contentManagerIfCreated?.getContent(this)?.displayName = getName(items.size)
        showDetails()
    }

    /** Changes what the tab shows, remembers it for the project, and shows it. */
    private fun change(update: (TabView) -> TabView) {
        view = update(view).copy(filter = filter.text)
        saveView(properties, view)
        rebuild()
    }

    private fun keyOf(path: TreePath): String =
        path.path.drop(1).joinToString("/") { (TreeUtil.getUserObject(it) as TabNode).key }

    private fun selectedNode(): TabNode? = tree.selectionPath?.let { TreeUtil.getUserObject(it.lastPathComponent) as? TabNode }

    private fun showDetails() {
        details.removeAll()
        details.add(detailsOf(selectedNode()), BorderLayout.NORTH)
        details.revalidate()
        details.repaint()
    }

    private fun detailsOf(node: TabNode?): JComponent = panel {
        when (node) {
            is ItemNode -> {
                val item = node.item
                val diagnostic = item.diagnostic
                heading(diagnostic.check, diagnostic.severity)
                row { text(StringUtil.escapeXmlEntities(diagnostic.message)) }
                diagnostic.fixes.forEachIndexed { i, fix ->
                    row { label(if (i == 0) "Did you mean" else "Or") }
                    row {
                        if (fix.isEmpty()) {
                            label("Remove this line")
                        } else {
                            cell(JBTextArea(fix).apply {
                                isEditable = false
                                lineWrap = true
                                font = EditorUtil.getEditorFont()
                            }).align(AlignX.FILL)
                        }
                    }
                }
                if (diagnostic.fixes.size > 1 && canFix(item)) row { comment("Apply Fix writes the first.") }
                row {
                    if (canFix(item)) button("Apply Fix in File") { locked { applyFixInFile(item) } }
                    if (canFix(item)) button("Apply for This Check…") { locked { applyForCheck(diagnostic.check) } }
                    button("Suppress") { locked { suppress(item) } }
                }
                diagnostic.link?.let { row { browserLink("Documentation", it) } }
                row { comment(StringUtil.escapeXmlEntities(freshnessOf(item.located, ErrorProneDiagnostics.getInstance(project).records()) + ".")) }
            }
            is CheckNode -> {
                heading(node.check, node.severity)
                row { comment("${count(node.items.size, "diagnostic")} in ${count(node.files, "file")}") }
                nodeButtons(node.items)
                node.link?.let { row { browserLink("Documentation", it) } }
            }
            is FileNode -> {
                row {
                    icon(node.items.first().icon ?: AllIcons.FileTypes.Any_type)
                    label(node.file.name).bold()
                }
                for ((check, items) in node.items.groupBy { it.diagnostic.check }) {
                    row { label(check); comment(count(items.size, "diagnostic")) }
                }
                nodeButtons(node.items)
            }
            else -> row { comment("Select a diagnostic") }
        }
    }.apply { border = JBUI.Borders.empty(8, 12) }

    private fun Panel.heading(check: String, severity: ErrorProneSeverity) = row {
        icon(severity.icon)
        label(check).bold()
        label(severity.label).applyToComponent { foreground = UIUtil.getContextHelpForeground() }
    }

    private fun canFix(item: TabItem) = item.diagnostic.fixable && !item.generated

    /** What can be done with every diagnostic under a node, as the tab shows them. */
    private fun Panel.nodeButtons(items: List<TabItem>) = row {
        val fixable = items.filter(::canFix)
        if (fixable.isNotEmpty()) {
            val files = fixable.distinctBy { it.file }.size
            button("Apply Fixes in ${count(files, "File")}") { locked { applyShown(items) } }
        }
        val suppressible = items.filterNot { it.generated }
        if (suppressible.isNotEmpty()) button("Suppress All ${suppressible.size}…") { locked { suppressShown(items) } }
    }

    /**
     * Error Prone's fixes for [items], in their files, without asking for a scope: the tab has chosen it.
     * Error Prone fixes a check in a whole file at once, so a file shown for one diagnostic is fixed in full.
     */
    private fun applyShown(items: List<TabItem>) {
        val fixable = items.filter(::canFix)
        if (fixable.isEmpty()) return
        ApplyAllErrorProneFixesAction(fixable.mapTo(HashSet()) { it.diagnostic.check })
            .analyze(project, AnalysisScope(project, fixable.map { it.file }.distinct()))
    }

    /** Suppresses [items] after saying how many, generated code aside, where the next generation would drop it. */
    private fun suppressShown(items: List<TabItem>) {
        val targets = items.filterNot { it.generated }
        val files = targets.distinctBy { it.file }.size
        val answer = Messages.showOkCancelDialog(
            project,
            "Add @SuppressWarnings for ${count(targets.size, "diagnostic")} in ${count(files, "file")}? Each goes on the " +
                "narrowest declaration around it.",
            "Suppress All", "Suppress", Messages.getCancelButton(), Messages.getQuestionIcon(),
        )
        if (answer == Messages.OK) leftAlone(suppressAll(project, targets.map { it.file to it.located }))
    }

    private fun leftAlone(count: Int) {
        if (count == 0) return
        val text = if (count == 1) "1 diagnostic was left as it is" else "$count diagnostics were left as they are"
        notifyErrorProne(
            project, "$text: outside any declaration (on an import, say), where @SuppressWarnings cannot go.",
            NotificationType.INFORMATION,
        )
    }

    /**
     * A button's listener runs on the EDT without the lock that actions get from the action system, and
     * what the buttons do reads PSI and documents. Application.invokeLater runs it under the write-intent
     * lock (WriteIntentReadAction would too, but is experimental API).
     */
    private fun locked(run: () -> Unit) = ApplicationManager.getApplication().invokeLater(run)

    private fun applyFixInFile(item: TabItem) {
        ApplyErrorProneFix(item.located, item.file).invoke(project, null, null)
    }

    private fun applyForCheck(check: String) {
        val action = ApplyAllErrorProneFixesAction(setOf(check))
        val context = DataManager.getInstance().getDataContext(tree)
        ActionUtil.performAction(action, AnActionEvent.createEvent(action, context, null, "ErrorProneTab", ActionUiKind.NONE, null))
    }

    /** As Alt+Enter would, undoably; the suppression finds its declaration in the committed tree. */
    private fun suppress(item: TabItem) {
        val psi = PsiManager.getInstance(project).findFile(item.file) ?: return
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        if (suppressionTargets(psi, item.located.marker.startOffset).isEmpty()) return leftAlone(1)
        val check = item.diagnostic.check
        WriteCommandAction.runWriteCommandAction(project, "Suppress '$check'", null, {
            SuppressErrorProneFix(check, item.located.marker).invoke(project, null, psi)
        }, psi)
    }

    private fun showContextPopup() {
        val context = DataManager.getInstance().getDataContext(tree)
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, contextActions, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true)
        val row = tree.selectionPath?.let(tree::getPathBounds)
        if (row != null) popup.show(RelativePoint(tree, Point(row.x, row.y + row.height))) else popup.showInCenterOf(tree)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val node = selectedNode()
        sink[SELECTED_NODE] = node
        sink[CommonDataKeys.VIRTUAL_FILE] = (node as? ItemNode)?.item?.file ?: (node as? FileNode)?.file
        sink[CommonDataKeys.NAVIGATABLE] = navigatable()
        sink[PlatformDataKeys.COPY_PROVIDER] = copyProvider
    }

    /** Where the selection's code is now, which its marker knows, not where it was at the last rebuild. */
    internal fun navigatable(): Navigatable? = when (val node = selectedNode()) {
        is ItemNode -> descriptorOf(node.item)
        is FileNode -> OpenFileDescriptor(project, node.file)
        else -> null
    }

    private fun descriptorOf(item: TabItem): OpenFileDescriptor? =
        item.located.marker.takeIf { it.isValid }?.let { OpenFileDescriptor(project, item.file, it.startOffset) }

    /** Every diagnostic under the selection, a line each: where it is, its check and its message. */
    internal fun copyText(): String? {
        val selected = tree.selectionPath?.lastPathComponent as? DefaultMutableTreeNode ?: return null
        return TreeUtil.treeNodeTraverser(selected).preOrderDfsTraversal()
            .mapNotNull { ((it as DefaultMutableTreeNode).userObject as? ItemNode)?.item }
            .joinToString("\n") { "${it.path}:${it.currentLine + 1}: ${it.diagnostic.text}" }
            .ifEmpty { null }
    }

    private val copyProvider = object : CopyProvider {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun performCopy(dataContext: DataContext) {
            copyText()?.let { CopyPasteManager.getInstance().setContents(StringSelection(it)) }
        }
        override fun isCopyEnabled(dataContext: DataContext) = tree.selectionPath != null
        override fun isCopyVisible(dataContext: DataContext) = true
    }

    override fun hasNextOccurence() = occurrences.hasNextOccurence()
    override fun hasPreviousOccurence() = occurrences.hasPreviousOccurence()
    override fun goNextOccurence(): OccurenceNavigator.OccurenceInfo? = occurrences.goNextOccurence()
    override fun goPreviousOccurence(): OccurenceNavigator.OccurenceInfo? = occurrences.goPreviousOccurence()
    override fun getNextOccurenceActionName(): String = occurrences.nextOccurenceActionName
    override fun getPreviousOccurenceActionName(): String = occurrences.previousOccurenceActionName

    private fun toolbarActions(): ActionGroup {
        val actions = ActionManager.getInstance()
        val expander = DefaultTreeExpander(tree)
        val common = CommonActionsManager.getInstance()
        return DefaultActionGroup(
            listOfNotNull(
                actions.getAction("ErrorProne.Run"),
                actions.getAction("ErrorProne.ApplyAllFixes"),
                Separator.getInstance(),
                popup(
                    "Group By", AllIcons.Actions.GroupBy,
                    toggle("Check", null, { view.grouping == Grouping.CHECK }) { on -> if (on) change { it.copy(grouping = Grouping.CHECK) } },
                    toggle("File", null, { view.grouping == Grouping.FILE }) { on -> if (on) change { it.copy(grouping = Grouping.FILE) } },
                ),
                popup(
                    "Filter", AllIcons.General.Filter,
                    *ErrorProneSeverity.entries.map { severity ->
                        toggle("${severity.label}s", severity.icon, { severity in view.severities }) { on ->
                            change { it.copy(severities = if (on) it.severities + severity else it.severities - severity) }
                        }
                    }.toTypedArray(),
                    Separator.getInstance(),
                    toggle("Generated Code", null, { view.generated }) { on -> change { it.copy(generated = on) } },
                ),
                Separator.getInstance(),
                common.createPrevOccurenceAction(this),
                common.createNextOccurenceAction(this),
                common.createExpandAllAction(expander, tree),
                common.createCollapseAllAction(expander, tree),
                autoscroll.createToggleAction(),
            ),
        )
    }

    private fun contextActions() = DefaultActionGroup(
        nodeAction("Apply Fix in File", { (it as? ItemNode)?.item?.takeIf(::canFix) }, ::applyFixInFile),
        nodeAction("Apply for This Check…", { (it as? ItemNode)?.item?.takeIf(::canFix)?.diagnostic?.check }, ::applyForCheck),
        nodeAction("Apply Fixes", { itemsOf(it)?.takeIf { items -> items.any(::canFix) } }, ::applyShown),
        nodeAction("Suppress", { (it as? ItemNode)?.item }, ::suppress),
        nodeAction("Suppress All…", { itemsOf(it)?.takeIf { items -> items.any { item -> !item.generated } } }, ::suppressShown),
        Separator.getInstance(),
        nodeAction("Open Documentation", ::linkOf) { BrowserUtil.browse(it) },
        nodeAction("Copy Gradle Line That Turns the Check Off", { it.takeIf { node -> node is CheckNode || node is ItemNode } }) { copyGradleLine("disable") },
        nodeAction("Copy Gradle Line That Makes the Check an Error", { it.takeIf { node -> node is CheckNode || node is ItemNode } }) { copyGradleLine("error") },
        nodeAction("Copy Message", { (it as? ItemNode)?.item?.diagnostic?.text }) { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
    )

    /**
     * Copies what sets the selected check's [severity] in the build that reported it (`disable` or `error`),
     * and says where it goes. The plugin never edits a build script itself.
     */
    internal fun copyGradleLine(severity: String) {
        val item = when (val node = selectedNode()) {
            is ItemNode -> node.item
            is CheckNode -> node.items.first()
            else -> return
        }
        val check = item.diagnostic.check
        CopyPasteManager.getInstance().setContents(StringSelection(checkSeveritySnippet(File(item.located.task.substringBeforeLast('|')), severity, check)))
        val what = if (severity == "disable") "turns '$check' off" else "makes '$check' an error, which fails a build wherever it is found"
        notifyErrorProne(
            project,
            "Copied the line that $what, for the build's root script. The next build recompiles every Java source set in full.",
            NotificationType.INFORMATION,
        )
    }

    private fun itemsOf(node: TabNode?): List<TabItem>? = when (node) {
        is CheckNode -> node.items
        is FileNode -> node.items
        else -> null
    }

    private fun linkOf(node: TabNode?): String? = when (node) {
        is ItemNode -> node.item.diagnostic.link
        is CheckNode -> node.link
        else -> null
    }

    /** A context-menu action on what [of] finds in the selected node; hidden when it finds nothing. */
    private fun <T : Any> nodeAction(text: String, of: (TabNode?) -> T?, run: (T) -> Unit): AnAction = object : DumbAwareAction(text) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = of(e.getData(SELECTED_NODE)) != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            of(e.getData(SELECTED_NODE))?.let(run)
        }
    }

    private fun toggle(text: String, icon: Icon?, isOn: () -> Boolean, turn: (Boolean) -> Unit): AnAction =
        object : DumbAwareToggleAction(text, null, icon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun isSelected(e: AnActionEvent) = isOn()
            override fun setSelected(e: AnActionEvent, state: Boolean) = turn(state)
        }

    private fun popup(text: String, icon: Icon, vararg actions: AnAction) =
        DefaultActionGroup(text, true).apply {
            addAll(*actions)
            templatePresentation.icon = icon
        }

    override fun getTabId(): String = "ErrorProne"

    /** As the platform's tabs show it: the name, then the count in grey. */
    override fun getName(count: Int): String {
        if (count <= 0) return "Error Prone"
        val grey = ColorUtil.toHtmlColor(NamedColorUtil.getInactiveTextColor())
        return "<html><body><table cellpadding='0' cellspacing='0'><tr><td><nobr>Error Prone</nobr></td>" +
            "<td width='${JBUI.scale(8)}'></td><td><font color='$grey'>$count</font></td></tr></table></body></html>"
    }

    override fun orientationChangedTo(vertical: Boolean) {
        orientation = vertical
    }

    /** The tab's content disposes of it, as it does of any Disposable component. */
    override fun dispose() {}
}

/** Takes the viewport's width, so the details wrap rather than scroll sideways. */
private class WidthTrackingPanel : JPanel(BorderLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
        if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}

private val ErrorProneSeverity.label: String get() = name.lowercase().replaceFirstChar(Char::uppercase)

private fun count(n: Int, noun: String) = "$n ${StringUtil.pluralize(noun, n)}"

internal class TabRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
        when (val node = TreeUtil.getUserObject(value)) {
            is CheckNode -> {
                icon = node.severity.icon
                append(node.check, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  ${node.items.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (node.files > 1) append(" · ${node.files} files", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (node.fixable.isNotEmpty()) {
                    append("  ${if (node.fixable.size == node.items.size) "fixable" else "${node.fixable.size} of ${node.items.size} fixable"}", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
            is FileNode -> {
                icon = node.items.first().icon
                append(node.file.name, if (node.generated) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                // Three Util.java apart.
                node.items.first().pkg.takeIf { it.isNotEmpty() }?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                node.module?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                if (node.generated) append("  generated", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                if (!expanded) append("  ${node.items.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is ItemNode -> {
                // Grouped by file, no parent says how severe it is.
                if (node.showCheck) icon = node.item.diagnostic.severity.icon
                // Where the code is now: an edit away from every diagnostic does not rebuild the tree.
                append("${node.item.currentLine + 1}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (node.showCheck) append("${node.item.diagnostic.check}  ", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append(node.item.diagnostic.message)
            }
        }
    }
}
