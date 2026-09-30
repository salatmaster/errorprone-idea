package io.github.salatmaster.errorprone

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.VirtualFile
import org.assertj.core.api.Assertions.assertThat

class CompileOnEditTest : ErrorProneLightTestCase() {

    private val source = "class Many {\n  public String toString() {\n    return \"\";\n  }\n  void other() {\n  }\n}\n"

    override fun tearDown() {
        try {
            ErrorProneSettings.getInstance().compileOnEdit = true
        } finally {
            super.tearDown()
        }
    }

    private val pending: Set<VirtualFile> get() = CompileOnEdit.getInstance(project).pending

    /** A MissingOverride on `toString`, then [text] typed at the end of [line]. Each test its own file: a second later the last one's is dropped. */
    private fun editAfterBuild(name: String, line: Int, text: String): VirtualFile {
        val file = myFixture.configureByText(name, source).virtualFile
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.getLineEndOffset(line - 1), text) }
        return file
    }

    fun `test on by default`() {
        assertThat(ErrorProneSettings().compileOnEdit).isTrue()
    }

    fun `test an edit of a line Error Prone flagged schedules its file`() {
        val file = editAfterBuild("Flagged.java", 2, " ")

        assertThat(pending).contains(file)
    }

    fun `test an edit elsewhere in the method holding a diagnostic schedules its file`() {
        // Where a fix often goes: an @Override above, a missing case below.
        val file = editAfterBuild("Body.java", 3, " ")

        assertThat(pending).contains(file)
    }

    fun `test an edit away from every diagnostic schedules nothing`() {
        val file = editAfterBuild("Other.java", 5, " ")

        assertThat(pending).doesNotContain(file)
    }

    fun `test nothing is scheduled while it is off`() {
        ErrorProneSettings.getInstance().compileOnEdit = false

        val file = editAfterBuild("Off.java", 2, " ")

        assertThat(pending).doesNotContain(file)
    }

    fun `test holds back a file javac would stop at`() {
        // Half-way through typing: javac fails on it, and Error Prone says nothing once it does.
        val broken = myFixture.configureByText("Broken.java", "class Broken {\n  int f() { return }\n}\n").virtualFile
        val clean = myFixture.addFileToProject("Clean.java", "class Clean {}\n").virtualFile

        val compilable = runReadActionBlocking { CompileOnEdit.getInstance(project).compilable(listOf(broken, clean)) }

        assertThat(compilable).containsExactly(clean)
    }

    fun `test names the compile task of a source set as Gradle does`() {
        assertThat(compileTaskName("main")).isEqualTo("compileJava")
        assertThat(compileTaskName("test")).isEqualTo("compileTestJava")
        assertThat(compileTaskName("integrationTest")).isEqualTo("compileIntegrationTestJava")
    }
}
