package io.github.salatmaster.errorprone

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.toolWindow.ProblemsViewPanel
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.util.Disposer
import com.intellij.pom.Navigatable
import org.assertj.core.api.Assertions.assertThat

class ErrorProneProblemsTabTest : ErrorProneLightTestCase() {

    fun `test lists stored diagnostics with their position`() {
        myFixture.configureByText("Many.java", "class Many {\n  public String toString() { return \"\"; }\n}\n")
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        val panel = ErrorProneProblemsTabProvider(project).create() as ProblemsViewPanel
        Disposer.register(testRootDisposable, panel)
        val root = panel.treeModel.root!!

        assertThat(root.getProblemCount()).isEqualTo(1)
        assertThat(root.getProblemFiles()).containsExactly(myFixture.file.virtualFile)
        val problem = root.getFileProblems(myFixture.file.virtualFile).single() as FileProblem
        // FileProblem positions are 0-based: the second line, the sixteenth character.
        assertThat(problem.line).isEqualTo(1)
        assertThat(problem.column).isEqualTo(16)
        assertThat(problem.text).isEqualTo("[MissingOverride] toString overrides method in Object; expected @Override")
    }

    fun `test navigates to where the code is now, not where it was built`() {
        val source = "class Many {\n  public String toString() { return \"\"; }\n}\n"
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val panel = ErrorProneProblemsTabProvider(project).create() as ProblemsViewPanel
        Disposer.register(testRootDisposable, panel)
        val problem = panel.treeModel.root!!.getFileProblems(myFixture.file.virtualFile).single()

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "// one\n// two\n// three\n")
        }
        (problem as Navigatable).navigate(true)

        val expected = "// one\n// two\n// three\n".length + source.indexOf("toString")
        assertThat(FileEditorManager.getInstance(project).selectedTextEditor!!.caretModel.offset).isEqualTo(expected)
        assertThat((problem as FileProblem).line).isEqualTo(4)
    }

    fun `test an empty store shows an empty tab`() {
        myFixture.configureByText("Many.java", "class Many {}\n")

        val panel = ErrorProneProblemsTabProvider(project).create() as ProblemsViewPanel
        Disposer.register(testRootDisposable, panel)

        assertThat(panel.treeModel.root!!.getProblemCount()).isZero()
        assertThat(panel.tree.emptyText.text).isEqualTo("No Error Prone diagnostics yet")
    }
}
