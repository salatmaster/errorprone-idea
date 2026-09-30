package io.github.salatmaster.errorprone

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.assertj.core.api.Assertions.assertThat
import javax.swing.JButton
import javax.swing.Scrollable
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeSelectionModel
import kotlin.concurrent.thread

class ErrorProneTabTest : ErrorProneLightTestCase() {

    private val source = "class Many {\n  public String toString() { return \"\"; }\n  public boolean equals(Object o) { return false; }\n}\n"

    private fun items() = runReadActionBlocking { collectItems(project) }

    private fun DefaultMutableTreeNode.labels() = children().toList().map { it.toString() }

    private fun DefaultMutableTreeNode.child(index: Int) = getChildAt(index) as DefaultMutableTreeNode

    fun `test groups by check, errors first, then the most reported`() {
        myFixture.configureByText("Many.java", source)
        val other = myFixture.addFileToProject("Other.java", source).virtualFile
        commit(
            CompileOutcome.FULL,
            diagnostic(line = 2, column = 17, fixable = true),
            diagnostic(line = 3, column = 18, fixable = true),
            diagnostic(line = 1, column = 7, check = "FallThrough"),
            diagnostic(line = 1, column = 1, check = "DeadException", severity = ErrorProneSeverity.ERROR),
        )
        store.commit(
            ":compileTestJava",
            CompileOutcome.FULL,
            mapOf(other to listOf(diagnostic(line = 2, column = 17, fixable = true, path = other.path))),
        )

        val root = buildTree(items(), TabView())

        assertThat(root.labels()).containsExactly("DeadException", "MissingOverride", "FallThrough")
        val check = root.child(1).userObject as CheckNode
        assertThat(check.items).hasSize(3)
        assertThat(check.files).isEqualTo(2)
        assertThat(check.fixable).isTrue()
        assertThat(root.child(1).labels()).containsExactly("Many.java", "Other.java")
        assertThat(root.child(1).child(0).children().toList().map { ((it as DefaultMutableTreeNode).userObject as ItemNode).item.line })
            .containsExactly(1, 2)
    }

    fun `test groups by file, generated code last, lines in order, each with its check`() {
        myFixture.configureByText("Many.java", source)
        val generated = generatedFile("Aaa.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 3, column = 18), diagnostic(line = 2, column = 17, check = "FallThrough"))
        store.commit(":compileTestJava", CompileOutcome.FULL, mapOf(generated to listOf(diagnostic(line = 2, column = 17, path = generated.path))))

        val root = buildTree(items(), TabView(grouping = Grouping.FILE))

        assertThat(root.labels()).containsExactly("Many.java", "Aaa.java")
        assertThat(root.child(0).labels()).containsExactly(
            "[FallThrough] toString overrides method in Object; expected @Override",
            "[MissingOverride] toString overrides method in Object; expected @Override",
        )
    }

    fun `test shows only what the filter, the severities and the generated toggle let through`() {
        myFixture.configureByText("Many.java", source)
        val generated = generatedFile("Gen.java", source)
        commit(
            CompileOutcome.FULL,
            diagnostic(line = 2, column = 17),
            diagnostic(line = 1, column = 1, check = "DeadException", severity = ErrorProneSeverity.ERROR),
        )
        store.commit(
            ":compileTestJava",
            CompileOutcome.FULL,
            mapOf(generated to listOf(diagnostic(line = 2, column = 17, check = "UnusedVariable", path = generated.path))),
        )
        val items = items()

        assertThat(buildTree(items, TabView(filter = "dead")).labels()).containsExactly("DeadException")
        assertThat(buildTree(items, TabView(filter = "gen.java")).labels()).containsExactly("UnusedVariable")
        assertThat(buildTree(items, TabView(severities = setOf(ErrorProneSeverity.ERROR))).labels()).containsExactly("DeadException")
        assertThat(buildTree(items, TabView(generated = false)).labels()).containsExactly("DeadException", "MissingOverride")
    }

    private fun tab() = ErrorProneTab(project).also { Disposer.register(testRootDisposable, it) }

    private fun waitFor(condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("the tab did not catch up with the store", condition, 10)

    private fun buttons(tab: ErrorProneTab) = UIUtil.findComponentsOfType(tab.details, JButton::class.java)

    /** Opens the first check and its first file, and selects that file's first diagnostic. */
    private fun selectFirstDiagnostic(tab: ErrorProneTab) {
        tab.tree.expandRow(0)
        tab.tree.expandRow(1)
        tab.tree.setSelectionRow(2)
    }

    fun `test lists what the store has, follows it, and says when there is nothing`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        waitFor { tab.tree.emptyText.text == "No Error Prone diagnostics" }

        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }
        assertThat(tab.status.text).contains("1 diagnostic", "1 check", "updated by :compileJava")

        commit(CompileOutcome.FULL)
        waitFor { tab.tree.rowCount == 0 }
        assertThat(tab.tree.emptyText.text).isEqualTo("No Error Prone diagnostics")
    }

