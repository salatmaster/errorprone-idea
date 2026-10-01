package io.github.salatmaster.errorprone

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.intention.IntentionActionDelegate
import com.intellij.codeInsight.intention.IntentionActionWithOptions
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import org.assertj.core.api.Assertions.assertThat

class ErrorProneExternalAnnotatorTest : ErrorProneLightTestCase() {

    private val source = "class Many {\n  public String toString() { return \"\"; }\n}\n"

    override fun setUp() {
        super.setUp()
        // The daemon runs an external annotator only when the profile knows its paired inspection.
        myFixture.enableInspections(ErrorProneInspection())
        myFixture.configureByText("Many.java", source)
    }

    /** Only this plugin's highlights: the light project has no JDK, so `String` is unresolved too. */
    private fun errorProneHighlights(): List<HighlightInfo> =
        myFixture.doHighlighting().filter { it.description?.startsWith("[") == true }

    fun `test underlines the token Error Prone points at`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        val info = errorProneHighlights().single()
        assertThat(info.text).isEqualTo("toString")
        assertThat(info.severity).isEqualTo(HighlightSeverity.WARNING)
        assertThat(info.description).isEqualTo("[MissingOverride] toString overrides method in Object; expected @Override")
        assertThat(info.toolTip).contains("(Error Prone)", "https://errorprone.info/bugpattern/MissingOverride")
    }

    fun `test keeps showing diagnostics in a file the IDE finds errors in`() {
        // The IDE's own errors say nothing about what the last build reported; while the user types,
        // there nearly always is one.
        val broken = "class Many {\n  public String toString() { return \"\"; }\n  Undefined field;\n}\n"
        myFixture.configureByText("Many.java", broken)
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        val highlights = myFixture.doHighlighting()

        assertThat(highlights.map { it.description }).anyMatch { it?.startsWith("Cannot resolve symbol 'Undefined'") == true }
        assertThat(highlights.map { it.description }).contains("[MissingOverride] toString overrides method in Object; expected @Override")
    }

    fun `test maps Error Prone severities`() {
        commit(
            CompileOutcome.FULL,
            diagnostic(line = 2, column = 17, check = "DeadException", severity = ErrorProneSeverity.ERROR),
            // On another token: the platform hides a weaker highlight that an error covers exactly
            // (UpdateHighlightersUtil.isWarningCoveredByError).
            diagnostic(line = 1, column = 7, check = "Suggestion", severity = ErrorProneSeverity.NOTE),
        )

        val severities = errorProneHighlights().associate { it.description!!.substringBefore("]") to it.severity }
        assertThat(severities).containsEntry("[DeadException", HighlightSeverity.ERROR)
        assertThat(severities).containsEntry("[Suggestion", HighlightSeverity.WEAK_WARNING)
    }

    fun `test shows the suggested fix in the tooltip, escaped`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, suggestion = "List<String> x = f();"))

        assertThat(errorProneHighlights().single().toolTip).contains("Did you mean: <code>List&lt;String&gt; x = f();</code>")
    }

    fun `test lists every fix Error Prone offers in the tooltip`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, suggestion = "'a(Locale.ROOT)' or 'a(Locale.getDefault())' or to remove this line"))

        assertThat(errorProneHighlights().single().toolTip).contains(
            "Did you mean: <code>a(Locale.ROOT)</code>",
            "<br>or: <code>a(Locale.getDefault())</code>",
            "<br>or: remove this line",
        )
    }

    private fun previewOfFix(): IntentionPreviewInfo {
        myFixture.editor.caretModel.moveToOffset(source.indexOf("toString"))
        // As the fix hands it over: the popup turns a custom diff into a diff of its own.
        val fix = IntentionActionDelegate.unwrap(myFixture.findSingleIntention("Apply Error Prone fix"))
        return fix.generatePreview(project, myFixture.editor, myFixture.file)
    }

    fun `test previews the fix Error Prone applies as a change of its line`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true, suggestion = "'@Override public String toString() { return \"\"; }' or 'x'"))

        val preview = previewOfFix() as IntentionPreviewInfo.CustomDiff

        assertThat(preview.originalText()).isEqualTo("public String toString() { return \"\"; }")
        assertThat(preview.modifiedText()).isEqualTo("@Override public String toString() { return \"\"; }")
    }

    fun `test previews a removal as the line gone`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true, suggestion = "to remove this line"))

        assertThat((previewOfFix() as IntentionPreviewInfo.CustomDiff).modifiedText()).isEmpty()
    }

    fun `test previews a fix of another line as that line, not as a change of this one`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true, suggestion = "'private static final Logger LOG = Logger.create();'"))

        val preview = previewOfFix() as IntentionPreviewInfo.Html

        assertThat(preview.content().toString()).contains("private static final Logger LOG = Logger.create();")
    }

    fun `test a later full build without diagnostics removes the highlight`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        assertThat(errorProneHighlights()).hasSize(1)

        commit(CompileOutcome.FULL)

        assertThat(errorProneHighlights()).isEmpty()
    }

    fun `test suppresses a check with @SuppressWarnings on the declaration it is in`() {
        myFixture.configureByText("Many.java", "class Many {\n  public String to<caret>String() { return \"\"; }\n}\n")
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        myFixture.launchAction(myFixture.findSingleIntention("Suppress 'MissingOverride' for method 'toString'"))

        // Fully qualified here: the light project has no JDK to shorten java.lang.SuppressWarnings against.
        assertThat(myFixture.file.text).contains("SuppressWarnings(\"MissingOverride\")")
        assertThat(myFixture.file.text.indexOf("SuppressWarnings")).isLessThan(myFixture.file.text.indexOf("toString"))
        // Error Prone reads the annotation itself; the highlight goes without waiting for a build.
        assertThat(errorProneHighlights()).isEmpty()
    }

    private fun edit(change: (Document) -> Unit) {
        WriteCommandAction.runWriteCommandAction(project) { change(myFixture.editor.document) }
    }

    fun `test hides a diagnostic whose line is commented out, until it is uncommented`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        val lineStart = myFixture.editor.document.getLineStartOffset(1)

        edit { it.insertString(lineStart, "//") }
        assertThat(errorProneHighlights()).isEmpty()

        edit { it.deleteString(lineStart, lineStart + 2) }
        assertThat(errorProneHighlights().map { it.text }).containsExactly("toString")
    }

    fun `test hides a diagnostic whose line is deleted rather than moving it onto a neighbour`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        // Deleted from the flagged token on: its marker survives at the edge of the deletion, which
        // now holds the next line's code.
        edit { it.deleteString(source.indexOf("toString"), it.getLineStartOffset(2)) }

        assertThat(errorProneHighlights()).isEmpty()
    }

    fun `test keeps a diagnostic through a change of indentation`() {
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        edit { it.insertString(it.getLineStartOffset(1), "    ") }

        assertThat(errorProneHighlights().map { it.text }).containsExactly("toString")
    }

    private val locals = "class Many {\n  void f() {\n    String s = \"\".toUpperCase();\n    String t = \"\".toUpperCase();\n  }\n  String g = \"\".toUpperCase();\n}\n"

    /** A StringCaseLocaleUsage on the toUpperCase of [line] (1-based) of [locals]. */
    private fun upperCaseAt(line: Int) =
        diagnostic(line = line, column = locals.lines()[line - 1].indexOf("toUpperCase") + 1, check = "StringCaseLocaleUsage")

    fun `test suppresses in the narrowest declaration, and offers the wider ones`() {
        myFixture.configureByText("Many.java", locals)
        commit(CompileOutcome.FULL, upperCaseAt(3))
        myFixture.editor.caretModel.moveToOffset(locals.indexOf("toUpperCase"))

        val suppress = myFixture.findSingleIntention("Suppress 'StringCaseLocaleUsage' for variable 's'")

        // The wider ones are its options: a submenu in the IDE, listed alongside in a test.
        assertThat((IntentionActionDelegate.unwrap(suppress) as IntentionActionWithOptions).options.map { it.text })
            .containsExactly("Suppress 'StringCaseLocaleUsage' for method 'f'", "Suppress 'StringCaseLocaleUsage' for class 'Many'")
        myFixture.launchAction(suppress)
        assertThat(myFixture.editor.document.text).contains("SuppressWarnings(\"StringCaseLocaleUsage\") String s")
    }

    fun `test a suppression settles the check's other diagnostics in its declaration at once`() {
        myFixture.configureByText("Many.java", locals)
        commit(CompileOutcome.FULL, upperCaseAt(3), upperCaseAt(4), upperCaseAt(6))
        myFixture.editor.caretModel.moveToOffset(locals.indexOf("toUpperCase"))
        myFixture.launchAction(myFixture.findSingleIntention("Suppress 'StringCaseLocaleUsage' for method 'f'"))

        // The field's is outside the method: the next build decides about it.
        assertThat(errorProneHighlights()).hasSize(1)
        myFixture.performEditorAction(IdeActions.ACTION_UNDO)
        assertThat(errorProneHighlights()).hasSize(3)
    }

    fun `test undoing a suppression brings the diagnostic back`() {
        myFixture.configureByText("Many.java", "class Many {\n  public String to<caret>String() { return \"\"; }\n}\n")
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))
        myFixture.launchAction(myFixture.findSingleIntention("Suppress 'MissingOverride' for method 'toString'"))

        myFixture.performEditorAction(IdeActions.ACTION_UNDO)

        assertThat(myFixture.editor.document.text).doesNotContain("SuppressWarnings")
        assertThat(errorProneHighlights().map { it.text }).containsExactly("toString")
    }

    fun `test applies Error Prone's fix as one undoable edit`() {
        commit(
            CompileOutcome.FULL,
            diagnostic(line = 2, column = 17, fixable = true),
            diagnostic(line = 1, column = 7, check = "Other"),
        )
        assertThat(applyFix(project, myFixture.file.virtualFile, "MissingOverride", readFix(myFixture.file, addOverride)!!)).isTrue()

        assertThat(myFixture.editor.document.text).isEqualTo("class Many {\n  @Override\n  public String toString() { return \"\"; }\n}\n")
        // What it fixed goes at once; the other check's diagnostic stays on its token.
        assertThat(errorProneHighlights().map { it.text }).containsExactly("Many")

        myFixture.performEditorAction(IdeActions.ACTION_UNDO)

        assertThat(myFixture.editor.document.text).isEqualTo(source)
        assertThat(errorProneHighlights().map { it.text }).containsExactlyInAnyOrder("Many", "toString")
    }

    private val addOverride = "--- Many.java\n+++ Many.java\n@@ -1,3 +1,4 @@\n class Many {\n+  @Override\n   public String toString() { return \"\"; }\n }\n"

    fun `test names the class a fix imports that the file cannot see`() {
        // Some of Error Prone's fixes use Guava, whether the project has it or not.
        val patch = "--- Many.java\n+++ Many.java\n@@ -1,1 +1,2 @@\n+import com.google.common.base.Splitter;\n class Many {\n"

        assertThat(readFix(myFixture.file, patch)!!.missingClass).isEqualTo("com.google.common.base.Splitter")
        assertThat(readFix(myFixture.file, addOverride)!!.missingClass).isNull()
    }

    fun `test applies a fix after an edit elsewhere in the file`() {
        val fix = readFix(myFixture.file, addOverride)!!
        edit { it.insertString(it.textLength, "// later\n") }

        assertThat(applyFix(project, myFixture.file.virtualFile, "MissingOverride", fix)).isTrue()
        assertThat(myFixture.editor.document.text).startsWith("class Many {\n  @Override\n  public String toString()")
    }

    fun `test does not apply a fix whose place moved or changed`() {
        val fix = readFix(myFixture.file, addOverride)!!
        // A line above shifts every line the patch names: its context no longer matches there.
        edit { it.insertString(0, "// earlier\n") }

        assertThat(applyFix(project, myFixture.file.virtualFile, "MissingOverride", fix)).isFalse()
        assertThat(myFixture.editor.document.text).isEqualTo("// earlier\n$source")
    }

    fun `test offers Error Prone's own fix only where it has one`() {
        myFixture.configureByText("Many.java", "class Many {\n  public String to<caret>String() { return \"\"; }\n}\n")

        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true))
        assertThat(myFixture.filterAvailableIntentions("Apply Error Prone fix").map { it.text })
            .containsExactly("Apply Error Prone fix for 'MissingOverride' in this file")

        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = false))
        assertThat(myFixture.filterAvailableIntentions("Apply Error Prone fix")).isEmpty()
    }

    fun `test says while Error Prone writes a fix, and does not start it again`() {
        myFixture.configureByText("Many.java", "class Many {\n  public String to<caret>String() { return \"\"; }\n}\n")
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true))
        val builds = ErrorProneBuilds.getInstance(project)
        val file = myFixture.file.virtualFile

        assertThat(builds.startFix(file, "MissingOverride")).isTrue()
        try {
            assertThat(builds.startFix(file, "MissingOverride")).isFalse()
            assertThat(myFixture.filterAvailableIntentions("Error Prone is writing").map { it.text })
                .containsExactly("Error Prone is writing its fix for 'MissingOverride'…")
            assertThat(myFixture.filterAvailableIntentions("Apply Error Prone fix")).isEmpty()
        } finally {
            builds.finishFix(file, "MissingOverride")
        }
        assertThat(myFixture.filterAvailableIntentions("Apply Error Prone fix").map { it.text })
            .containsExactly("Apply Error Prone fix for 'MissingOverride' in this file")
    }

    fun `test does not offer Error Prone's fix in generated code, which the next build would undo`() {
        val text = "class Gen {\n  public String toString() { return \"\"; }\n}\n"
        myFixture.configureFromExistingVirtualFile(generatedFile("Gen.java", text))
        myFixture.editor.caretModel.moveToOffset(text.indexOf("toString"))
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17, fixable = true))

        assertThat(myFixture.filterAvailableIntentions("Apply Error Prone fix")).isEmpty()
        // Still reported, and still suppressible where the generator allows it.
        assertThat(myFixture.filterAvailableIntentions("Suppress 'MissingOverride'")).isNotEmpty()
    }

    fun `test nothing is shown when the inspection is turned off`() {
        myFixture.disableInspections(ErrorProneInspection())
        commit(CompileOutcome.FULL, diagnostic(line = 2, column = 17))

        assertThat(errorProneHighlights()).isEmpty()
    }
}
