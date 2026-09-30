package io.github.salatmaster.errorprone

import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanelProvider
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewTab
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CommonActionsManager
import com.intellij.ide.DataManager
import com.intellij.ide.DefaultTreeExpander
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.ui.*
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
import java.awt.Rectangle
import java.awt.datatransfer.StringSelection
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

/** The node the Error Prone tab has selected, for its context menu. */
private val SELECTED_NODE = DataKey.create<TabNode>("ErrorProne.TabNode")

/**
 * The Error Prone tab of the Problems tool window: the stored diagnostics grouped by check or by file,
 * a filter, the details of the selection and what can be done about it. The platform's own tabs sit on
 * an internal collector and group by file only, hence a panel of the plugin's own: the tool window
 * takes any component that is a ProblemsViewTab.
 */
internal class ErrorProneTab(private val project: Project) :
    OnePixelSplitter(false, "ErrorProne.Tab.Splitter", 0.6f), ProblemsViewTab, UiDataProvider, Disposable {

    private val properties = PropertiesComponent.getInstance(project)
    private var view = loadView(properties)
    private var items: List<TabItem> = emptyList()

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

    init {
        TreeSpeedSearch.installOn(tree)
        EditSourceOnDoubleClickHandler.install(tree)
        EditSourceOnEnterKeyHandler.install(tree)
        autoscroll.install(tree)
        PopupHandler.installPopupMenu(tree, contextActions(), "ErrorProneTabPopup")
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
        ReadAction.nonBlocking<List<TabItem>> { collectItems(project) }
            .expireWith(this)
            .coalesceBy(this)
            .finishOnUiThread(ModalityState.any()) {
                items = it
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
        status.text = buildString {
            append(if (shown.size == items.size) count(items.size, "diagnostic") else "${shown.size} of ${count(items.size, "diagnostic")}")
            append(" · ").append(count(shown.distinctBy { it.diagnostic.check }.size, "check"))
            ErrorProneDiagnostics.getInstance(project).lastUpdate?.let { (task, time) ->
                append(" · updated by ${task.substringAfterLast('|')} at ${DateFormatUtil.formatTime(time)}")
            }
        }
        if (items.isEmpty()) {
            // Before any build and after a clean one alike.
            tree.emptyText.text = "No Error Prone diagnostics"
            tree.emptyText.appendLine("Run Error Prone", SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { runErrorProne(project) }
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
                diagnostic.suggestion?.let { suggestion ->
                    row { label("Did you mean") }
                    row {
                        cell(JBTextArea(suggestion).apply {
                            isEditable = false
                            lineWrap = true
                            font = EditorUtil.getEditorFont()
                        }).align(AlignX.FILL)
                    }
                }
                row {
                    if (canFix(item)) button("Apply Fix in File") { locked { applyFixInFile(item) } }
                    if (canFix(item)) button("Apply for This Check…") { locked { applyForCheck(diagnostic.check) } }
                    button("Suppress") { locked { suppress(item) } }
                }
                diagnostic.link?.let { row { browserLink("Documentation", it) } }
            }
            is CheckNode -> {
                heading(node.check, node.severity)
                row { comment("${count(node.items.size, "diagnostic")} in ${count(node.files, "file")}") }
                if (node.fixable) row { button("Apply for This Check…") { locked { applyForCheck(node.check) } } }
                node.link?.let { row { browserLink("Documentation", it) } }
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

    /**
     * A button's listener runs on the EDT without the lock that actions get from the action system, and
     * what the buttons do reads PSI and documents. Application.invokeLater runs it under the write-intent
     * lock (WriteIntentReadAction would too, but is experimental API).
     */
    private fun locked(run: () -> Unit) = ApplicationManager.getApplication().invokeLater(run)

    private fun applyFixInFile(item: TabItem) {
        ApplyErrorProneFix(item.diagnostic.check, item.located.task, item.file).invoke(project, null, null)
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
        val check = item.diagnostic.check
        WriteCommandAction.runWriteCommandAction(project, "Suppress '$check'", null, {
            SuppressErrorProneFix(check, item.located.marker).invoke(project, null, psi)
        }, psi)
    }

    override fun uiDataSnapshot(sink: DataSink) {
        val node = selectedNode()
        sink[SELECTED_NODE] = node
        sink[CommonDataKeys.VIRTUAL_FILE] = (node as? ItemNode)?.item?.file ?: (node as? FileNode)?.file
        sink[CommonDataKeys.NAVIGATABLE] = navigatable()
    }

    /** Where the selection's code is now, which its marker knows, not where it was at the last rebuild. */
    internal fun navigatable(): Navigatable? = when (val node = selectedNode()) {
        is ItemNode -> node.item.located.marker.takeIf { it.isValid }?.let { OpenFileDescriptor(project, node.item.file, it.startOffset) }
        is FileNode -> OpenFileDescriptor(project, node.file)
        else -> null
    }

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
                common.createExpandAllAction(expander, tree),
                common.createCollapseAllAction(expander, tree),
                autoscroll.createToggleAction(),
            ),
        )
    }

    private fun contextActions() = DefaultActionGroup(
        nodeAction("Apply Fix in File", { (it as? ItemNode)?.item?.takeIf(::canFix) }, ::applyFixInFile),
        nodeAction("Apply for This Check…", ::fixableCheckOf, ::applyForCheck),
        nodeAction("Suppress", { (it as? ItemNode)?.item }, ::suppress),
        Separator.getInstance(),
        nodeAction("Open Documentation", ::linkOf) { BrowserUtil.browse(it) },
        nodeAction("Copy Message", { (it as? ItemNode)?.item?.diagnostic?.text }) { CopyPasteManager.getInstance().setContents(StringSelection(it)) },
    )

    private fun fixableCheckOf(node: TabNode?): String? = when (node) {
        is ItemNode -> node.item.takeIf(::canFix)?.diagnostic?.check
        is CheckNode -> node.check.takeIf { node.fixable }
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
                if (node.fixable) append("  fixable", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
            is FileNode -> {
                icon = node.items.first().icon
                append(node.file.name, if (node.generated) SimpleTextAttributes.GRAYED_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
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
