package io.github.salatmaster.errorprone

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import org.assertj.core.api.Assertions.assertThat
import kotlin.system.measureTimeMillis

/**
 * A build of forty modules that reports twenty thousand diagnostics: what the store and the tab do with
 * them must stay in the tens of milliseconds, far under the bound here, which only catches a slip into
 * quadratic time. Measured at 49,000: committing 107 ms, the tab's items 64, its tree 54.
 */
class ScaleTest : ErrorProneLightTestCase() {

    fun `test keeps up with tens of thousands of diagnostics`() {
        val lines = 50
        val text = "class C {\n" + (1 until lines).joinToString("") { "  int f$it() { return $it; }\n" } + "}\n"
        val files = (0 until 400).map { myFixture.addFileToProject("p${it / 20}/C$it.java", text.replace("class C", "class C$it")).virtualFile }
        fun diagnostics(file: VirtualFile) = (2..lines).map {
            diagnostic(line = it, column = 7, check = "Check${it % 40}", fixable = it % 2 == 0, path = file.path)
        }

        val took = measureTimeMillis {
            files.chunked(10).forEachIndexed { i, chunk -> store.commit(":m$i:compileJava", CompileOutcome.FULL, chunk.associateWith(::diagnostics)) }
            val items = runReadActionBlocking { collectItems(project) }
            buildTree(items, TabView())
            buildTree(items, TabView(grouping = Grouping.FILE))
            runReadActionBlocking { store.hasFix { false } }
            store.state
            assertThat(items).hasSize(files.size * (lines - 1))
        }

        assertThat(took).isLessThan(5_000)
    }
}
