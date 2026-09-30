package io.github.salatmaster.errorprone

import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * Base for tests against the light project fixture.
 *
 * BasePlatformTestCase hands every test in every class the same light project, so the store would
 * carry one test's diagnostics into the next; it is emptied on both sides of each test.
 *
 * JUnit 3 recognises a test only if the method returns void: keep test bodies as blocks, never
 * `= expression`, or the method silently stops being a test.
 */
abstract class ErrorProneLightTestCase : BasePlatformTestCase() {

    protected val store: ErrorProneDiagnostics get() = ErrorProneDiagnostics.getInstance(project)

    override fun setUp() {
        super.setUp()
        store.clear()
    }

    override fun tearDown() {
        try {
            store.clear()
        } finally {
            super.tearDown()
        }
    }

    protected fun diagnostic(
        line: Int,
        column: Int,
        check: String = "MissingOverride",
        severity: ErrorProneSeverity = ErrorProneSeverity.WARNING,
        suggestion: String? = null,
        fixable: Boolean = false,
        path: String = myFixture.file.virtualFile.path,
    ) = ErrorProneDiagnostic(
        path = path,
        line = line,
        column = column,
        length = 0,
        check = check,
        severity = severity,
        message = "toString overrides method in Object; expected @Override",
        link = "https://errorprone.info/bugpattern/$check",
        suggestion = suggestion,
        fixable = fixable,
    )

    /**
     * A file where a code generator writes under Gradle: a source root inside the build directory, which
     * the IDE excludes. Undone with the test, since every test shares the light project.
     */
    protected fun generatedFile(name: String, text: String): VirtualFile {
        val root = myFixture.tempDirFixture.findOrCreateDir("build/generated/java")
        val build = root.parent.parent
        PsiTestUtil.addExcludedRoot(module, build)
        PsiTestUtil.addSourceRoot(module, root)
        Disposer.register(testRootDisposable) {
            PsiTestUtil.removeSourceRoot(module, root)
            PsiTestUtil.removeExcludedRoot(module, build)
        }
        return myFixture.addFileToProject("build/generated/java/$name", text).virtualFile
    }

    /** Commits [diagnostics] for the file open in the fixture, as `:compileJava` finishing would. */
    protected fun commit(outcome: CompileOutcome, vararg diagnostics: ErrorProneDiagnostic) {
        val byFile = if (diagnostics.isEmpty()) emptyMap() else mapOf(myFixture.file.virtualFile to diagnostics.toList())
        store.commit(":compileJava", outcome, byFile)
    }
}
