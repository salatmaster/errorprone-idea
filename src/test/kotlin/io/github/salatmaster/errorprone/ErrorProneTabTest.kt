package io.github.salatmaster.errorprone

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.KeyboardShortcut
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.text.DateFormatUtil
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import org.assertj.core.api.Assertions.assertThat
import org.gradle.util.GradleVersion
import java.awt.datatransfer.DataFlavor
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
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
        assertThat(check.fixable).hasSize(3)
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

    fun `test shows only diagnostics on changed lines when asked`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17), diagnostic(line = 3, column = 18, check = "EqualsGetClass"))
        // The light project has no version control: mark one line changed by hand.
        val items = items().map { if (it.line == 1) it.copy(changed = true) else it }

        assertThat(buildTree(items, TabView(changedOnly = true)).labels()).containsExactly("MissingOverride")
        assertThat(buildTree(items, TabView()).labels()).containsExactlyInAnyOrder("MissingOverride", "EqualsGetClass")
    }

    fun `test a commit with diagnostics on its changed lines is held up, one without is not`() {
        assertThat(commitProblem(0)).isNull()
        val problem = commitProblem(2)!!
        assertThat(problem.text).isEqualTo("2 Error Prone diagnostics on lines this commit changes")
        assertThat(problem.showDetailsAction).isEqualTo("Show in Error Prone Tab")
    }

    private fun tab() = ErrorProneTab(project).also { Disposer.register(testRootDisposable, it) }

    private fun waitFor(condition: () -> Boolean) =
        PlatformTestUtil.waitWithEventsDispatching("the tab did not catch up with the store", condition, 10)

    private fun buttons(tab: ErrorProneTab) = UIUtil.findComponentsOfType(tab.details, JButton::class.java)

    /** What the details pane says, labels and comments alike. */
    private fun detailsText(tab: ErrorProneTab) =
        UIUtil.uiTraverser(tab.details).mapNotNull { (it as? JLabel)?.text ?: (it as? JEditorPane)?.text }.joinToString("\n")

    /** Opens the first check and its first file, and selects that file's first diagnostic. */
    private fun selectFirstDiagnostic(tab: ErrorProneTab) {
        tab.tree.expandRow(0)
        tab.tree.expandRow(1)
        tab.tree.setSelectionRow(2)
    }

    fun `test lists what the store has, follows it, and says why there is nothing`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        // The light project links no Gradle build, so running Error Prone is not offered.
        waitFor { tab.tree.emptyText.text == "No Gradle build is linked to this project" }

        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }
        assertThat(tab.status.text).contains("1 diagnostic", "1 check", "updated by :compileJava")

        commit(CompileOutcome.FULL)
        waitFor { tab.tree.rowCount == 0 }
        assertThat(tab.tree.emptyText.text).isEqualTo("No Gradle build is linked to this project")
    }

    fun `test shows Error Prone running, then what the run changed`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17), diagnostic(line = 3, column = 18))
        waitFor { tab.tree.rowCount == 1 }
        val builds = ErrorProneBuilds.getInstance(project)

        assertThat(builds.startRun(1)).isTrue()
        // A second click while it runs starts nothing.
        assertThat(builds.startRun(1)).isFalse()
        waitFor { tab.status.text.startsWith("Running Error Prone") }
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        assertThat(builds.finishRun(succeeded = true)).isEqualTo(RunResult(builds.lastRun!!.at, before = 2, after = 1, succeeded = true))

        waitFor { "Run Error Prone at " in tab.status.text }
        assertThat(tab.status.text).contains("1 fewer").doesNotContain("Running")
    }

    fun `test sums up a run on the thread Gradle calls back on`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val builds = ErrorProneBuilds.getInstance(project)
        assertThat(builds.startRun(1)).isTrue()

        // The task callback runs on a background thread holding no lock.
        var failure: Throwable? = null
        thread {
            try {
                builds.finishRun(succeeded = true)
            } catch (e: Throwable) {
                failure = e
            }
        }.join(30_000)

        assertThat(failure).isNull()
        assertThat(builds.lastRun!!.after).isEqualTo(1)
    }

    fun `test says what recompiling after an edit waits for`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }

        CompileOnEdit.getInstance(project).waitingFor("Many.java recompiles once its module has no errors")
        try {
            waitFor { tab.status.text.startsWith("Many.java recompiles once its module has no errors · ") }
        } finally {
            CompileOnEdit.getInstance(project).waitingFor(null)
        }
        waitFor { tab.status.text.startsWith("1 diagnostic") }
    }

    fun `test names the first reason there is nothing to show`() {
        val ready = Setup(linked = true, errorProne = true, gradle = GradleVersion.version("9.7"), delegated = true)
        fun reason(setup: Setup, vararg outcomes: CompileOutcome) = emptyState(setup, outcomes.map { TaskRecord(it, 0) })

        assertThat(reason(ready.copy(linked = false, errorProne = false)).text).isEqualTo("No Gradle build is linked to this project")
        assertThat(reason(ready.copy(linked = false)).text).contains("only Gradle builds")
        assertThat(reason(ready.copy(gradle = GradleVersion.version("8.12"))).text).contains("Gradle 8.12", "8.14")
        assertThat(reason(ready.copy(errorProne = false))).isEqualTo(EmptyState("The last Gradle sync found no Error Prone in this build", EmptyAction.GETTING_STARTED))
        assertThat(reason(ready.copy(delegated = false))).isEqualTo(EmptyState("Build Project uses IntelliJ IDEA's own builder here, which reports nothing to this plugin", EmptyAction.RUN))
        assertThat(reason(ready)).isEqualTo(EmptyState("No compile has run since the IDE started", EmptyAction.RUN))
        assertThat(reason(ready, CompileOutcome.NONE).text).contains("up to date")
        assertThat(reason(ready, CompileOutcome.NONE, CompileOutcome.PARTIAL).text).contains("incremental")
        assertThat(reason(ready, CompileOutcome.FULL, CompileOutcome.FAILED).text).contains("failed")
        // A full compile of one task says nothing of another's incremental one.
        assertThat(reason(ready, CompileOutcome.FULL, CompileOutcome.PARTIAL).text).contains("incremental")
        assertThat(reason(ready, CompileOutcome.FULL, CompileOutcome.NONE)).isEqualTo(EmptyState("Error Prone found nothing in the last full compile, at ${DateFormatUtil.formatTime(0)}"))
    }

    fun `test says when a compile failed or javac stopped at its limit`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        waitFor { tab.tree.rowCount == 1 }
        selectFirstDiagnostic(tab)
        assertThat(detailsText(tab)).contains("Reported by :compileJava at ")

        commit(CompileOutcome.FAILED)
        store.capped(":compileJava")

        waitFor { "1 compile failed" in tab.status.text && "javac's limit" in tab.status.text }
        assertThat(tab.status.toolTipText).contains(":compileJava failed at ")
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

    fun `test shows each fix Error Prone offers apart`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true, suggestion = "'a(Locale.ROOT)' or 'a(Locale.getDefault())'"))
        waitFor { tab.tree.rowCount == 1 }
        selectFirstDiagnostic(tab)

        assertThat(UIUtil.findComponentsOfType(tab.details, JBTextArea::class.java).map { it.text })
            .containsExactly("a(Locale.ROOT)", "a(Locale.getDefault())")
    }

    fun `test a check offers to fix what it shows without asking where, and to suppress it all`() {
        myFixture.configureByText("Many.java", source)
        val other = myFixture.addFileToProject("Other.java", source).virtualFile
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true), diagnostic(line = 3, column = 18))
        store.commit(":compileTestJava", CompileOutcome.FULL, mapOf(other to listOf(diagnostic(line = 2, column = 17, fixable = true, path = other.path))))
        waitFor { tab.tree.rowCount == 1 }

        tab.tree.setSelectionRow(0)

        // Error Prone fixes a check in a whole file, so the files are what the button can promise.
        assertThat(buttons(tab).map { it.text }).containsExactly("Apply Fixes in 2 Files", "Suppress All 3…", "Documentation")
        val rendered = TabRenderer().getTreeCellRendererComponent(Tree(), tab.tree.getPathForRow(0).lastPathComponent, false, false, false, 0, false)
        assertThat((rendered as ColoredTreeCellRenderer).getCharSequence(false).toString()).contains("2 of 3 fixable")
    }

    fun `test suppresses everything a node holds, each in its own declaration, after asking`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17), diagnostic(line = 3, column = 18))
        waitFor { tab.tree.rowCount == 1 }
        tab.tree.setSelectionRow(0)

        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            buttons(tab).single { it.text == "Suppress All 2…" }.doClick()
            waitFor { store.count() == 0 }
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }

        val text = myFixture.editor.document.text
        assertThat(text.split("SuppressWarnings(\"MissingOverride\")")).hasSize(3)
    }

    fun `test changes the selected check's severity in the build that reported it`() {
        myFixture.configureByText("Many.java", source)
        val tab = tab()
        val root = tempDir()
        store.commit("${root.path}|:app:compileJava", CompileOutcome.FULL, mapOf(myFixture.file.virtualFile to listOf(diagnostic(line = 2, column = 17))))
        waitFor { tab.tree.rowCount == 1 }

        tab.tree.setSelectionRow(0)
        assertThat(tab.gradleCheck()).isEqualTo(root to "MissingOverride")
        TreeUtil.expandAll(tab.tree)
        tab.tree.setSelectionRow(2)
        assertThat(tab.gradleCheck()).isEqualTo(root to "MissingOverride")
    }

    fun `test leaves alone what no declaration holds, and says so`() {
        val text = "import java.util.List;\n$source"
        myFixture.configureByText("Many.java", text)
        val tab = tab()
        // BadImport reports on the import, which no @SuppressWarnings can reach.
        commit(CompileOutcome.FULL, diagnostic(line = 1, column = 8, check = "BadImport"), diagnostic(line = 3, column = 17, check = "BadImport"))
        waitFor { tab.tree.rowCount == 1 }
        tab.tree.setSelectionRow(0)
        val shown = mutableListOf<Notification>()
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown += notification
            }
        })

        TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            buttons(tab).single { it.text.startsWith("Suppress All") }.doClick()
            waitFor { "SuppressWarnings(\"BadImport\")" in myFixture.editor.document.text }
        } finally {
            TestDialogManager.setTestDialog(TestDialog.DEFAULT)
        }

        // The one on the import still shows: nothing silences it.
        assertThat(store.count()).isEqualTo(1)
        assertThat(shown.map { it.content }).anyMatch { it.startsWith("1 diagnostic was left as it is") }
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

    fun `test steps from diagnostic to diagnostic, across files, as Next Occurrence does`() {
        myFixture.configureByText("Many.java", source)
        val other = myFixture.addFileToProject("Other.java", source).virtualFile
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17), diagnostic(line = 3, column = 18))
        store.commit(":compileTestJava", CompileOutcome.FULL, mapOf(other to listOf(diagnostic(line = 2, column = 17, path = other.path))))
        waitFor { tab.tree.rowCount == 1 }

        val visited = generateSequence { if (tab.hasNextOccurence()) tab.goNextOccurence() else null }
            .map { (it.navigateable as OpenFileDescriptor).let { d -> d.file.name to d.offset } }
            .toList()

        assertThat(visited).containsExactly(
            "Many.java" to source.indexOf("toString"),
            "Many.java" to source.indexOf("equals"),
            "Other.java" to source.indexOf("toString"),
        )
    }

    fun `test Alt+Enter on a row opens what can be done with it`() {
        val tab = tab()

        assertThat(ActionUtil.getActions(tab.tree).map { it.shortcutSet.shortcuts.toList() })
            .anyMatch { shortcuts -> shortcuts.any { it == KeyboardShortcut.fromString("alt ENTER") } }
    }

    fun `test copies every diagnostic under the selection, with where it is`() {
        myFixture.configureByText("Many.java", source)
        val other = myFixture.addFileToProject("com/example/Other.java", source).virtualFile
        val tab = tab()
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        store.commit(":compileTestJava", CompileOutcome.FULL, mapOf(other to listOf(diagnostic(line = 2, column = 17, path = other.path))))
        waitFor { tab.tree.rowCount == 1 }
        tab.tree.setSelectionRow(0)

        assertThat(tab.copyText()!!.lines()).containsExactly(
            "Many.java:2: [MissingOverride] toString overrides method in Object; expected @Override",
            "com/example/Other.java:2: [MissingOverride] toString overrides method in Object; expected @Override",
        )
    }

    fun `test tells files of the same name apart by package, and filters by package and module`() {
        myFixture.configureByText("Many.java", source)
        val deep = myFixture.addFileToProject("com/example/Many.java", source).virtualFile
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        store.commit(":compileTestJava", CompileOutcome.FULL, mapOf(deep to listOf(diagnostic(line = 2, column = 17, check = "Deep", path = deep.path))))
        val items = items()

        assertThat(buildTree(items, TabView(filter = "com.example")).labels()).containsExactly("Deep")
        assertThat(buildTree(items, TabView(filter = module.name)).labels()).containsExactlyInAnyOrder("Deep", "MissingOverride")
        val file = buildTree(items, TabView(filter = "com.example")).child(0).child(0)
        val rendered = TabRenderer().getTreeCellRendererComponent(Tree(), file, false, false, false, 0, false) as ColoredTreeCellRenderer
        assertThat(rendered.getCharSequence(false).toString()).contains("Many.java", "com.example")
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

        assertThat((buildTree(items(), TabView()).child(0).userObject as CheckNode).fixable).isEmpty()
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
