package io.github.salatmaster.errorprone

import com.intellij.analysis.AnalysisScope
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.ui.EDT
import org.assertj.core.api.Assertions.assertThat
import java.util.Collections

class ApplyAllErrorProneFixesTest : ErrorProneLightTestCase() {

    private val source = "class Many {\n  public String toString() { return \"\"; }\n}\n"

    fun `test runs, per build, the tasks that reported fixable diagnostics in scope, generated code aside`() {
        val shop = myFixture.addFileToProject("Shop.java", source).virtualFile
        val other = myFixture.addFileToProject("Other.java", source).virtualFile
        val generated = generatedFile("Gen.java", source)
        fun at(check: String, file: String, fixable: Boolean = true) =
            diagnostic(line = 2, column = 17, check = check, fixable = fixable, path = file)
        store.commit(
            "/shop|:app:compileJava",
            CompileOutcome.FULL,
            mapOf(
                shop to listOf(at("MissingOverride", shop.path), at("FallThrough", shop.path, fixable = false)),
                generated to listOf(at("UnusedVariable", generated.path)),
            ),
        )
        store.commit("/other|:compileJava", CompileOutcome.FULL, mapOf(other to listOf(at("StringCaseLocaleUsage", other.path))))

        val runs = runReadActionBlocking { fixRuns(project) { it != other } }

        assertThat(runs).isEqualTo(mapOf("/shop" to FixRun(setOf(":app:compileJava"), setOf("MissingOverride"))))
    }

    fun `test reads the scope off the EDT, where a module's file set would freeze it`() {
        val shop = myFixture.addFileToProject("Shop.java", source).virtualFile
        store.commit("/shop|:compileJava", CompileOutcome.FULL, mapOf(shop to listOf(diagnostic(line = 2, column = 17, fixable = true, path = shop.path))))
        val onEdt = Collections.synchronizedList(mutableListOf<Boolean>())
        val scope = object : AnalysisScope(project) {
            override fun contains(file: VirtualFile): Boolean {
                onEdt += EDT.isCurrentThreadEdt()
                return false
            }
        }

        ApplyAllErrorProneFixesAction().analyze(project, scope)

        PlatformTestUtil.waitWithEventsDispatching("the scope was never asked", { onEdt.isNotEmpty() }, 10)
        assertThat(onEdt).containsOnly(false)
    }

    fun `test limits the fixes to the checks asked for`() {
        val shop = myFixture.addFileToProject("Shop.java", source).virtualFile
        store.commit(
            "/shop|:compileJava",
            CompileOutcome.FULL,
            mapOf(
                shop to listOf(
                    diagnostic(line = 2, column = 17, fixable = true, path = shop.path),
                    diagnostic(line = 2, column = 17, check = "StringCaseLocaleUsage", fixable = true, path = shop.path),
                ),
            ),
        )

        val runs = runReadActionBlocking { fixRuns(project, setOf("StringCaseLocaleUsage")) { true } }

        assertThat(runs).isEqualTo(mapOf("/shop" to FixRun(setOf(":compileJava"), setOf("StringCaseLocaleUsage"))))
    }
}
