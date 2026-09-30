package io.github.salatmaster.errorprone

import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.Icon
import javax.swing.tree.DefaultMutableTreeNode

/** How the Error Prone tab groups diagnostics. */
internal enum class Grouping { CHECK, FILE }

/** What the Error Prone tab shows, and how; all of it but [filter] is remembered per project. */
internal data class TabView(
    val grouping: Grouping = Grouping.CHECK,
    val severities: Set<ErrorProneSeverity> = ErrorProneSeverity.entries.toSet(),
    val generated: Boolean = true,
    val autoscroll: Boolean = false,
    val filter: String = "",
) {
    fun shows(item: TabItem): Boolean {
        val text = filter.trim()
        return item.diagnostic.severity in severities && (generated || !item.generated) &&
            (text.isEmpty() || listOf(item.diagnostic.check, item.diagnostic.message, item.file.name).any { it.contains(text, ignoreCase = true) })
    }
}

private const val PREFIX = "errorprone.tab."

internal fun loadView(properties: PropertiesComponent) = TabView(
    grouping = Grouping.entries.firstOrNull { it.name == properties.getValue(PREFIX + "grouping") } ?: Grouping.CHECK,
    severities = ErrorProneSeverity.entries.filterTo(HashSet()) { properties.getBoolean(PREFIX + it.name, true) },
    generated = properties.getBoolean(PREFIX + "generated", true),
    autoscroll = properties.getBoolean(PREFIX + "autoscroll", false),
)

internal fun saveView(properties: PropertiesComponent, view: TabView) {
    properties.setValue(PREFIX + "grouping", view.grouping.name)
    ErrorProneSeverity.entries.forEach { properties.setValue(PREFIX + it.name, it in view.severities, true) }
    properties.setValue(PREFIX + "generated", view.generated, true)
    properties.setValue(PREFIX + "autoscroll", view.autoscroll, false)
}

/**
 * One diagnostic as the tab shows it; [line] (0-based) is where its code was when the tab last read it.
 * The [document] and the file's [icon] are read here, in a read action: the tree paints on the EDT
 * without one, and a marker finds its document through FileDocumentManager, which needs it.
 */
internal class TabItem(
    val file: VirtualFile,
    val located: Located,
    val document: Document,
    val line: Int,
    val generated: Boolean,
    val module: String?,
    val icon: Icon?,
) {
    val diagnostic: ErrorProneDiagnostic get() = located.diagnostic

    /** Where the code is now; the document's lines need no lock. */
    val currentLine: Int get() = if (located.marker.isValid) document.getLineNumber(located.marker.startOffset) else line
}

/** Every diagnostic the store shows now. Call in a read action. */
internal fun collectItems(project: Project): List<TabItem> {
    val store = ErrorProneDiagnostics.getInstance(project)
    val index = ProjectFileIndex.getInstance(project)
    return store.files().flatMap { file ->
        val generated = isGeneratedCode(project, file)
        val module = index.getModuleForFile(file)?.name
        val icon = file.fileType.icon
        store.forFile(file).map {
            val document = it.marker.document
            TabItem(file, it, document, document.getLineNumber(it.range.startOffset), generated, module, icon)
        }
    }
}

/** What a node of the tab's tree stands for; [key] tells the same node apart across rebuilds. */
internal sealed interface TabNode {
    val key: String
}

internal class CheckNode(val check: String, val items: List<TabItem>) : TabNode {
    val severity: ErrorProneSeverity = items.minOf { it.diagnostic.severity }
    val files: Int = items.distinctBy { it.file }.size
    /** Error Prone has a fix for it somewhere but in generated code, where the next generation would undo it. */
    val fixable: Boolean = items.any { it.diagnostic.fixable && !it.generated }
    val link: String? = items.firstNotNullOfOrNull { it.diagnostic.link }
    override val key: String get() = "check:$check"
    override fun toString() = check
}

internal class FileNode(val file: VirtualFile, val items: List<TabItem>) : TabNode {
    val generated: Boolean get() = items.first().generated
    val module: String? get() = items.first().module
    override val key: String get() = "file:${file.path}"
    override fun toString(): String = file.name
}

/** [showCheck] when the tree is grouped by file, where no parent node names the check. */
internal class ItemNode(val item: TabItem, val showCheck: Boolean) : TabNode {
    override val key: String get() = with(item.diagnostic) { "item:$path:$line:$column:$check:$message" }
    override fun toString(): String = if (showCheck) item.diagnostic.text else item.diagnostic.message
}

/** The tab's tree for [items] as [view] shows them; its root stands for nothing. */
internal fun buildTree(items: List<TabItem>, view: TabView): DefaultMutableTreeNode {
    val shown = items.filter(view::shows)
    val root = DefaultMutableTreeNode()
    when (view.grouping) {
        Grouping.CHECK -> shown.groupBy { it.diagnostic.check }
            .map { (check, list) -> CheckNode(check, list) }
            .sortedWith(compareBy<CheckNode> { it.severity }.thenByDescending { it.items.size }.thenBy { it.check })
            .forEach { check -> root.add(DefaultMutableTreeNode(check).apply { fileNodes(check.items, showCheck = false).forEach(::add) }) }
        Grouping.FILE -> fileNodes(shown, showCheck = true).forEach(root::add)
    }
    return root
}

/** Code people write before generated code, then by name. */
private fun fileNodes(items: List<TabItem>, showCheck: Boolean): List<DefaultMutableTreeNode> =
    items.groupBy { it.file }
        .map { (file, list) -> FileNode(file, list) }
        .sortedWith(compareBy<FileNode>({ it.generated }, { it.file.name }, { it.file.path }))
        .map { file ->
            DefaultMutableTreeNode(file).apply {
                file.items.sortedWith(compareBy({ it.line }, { it.diagnostic.check }))
                    .forEach { add(DefaultMutableTreeNode(ItemNode(it, showCheck), false)) }
            }
        }

internal val ErrorProneSeverity.icon: Icon
    get() = when (this) {
        ErrorProneSeverity.ERROR -> HighlightDisplayLevel.ERROR.icon
        ErrorProneSeverity.WARNING -> HighlightDisplayLevel.WARNING.icon
        ErrorProneSeverity.NOTE -> HighlightDisplayLevel.WEAK_WARNING.icon
    }
