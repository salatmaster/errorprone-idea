package io.github.salatmaster.errorprone

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ErrorProneDiagnosticTest {

    // javac's text for a MissingOverride, exactly as Gradle puts it in the problem's details.
    private val details = """
        /src/demo/Many.java:4: warning: [MissingOverride] toString overrides method in Object; expected @Override
          static class C0 { public String toString() { return ""; } }
                                          ^
            (see https://errorprone.info/bugpattern/MissingOverride)
          Did you mean 'static class C0 { @Override public String toString() { return ""; } }'?
    """.trimIndent()

    private val label = "[MissingOverride] toString overrides method in Object; expected @Override"

    private fun parse(
        code: String = "compiler.warn.error.prone",
        label: String? = this.label,
        details: String? = this.details,
        line: Int = 4,
        column: Int = 35,
        length: Int = 0,
    ) = ErrorProneDiagnostic.fromProblem(code, label, details, "/src/demo/Many.java", line, column, length)

    @Test
    fun `parses an Error Prone warning`() {
        assertThat(parse()).isEqualTo(
            ErrorProneDiagnostic(
                path = "/src/demo/Many.java",
                line = 4,
                column = 35,
                length = 0,
                check = "MissingOverride",
                severity = ErrorProneSeverity.WARNING,
                message = "toString overrides method in Object; expected @Override",
                link = "https://errorprone.info/bugpattern/MissingOverride",
                suggestion = "static class C0 { @Override public String toString() { return \"\"; } }",
                fixable = true,
            )
        )
    }

    @Test
    fun `reads the severity from the javac code`() {
        assertThat(parse(code = "compiler.err.error.prone")!!.severity).isEqualTo(ErrorProneSeverity.ERROR)
        assertThat(parse(code = "compiler.warn.error.prone")!!.severity).isEqualTo(ErrorProneSeverity.WARNING)
        assertThat(parse(code = "compiler.note.error.prone")!!.severity).isEqualTo(ErrorProneSeverity.NOTE)
    }

    @Test
    fun `ignores problems that are not Error Prone diagnostics`() {
        assertThat(parse(code = "compiler.err.cant.resolve.location")).isNull()
        assertThat(parse(code = "deprecated-feature-used")).isNull()
    }

    @Test
    fun `ignores a problem without a label or a line`() {
        assertThat(parse(label = null)).isNull()
        assertThat(parse(label = "no check name here")).isNull()
        assertThat(parse(line = 0)).isNull()
    }

    @Test
    fun `leaves link and suggestion empty when javac printed neither`() {
        val parsed = parse(details = "/src/demo/Many.java:4: warning: [Custom] a check without docs")!!

        assertThat(parsed.link).isNull()
        assertThat(parsed.suggestion).isNull()
        assertThat(parse(details = null)!!.link).isNull()
    }

    @Test
    fun `clamps a column and a length Gradle did not know`() {
        val parsed = parse(column = -1, length = -1)!!

        assertThat(parsed.column).isEqualTo(1)
        assertThat(parsed.length).isEqualTo(0)
    }

    @Test
    fun `knows when Error Prone has a fix, including one that removes code`() {
        assertThat(parse()!!.fixable).isTrue()
        assertThat(parse(details = "Foo.java:3: warning: [UnusedMethod] Method 'm' is never used.\n  Did you mean to remove this line?")!!.fixable)
            .isTrue()
        assertThat(parse(details = "Foo.java:3: warning: [FallThrough] Execution may fall through")!!.fixable).isFalse()
        assertThat(parse(details = null)!!.fixable).isFalse()
    }

    @Test
    fun `keeps the link and the suggestion out of a label Gradle flattened`() {
        // On Windows Gradle splits javac's message on CRLF, finds none, and flattens the whole of it
        // into the label, link and suggestion included.
        val flattened = "[MissingOverride] toString overrides method in Object; expected @Override" +
            "     (see https://errorprone.info/bugpattern/MissingOverride)" +
            "   Did you mean 'static class C0 { @Override public String toString() { return \"\"; } }'?"

        assertThat(parse(label = flattened)!!.message).isEqualTo("toString overrides method in Object; expected @Override")
    }

    @Test
    fun `reads the link of an Error Prone plugin that spaces it out`() {
        // NullAway's, as javac prints it: a space before the closing parenthesis.
        val nullAway = """
            /src/demo/Address.java:38: warning: [NullAway] dereferenced expression 'apartment' is @Nullable
                return street + ", apt. " + apartment.trim() + ", " + city;
                                                     ^
                (see http://t.uber.com/nullaway )
        """.trimIndent()
        val flattened = "[NullAway] dereferenced expression 'apartment' is @Nullable     (see http://t.uber.com/nullaway )"

        assertThat(parse(label = "[NullAway] dereferenced expression 'apartment' is @Nullable", details = nullAway)!!.link)
            .isEqualTo("http://t.uber.com/nullaway")
        assertThat(parse(label = flattened, details = nullAway)!!.message).isEqualTo("dereferenced expression 'apartment' is @Nullable")
    }

    @Test
    fun `reads a suggestion Error Prone escaped as the text it stands for`() {
        val details = "/src/demo/Ru.java:6: warning: [MissingSummary] A summary line is required\n" +
            "  Did you mean '/** Returns \\u0441\\u043f\\u0438\\u0441\\u043e\\u043a.'?"

        assertThat(parse(details = details)!!.suggestion).isEqualTo("/** Returns список.")
    }

    @Test
    fun `text is the check and the message`() {
        assertThat(parse()!!.text).isEqualTo(label)
    }

    @Test
    fun `a column without tabs is the index plus one`() {
        val line = "  static class C0 { public String toString() { return \"\"; } }"

        assertThat(expandedColumnToIndex(line, 35)).isEqualTo(line.indexOf("toString"))
    }

    @Test
    fun `a tab reaches the next multiple of eight`() {
        // javac reports column 21 for `unused` here: two tabs take it to 16, `int ` to 20.
        assertThat(expandedColumnToIndex("\t\tint unused = 1;", 21)).isEqualTo(6)
        // A tab after text still stops at 8: `a` is 1, `b` is 2, the tab ends at 8, `c` is 9.
        assertThat(expandedColumnToIndex("ab\tc", 9)).isEqualTo(3)
    }

    @Test
    fun `a column past the line maps to its end`() {
        assertThat(expandedColumnToIndex("abc", 40)).isEqualTo(3)
        assertThat(expandedColumnToIndex("", 1)).isEqualTo(0)
        assertThat(expandedColumnToIndex("abc", 1)).isEqualTo(0)
    }
}