    fun `test the selected diagnostic leads to its code as it is now, and offers what can be done`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true))
        waitFor { tab.tree.rowCount == 1 }
        selectFirstDiagnostic(tab)

        // The documentation link is a button too.
        assertThat(buttons(tab).map { it.text }).containsExactly("Apply Fix in File", "Apply for This Check…", "Suppress", "Documentation")

        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// one\n") }
        tab.navigatable()!!.navigate(true)
        assertThat(FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.offset)
            .isEqualTo("// one\n".length + source.indexOf("toString"))
    }

    fun `test suppresses from the tab as from the editor`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }
        selectFirstDiagnostic(tab)

        buttons(tab).single { it.text == "Suppress" }.doClick()

        // Later, under the write-intent lock a button's listener does not have.
        waitFor { "SuppressWarnings(\"MissingOverride\")" in myFixture.editor.document.text }
    }

    fun `test moves on to the next diagnostic once the selected one is dealt with`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17), diagnostic(line = 3, column = 18))
        waitFor { tab.tree.rowCount == 1 }
        selectFirstDiagnostic(tab)

        buttons(tab).single { it.text == "Suppress" }.doClick()

        // The check, its file, and the diagnostic left.
        waitFor { tab.tree.rowCount == 3 }
        val selected = TreeUtil.getUserObject(tab.tree.selectionPath?.lastPathComponent) as? ItemNode
        assertThat(selected?.item?.diagnostic?.line).isEqualTo(3)
    }

    fun `test marks each diagnostic's severity when grouped by file`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 1, column = 1, check = "DeadException", severity = ErrorProneSeverity.ERROR))
        val item = buildTree(items(), TabView(grouping = Grouping.FILE)).child(0).child(0)

        val rendered = TabRenderer().getTreeCellRendererComponent(Tree(), item, false, false, true, 0, false) as ColoredTreeCellRenderer

        assertThat(rendered.icon).isEqualTo(ErrorProneSeverity.ERROR.icon)
    }

    fun `test drops a diagnostic as soon as an edit changes its line`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }

        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.getLineStartOffset(1), "//") }

        waitFor { tab.tree.rowCount == 0 }
    }

    fun `test says when the filter hides everything`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }

        UIUtil.findComponentOfType(tab, SearchTextField::class.java)!!.text = "nothing like this"

        assertThat(tab.tree.rowCount).isZero()
        assertThat(tab.tree.emptyText.text).isEqualTo("Nothing matches")
    }

    fun `test shows each diagnostic's line where its code is now`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val item = buildTree(items(), TabView()).child(0).child(0).child(0)

        // Away from the diagnostic: nothing rebuilds the tree.
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// one\n") }
        val rendered = TabRenderer().getTreeCellRendererComponent(Tree(), item, false, false, true, 0, false) as ColoredTreeCellRenderer

        assertThat(rendered.getCharSequence(false).toString()).startsWith("3 ")
    }

    fun `test draws every node without the read lock, as the EDT paints them`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val nodes = TreeUtil.treeNodeTraverser(buildTree(items(), TabView())).toList().drop(1)

        // The test body holds the write-intent lock; the IDE's EDT paints without any.
        var failure: Throwable? = null
        thread {
            try {
                for (node in nodes) TabRenderer().getTreeCellRendererComponent(Tree(), node, false, true, false, 0, false)
            } catch (e: Throwable) {
                failure = e
            }
        }.join(30_000)

        assertThat(failure).isNull()
    }

    fun `test counts no fix in generated code, which the next generation would undo`() {
        myFixture.configureByText("Many.java", source)
        val generated = generatedFile("Gen.java", source)
        store.commit(
            ":compileTestJava",
            CompileOutcome.FULL,
            mapOf(generated to listOf(diagnostic(line = 2, column = 17, check = "UnusedVariable", fixable = true, path = generated.path))),
        )

        assertThat((buildTree(items(), TabView()).child(0).userObject as CheckNode).fixable).isFalse()
    }

    fun `test selects one diagnostic at a time and wraps its details to the pane`() {
        val tab = tab()

        // Every action acts on one diagnostic.
        assertThat(tab.tree.selectionModel.selectionMode).isEqualTo(TreeSelectionModel.SINGLE_TREE_SELECTION)
        // A long "Did you mean" wraps rather than scrolling sideways.
        assertThat((tab.details as Scrollable).scrollableTracksViewportWidth).isTrue()
    }

    fun `test follows a build reported from the Gradle event thread`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        val file = myFixture.file.virtualFile
        val diagnostic = diagnostic(line = 2, column = 17)

        // A plain thread with no lock and no IDE context, as the Tooling API calls back on.
        thread { store.commit(":compileJava", CompileOutcome.FULL, mapOf(file to listOf(diagnostic))) }.join(30_000)

        waitFor { tab.tree.rowCount == 1 }
    }
}
