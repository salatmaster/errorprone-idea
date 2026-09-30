package io.github.salatmaster.errorprone

/** How Error Prone rated a diagnostic. */
enum class ErrorProneSeverity { ERROR, WARNING, NOTE }

/**
 * One Error Prone diagnostic, as a Gradle build reported it.
 *
 * [line] and [column] are javac's: 1-based, and the column counts a tab as reaching the next
 * multiple of 8 (see [expandedColumnToIndex]). [length] is how many characters the diagnostic
 * covers; javac nearly always reports 0, which means "the token at this position". [fixable] says
 * Error Prone has a fix for it: javac's text then offers one with "Did you mean".
 */
data class ErrorProneDiagnostic(
    val path: String,
    val line: Int,
    val column: Int,
    val length: Int,
    val check: String,
    val severity: ErrorProneSeverity,
    val message: String,
    val link: String?,
    val suggestion: String?,
    val fixable: Boolean = false,
) {
    /** The one-line form shown by the editor, the status bar and the Problems tool window. */
    val text: String get() = "[$check] $message"

    companion object {
        private val SEVERITY_BY_CODE = mapOf(
            "compiler.err.error.prone" to ErrorProneSeverity.ERROR,
            "compiler.warn.error.prone" to ErrorProneSeverity.WARNING,
            "compiler.note.error.prone" to ErrorProneSeverity.NOTE,
        )
        private val LABEL = Regex("""\[([^\]]+)]\s*(.*)""")
        // A plugin's link may be spaced out: NullAway prints "(see http://t.uber.com/nullaway )".
        private val LINK = Regex("""\(see (\S+)\s*\)""")
        private val SUGGESTION = Regex("""Did you mean '(.*)'\?""")

        /**
         * What javac prints after the message. Gradle keeps only the label's first line, splitting on
         * the platform's line separator — which javac does not use, so on Windows the whole text
         * arrives flattened into one line.
         */
        private val TRAILER = Regex("""\s+(\(see \S+\s*\)|Did you mean ).*$""")

        /**
         * Builds a diagnostic from the parts of a Gradle problem, or returns null when the problem is
         * not an Error Prone diagnostic this plugin can place.
         *
         * [code] is the javac diagnostic code, which Gradle uses as the problem id. Severity is read
         * from it rather than from the problem's own severity, because Gradle raises every problem of
         * a failed build to ERROR. [label] is Gradle's contextual label, `[Check] message`. [details]
         * is javac's full text, which alone carries the documentation link and the suggested fix.
         */
        fun fromProblem(
            code: String,
            label: String?,
            details: String?,
            path: String,
            line: Int,
            column: Int,
            length: Int,
        ): ErrorProneDiagnostic? {
            val severity = SEVERITY_BY_CODE[code] ?: return null
            if (line < 1) return null
            val parts = label?.let { LABEL.matchEntire(it.trim()) } ?: return null
            return ErrorProneDiagnostic(
                path = path,
                line = line,
                // Gradle reports -1 for a column or a length javac did not give.
                column = column.coerceAtLeast(1),
                length = length.coerceAtLeast(0),
                check = parts.groupValues[1],
                severity = severity,
                message = parts.groupValues[2].replace(TRAILER, ""),
                link = details?.let { LINK.find(it)?.groupValues?.get(1) },
                suggestion = details?.let { SUGGESTION.find(it)?.groupValues?.get(1) },
                fixable = details?.contains("Did you mean") == true,
            )
        }
    }
}

private const val TAB_WIDTH = 8

/**
 * Converts a javac column into a character index within [lineText].
 *
 * javac expands every tab to the next multiple of 8 before it numbers columns
 * (JCDiagnostic.SourcePosition), so for `\t\tint unused` it reports column 21 for `unused`, which is
 * index 6. Line and column, unlike javac's character offset, do not depend on the file's line
 * separators, which the IDE's documents normalise. A column past the end maps to the line's end.
 */
fun expandedColumnToIndex(lineText: CharSequence, column: Int): Int {
    val target = column - 1
    var expanded = 0
    for (index in lineText.indices) {
        if (expanded >= target) return index
        expanded = if (lineText[index] == '\t') (expanded / TAB_WIDTH + 1) * TAB_WIDTH else expanded + 1
    }
    return lineText.length
}
