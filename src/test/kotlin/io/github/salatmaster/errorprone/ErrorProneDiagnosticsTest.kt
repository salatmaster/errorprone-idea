package io.github.salatmaster.errorprone

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.xmlb.XmlSerializer
import org.assertj.core.api.Assertions.assertThat
import java.io.File
import java.nio.file.Files
import kotlin.concurrent.thread

class ErrorProneDiagnosticsTest : ErrorProneLightTestCase() {

    private val source = "class Many {\n  public String toString() { return \"\"; }\n}\n"

    private fun ranges() = store.forFile(myFixture.file.virtualFile).map { it.range }

    fun `test anchors a diagnostic at the column javac reported`() {
        myFixture.configureByText("Many.java", source)

        // `  public String ` is 16 characters, so `toString` is javac column 17.
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        assertThat(ranges().single().startOffset).isEqualTo(source.indexOf("toString"))
    }

    fun `test counts columns across tabs the way javac does`() {
        val tabbed = "class Many {\n\tpublic String toString() { return \"\"; }\n}\n"
        myFixture.configureByText("Many.java", tabbed)

        // The tab reaches column 9, so `toString` is javac column 23 while being character 15.
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 23))

        assertThat(ranges().single().startOffset).isEqualTo(tabbed.indexOf("toString"))
    }

    fun `test a commit from the Gradle event thread takes the locks it needs itself`() {
        // A file on disk that no editor has open, as most files a build reports on are: its PSI is
        // created fresh, so nothing has cached its document yet.
        val onDisk = File(Files.createTempDirectory("errorprone").toFile(), "Many.java").apply { writeText(source) }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(onDisk)!!
        val diagnostic = ErrorProneDiagnostic(onDisk.path, 2, 17, 0, "MissingOverride", ErrorProneSeverity.WARNING, "m", null, null)

        // The Tooling API calls back on a plain thread of its own, holding no lock and carrying no IDE
        // context; every other test here commits on the EDT, where reading is always allowed and a
        // missing read action hides. A pooled thread would not do: it inherits the EDT's context.
        var failure: Throwable? = null
        thread {
            try {
                store.commit(":compileJava", CompileOutcome.FULL, mapOf(file to listOf(diagnostic)))
            } catch (e: Throwable) {
                failure = e
            }
        }.join(30_000)

        assertThat(failure).isNull()
        assertThat(store.forFile(file)).hasSize(1)
    }

    /** What the platform does across a restart: write the state out, read it into a fresh store. */
    private fun restart() {
        val saved = XmlSerializer.deserialize(XmlSerializer.serialize(store.state), ErrorProneDiagnostics.Saved::class.java)
        store.clear()
        store.loadState(saved)
        store.restore()
    }

    fun `test diagnostics survive a restart`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        restart()

        val restored = store.forFile(myFixture.file.virtualFile).single()
        assertThat(restored.range.startOffset).isEqualTo(source.indexOf("toString"))
        assertThat(restored.diagnostic).isEqualTo(diagnostic(line = 2, column = 17))
    }

    fun `test a file changed while the IDE was closed loses what was saved for it`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val saved = XmlSerializer.deserialize(XmlSerializer.serialize(store.state), ErrorProneDiagnostics.Saved::class.java)
        store.clear()

        // A pull or a checkout while the IDE was closed: the saved offsets no longer fit the text.
        val file = myFixture.file.virtualFile
        WriteAction.run<Throwable> { file.setBinaryContent("class Many {}\n".toByteArray(), -1, file.timeStamp + 1000) }
        store.loadState(saved)
        store.restore()

        assertThat(store.forFile(file)).isEmpty()
    }

    fun `test a file with unsaved edits is left out of what is saved`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        // Its offsets follow the editor, not the file on disk that the next session will read.
        WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, "// unsaved\n") }

        assertThat(store.state.files).isEmpty()
    }

    fun `test a range follows edits made after the build`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, "// moved\n")
        }

        assertThat(ranges().single().startOffset).isEqualTo("// moved\n".length + source.indexOf("toString"))
    }

    fun `test a full compile without diagnostics clears the task`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        commit(CompileOutcome.FULL)

        assertThat(ranges()).isEmpty()
        assertThat(store.files()).isEmpty()
        assertThat(store.count()).isZero()
    }

    fun `test an up-to-date task keeps its diagnostics`() {
        myFixture.configureByText("Many.java", source)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        commit(CompileOutcome.NONE)

        assertThat(ranges()).hasSize(1)
        assertThat(store.files()).containsExactly(myFixture.file.virtualFile)
    }

    fun `test drops a diagnostic whose line no longer exists`() {
        myFixture.configureByText("Many.java", source)

        commit(CompileOutcome.FULL, diagnostic(line = 40, column = 1), diagnostic(line = 2, column = 17))

        assertThat(ranges()).hasSize(1)
    }

    fun `test a task that never reported anything changes nothing`() {
        myFixture.configureByText("Many.java", source)
        var notifications = 0
        project.messageBus.connect(testRootDisposable)
            .subscribe(ErrorProneDiagnostics.TOPIC, ErrorProneDiagnosticsListener { notifications++ })

        store.commit(":processResources", CompileOutcome.FULL, emptyMap())
        assertThat(notifications).isZero()

        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        assertThat(notifications).isEqualTo(1)
    }
}
