package io.github.salatmaster.errorprone

import com.intellij.codeInsight.daemon.HighlightDisplayKey
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.NamedColorUtil

/**
 * Shows the stored Error Prone diagnostics of a Java file in the editor.
 *
 * The work happens in [collectInformation], which the daemon calls in a read action: that is where
 * the RangeMarkers can be read consistently. Nothing is left to compute in the background, because
 * the analysis already ran in the Gradle build.
 */
class ErrorProneExternalAnnotator : ExternalAnnotator<List<Located>, List<Located>>() {

    override fun collectInformation(file: PsiFile): List<Located>? {
        val virtualFile = file.virtualFile ?: return null
        return ErrorProneDiagnostics.getInstance(file.project).forFile(virtualFile).ifEmpty { null }
    }

    // The default skips a file the IDE already found errors in, which suits a tool that has to
    // compile it. These diagnostics come from the last build and are just as true while the user is
    // halfway through typing a line, so they are shown regardless.
    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): List<Located>? =
        collectInformation(file)

    override fun doAnnotate(collectedInfo: List<Located>): List<Located> = collectedInfo

    override fun apply(file: PsiFile, annotationResult: List<Located>, holder: AnnotationHolder) {
        val virtualFile = file.virtualFile ?: return
        // The next generation would undo a fix there.
        val fixable = !isGeneratedCode(file.project, virtualFile)
        val records = ErrorProneDiagnostics.getInstance(file.project).records()
        for (located in annotationResult) {
            val (diagnostic, range) = located
            if (range.endOffset > file.textLength) continue
            var annotation = holder.newAnnotation(diagnostic.severity.highlight, diagnostic.text)
                .range(visibleRange(file, range))
                .tooltip(tooltip(diagnostic, freshnessOf(located, records)))
            if (diagnostic.fixable && fixable) annotation = annotation.withFix(ApplyErrorProneFix(located, virtualFile))
            val targets = suppressionTargets(file, range.startOffset).map(::describeTarget)
            val suppress = SuppressErrorProneFix(diagnostic.check, located.marker, targets)
            // Under the inspection's key, the submenu of wider declarations is titled Error Prone, not Annotator.
            val key = HighlightDisplayKey.find(ErrorProneInspection.SHORT_NAME)
            annotation = if (key != null) annotation.newFix(suppress).key(key).registerFix() else annotation.withFix(suppress)
            annotation.create()
        }
    }

    override fun getPairedBatchInspectionShortName(): String = ErrorProneInspection.SHORT_NAME
}

private val ErrorProneSeverity.highlight: HighlightSeverity
    get() = when (this) {
        ErrorProneSeverity.ERROR -> HighlightSeverity.ERROR
        ErrorProneSeverity.WARNING -> HighlightSeverity.WARNING
        ErrorProneSeverity.NOTE -> HighlightSeverity.WEAK_WARNING
    }

/**
 * javac nearly always reports a position rather than a span; the token at that position is what
 * the diagnostic is about — for a MissingOverride on `toString()`, the name `toString`.
 */
private fun visibleRange(file: PsiFile, range: TextRange): TextRange {
    if (!range.isEmpty) return range
    val leaf = file.findElementAt(range.startOffset)
    if (leaf != null && leaf !is PsiWhiteSpace) return leaf.textRange
    return TextRange(range.startOffset, minOf(range.startOffset + 1, file.textLength))
}

/** The highlighting tooltip opens http links in the browser (LineTooltipRenderer), so the link needs no intention. */
private fun tooltip(diagnostic: ErrorProneDiagnostic, freshness: String): String = buildString {
    append("<html><b>").append(escape(diagnostic.check)).append("</b> (Error Prone)<br>")
    append(escape(diagnostic.message))
    diagnostic.fixes.forEachIndexed { i, fix ->
        append(if (i == 0) "<br>Did you mean: " else "<br>or: ")
        append(if (fix.isEmpty()) "remove this line" else "<code>${escape(fix)}</code>")
    }
    diagnostic.link?.let { append("<br><a href=\"").append(escape(it)).append("\">").append(escape(it)).append("</a>") }
    append("<br><font color=\"").append(ColorUtil.toHtmlColor(NamedColorUtil.getInactiveTextColor())).append("\">")
    append(escape(freshness)).append("</font>")
    append("</html>")
}

private fun escape(text: String): String = StringUtil.escapeXmlEntities(text)
