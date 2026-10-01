package io.github.salatmaster.errorprone

import org.assertj.core.api.Assertions.assertThat
import org.gradle.tooling.BuildException
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.events.OperationType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Drives real Gradle builds of src/test/testData/sample through the Tooling API, with the listener
 * and the build argument the IDE uses. Everything this plugin assumes about Gradle is asserted here,
 * because this is where a new Gradle release would break it.
 *
 * Needs the network the first time, to resolve Error Prone and its Gradle plugin.
 * `-PtestGradleVersion=8.9` runs it against another Gradle.
 */
class ErrorProneEndToEndTest {

    @TempDir
    lateinit var dir: Path

    private data class Commit(val task: String, val outcome: CompileOutcome, val diagnostics: List<ErrorProneDiagnostic>) {
        /**
         * Without the included build's: Gradle before 9.7 files those under the root build's task of
         * the same name, which `files the diagnostics of an included build…` pins down separately.
         */
        val own: List<ErrorProneDiagnostic> get() = diagnostics.filter { File(it.path).name != "Slugs.java" }
    }

    private class Run(
        val commits: List<Commit>,
        val cutOff: Int,
        val javacLimited: List<String>,
        val output: String,
        val failed: Boolean,
    ) {
        fun of(task: String): Commit = commits.single { it.task == task }
    }

    @BeforeEach
    fun copyFixture() {
        // Sources only: runIde -PsampleProject leaves build state and a wrapper in the fixture, and a
        // copied execution history or build directory would make the first build here up to date.
        val sample = File("src/test/testData/sample")
        sample.walkTopDown()
            .onEnter { it.name !in setOf(".gradle", ".idea", "build", "gradle") }
            .filter { it.isFile && it.name !in setOf("gradlew", "gradlew.bat") }
            .forEach { it.copyTo(dir.resolve(it.relativeTo(sample).path).toFile()) }
        // Written here rather than checked in: .gitattributes forces LF on every file, and editors
        // tend to turn tabs into spaces. The tests below add their own files next to these.
        Files.createDirectories(dir.resolve("src/main/java/demo"))
        dir.resolve("src/main/java/demo/Tabs.java").toFile().writeText(
            "package demo;\n\npublic class Tabs {\n\tstatic class T {\n\t\tpublic String toString() { return \"\"; }\n\t}\n}\n"
        )
        dir.resolve("src/main/java/demo/Crlf.java").toFile().writeText(
            "package demo;\r\n\r\npublic class Crlf {\r\n  static class C { public String toString() { return \"\"; } }\r\n}\r\n"
        )
    }

    private fun build(vararg tasks: String, arguments: List<String> = emptyList()): Run {
        val commits = mutableListOf<Commit>()
        var cutOff = 0
        val javacLimited = mutableListOf<String>()
        val output = ByteArrayOutputStream()
        var failed = false
        val listener = ErrorProneBuildListener(
            onTaskFinished = { task, outcome, diagnostics -> commits += Commit(task, outcome, diagnostics) },
            onCutOff = { cutOff += it },
            onJavacLimit = { javacLimited += it },
        )
        connector().connect().use { connection ->
            try {
                connection.newBuild()
                    .forTasks(*tasks)
                    // A contributor's own org.gradle.caching=true would otherwise turn a fresh compile
                    // into FROM-CACHE, which reports nothing.
                    .withArguments(listOf(ErrorProneGradleExtension.THRESHOLD_ARGUMENT, "--no-build-cache") + javaHome() + arguments)
                    .addProgressListener(listener, OperationType.PROBLEMS, OperationType.TASK)
                    .setStandardOutput(output)
                    .setStandardError(output)
                    .run()
            } catch (_: BuildException) {
                failed = true
            }
        }
        return Run(commits, cutOff, javacLimited, output.toString(), failed)
    }

    private fun connector(): GradleConnector {
        val connector = GradleConnector.newConnector().forProjectDirectory(dir.toFile())
        val version = System.getProperty("errorprone.test.gradleVersion")
        return if (version != null) {
            connector.useGradleVersion(version)
        } else {
            connector.useInstallation(File(System.getProperty("errorprone.test.gradleHome")))
        }
    }

    private fun javaHome(): List<String> =
        listOfNotNull(System.getProperty("errorprone.test.javaHome")?.let { "-Dorg.gradle.java.home=$it" })

    /** Every diagnostic of the sample is a MissingOverride on a `toString`, whatever the indentation or line separators. */
    private fun assertEachPointsAtToString(diagnostics: List<ErrorProneDiagnostic>) {
        for (d in diagnostics) {
            val line = File(d.path).readLines()[d.line - 1]
            assertThat(expandedColumnToIndex(line, d.column))
                .describedAs("%s:%d", d.path, d.line)
                .isEqualTo(line.indexOf("toString"))
        }
    }

    @Test
    fun `reports every diagnostic with its task and position`() {
        val run = build("compileJava")

        assertThat(run.failed).describedAs(run.output).isFalse()
        val main = run.of(":compileJava")
        assertThat(main.outcome).isEqualTo(CompileOutcome.FULL)
        // NullAway's findings are the next test's.
        val own = main.own.filter { it.check != "NullAway" }
        // One of each check the shop shows, plus the generated Tabs.java and Crlf.java: well past
        // Gradle's default cap of 15.
        assertThat(own.map { it.check }.toSet()).isEqualTo(SHOP_CHECKS)
        assertThat(own).hasSize(SHOP_DIAGNOSTICS)
        assertThat(own.map { it.severity }.distinct()).containsExactly(ErrorProneSeverity.WARNING)
        assertThat(own.filter { it.link != "https://errorprone.info/bugpattern/${it.check}" }).isEmpty()
        assertEachPointsAtToString(own.filter { File(it.path).name in setOf("Tabs.java", "Crlf.java") })
        for (d in main.own) {
            val line = File(d.path).readLines()[d.line - 1]
            assertThat(expandedColumnToIndex(line, d.column)).describedAs("%s:%d", d.path, d.line).isBetween(0, line.length - 1)
        }
        assertThat(run.cutOff).isZero()
        assertThat(run.javacLimited).isEmpty()
    }

    @Test
    fun `files the diagnostics of an included build under its own task`() {
        val run = build("compileJava")

        // Gradle 9.0 and older give an included build's problems the task path without the build
        // (`:compileJava`), so they land on the root build's task of the same name. The README
        // lists that as a limitation; this pins the behaviour of the Gradle that runs the tests.
        val lib = run.commits.single { commit -> commit.diagnostics.any { File(it.path).name == "Slugs.java" } }
        assertThat(lib.task).endsWith(":compileJava").isNotEqualTo(":compileJava")
        assertThat(lib.outcome).isEqualTo(CompileOutcome.FULL)
        assertThat(lib.diagnostics.map { it.check }).containsExactly("StringCaseLocaleUsage")
    }

    @Test
    fun `the sample itself builds`() {
        // It is also what `runIde -PsampleProject` opens, where people run `build` on it.
        val run = build("build")

        assertThat(run.failed).describedAs(run.output).isFalse()
    }

    @Test
    fun `an incremental build reports only the files it recompiled`() {
        build("compileJava")
        assertThat(build("compileJava").of(":compileJava").outcome).isEqualTo(CompileOutcome.NONE)

        dir.resolve("src/main/java/demo/Tabs.java").toFile().appendText("// edited\n")
        val main = build("compileJava").of(":compileJava")

        assertThat(main.outcome).isEqualTo(CompileOutcome.PARTIAL)
        assertThat(main.diagnostics.map { File(it.path).name }.distinct()).containsExactly("Tabs.java")
    }

    @Test
    fun `a failed build keeps warnings as warnings`() {
        // DeadException is an Error Prone ERROR, so this file fails the build.
        dir.resolve("src/main/java/demo/Broken.java").toFile().writeText(
            "package demo;\n\npublic class Broken {\n  void f() {\n    new Exception();\n  }\n}\n"
        )

        val run = build("compileJava")

        assertThat(run.failed).isTrue()
        val main = run.of(":compileJava")
        assertThat(main.outcome).isEqualTo(CompileOutcome.FAILED)
        assertThat(main.diagnostics.filter { it.check == "DeadException" }.map { it.severity })
            .containsExactly(ErrorProneSeverity.ERROR)
        // Gradle raises every problem of a failed build to ERROR; the javac code still says warning.
        val warnings = main.diagnostics.filter { it.check == "MissingOverride" }
        assertThat(warnings).isNotEmpty()
        assertThat(warnings.map { it.severity }.distinct()).containsExactly(ErrorProneSeverity.WARNING)
    }

    @Test
    fun `Error Prone reports nothing once javac finds an error`() {
        // What the code looks like half-way through an edit, saved.
        dir.resolve("src/main/java/demo/Typo.java").toFile().writeText("package demo;\n\nclass Typo {\n  int f() { return \"\"; }\n}\n")

        val run = build("compileJava")

        assertThat(run.failed).isTrue()
        val main = run.of(":compileJava")
        assertThat(main.outcome).isEqualTo(CompileOutcome.FAILED)
        assertThat(main.own).isEmpty()
    }

    @Test
    fun `reports what an Error Prone plugin finds like a built-in check`() {
        val run = build("compileJava")

        val nullAway = run.of(":compileJava").own.filter { it.check == "NullAway" }
        assertThat(nullAway).hasSize(NULLAWAY_FINDINGS.size)
        for ((file, message) in NULLAWAY_FINDINGS) {
            assertThat(nullAway).describedAs("%s: %s", file, message)
                .filteredOn { File(it.path).name == file && it.message.startsWith(message) }
                .hasSize(1)
        }
        assertThat(nullAway.map { it.severity }.distinct()).containsExactly(ErrorProneSeverity.WARNING)
        // Printed as "(see http://t.uber.com/nullaway )", unlike Error Prone's own links.
        assertThat(nullAway.map { it.link }.distinct()).containsExactly("http://t.uber.com/nullaway")
    }

    @Test
    fun `says when javac stopped reporting at its warning limit`() {
        // javac hands at most 100 warnings per compilation to Gradle (-Xmaxwarns), silently.
        val many = (0 until 110).joinToString("\n") { "  static class D$it { public String toString() { return \"\"; } }" }
        dir.resolve("src/main/java/demo/Lots.java").toFile().writeText("package demo;\n\npublic class Lots {\n$many\n}\n")

        val run = build("compileJava")

        assertThat(run.of(":compileJava").diagnostics).hasSize(100)
        assertThat(run.javacLimited).containsExactly(":compileJava")
        assertThat(build("compileJava").javacLimited).isEmpty()
    }

    private fun initScript(text: String = ERROR_PRONE_INIT_SCRIPT): String =
        dir.resolve("errorprone.init.gradle").toFile()
            .apply { writeText(text) }
            .absolutePath

    @Test
    fun `the fix init script writes Error Prone's fixes as patches and leaves the sources alone`() {
        val patchDir = Files.createDirectories(dir.resolve("patches")).toRealPath()
        val sources = dir.toFile().walkTopDown().filter { it.extension == "java" }.associateWith { it.readText() }
        val script = errorProneInitScript(patchChecks = listOf("MissingOverride", "StringCaseLocaleUsage"), patchDir = patchDir)

        val run = build(ERROR_PRONE_TASK, arguments = listOf("--init-script", initScript(script)))

        assertThat(run.failed).describedAs(run.output).isFalse()
        // Only the checks being patched run, which is why a patching build must not reach the store.
        assertThat(run.commits.flatMap { it.diagnostics }.map { it.check }.toSet())
            .containsOnly("MissingOverride", "StringCaseLocaleUsage")
        val patches = patchDir.toFile().walkTopDown().filter { it.name == "error-prone.patch" }.toList()
        val rebased = patches.map { rebasePatch(it.readText(), it.parentFile.toPath(), dir.toRealPath()) }
        assertThat(rebased.joinToString("")).contains(
            "+  @Override public String toString() {",
            "toUpperCase(Locale.ROOT)",
            "+import java.util.Locale;",
        )
        // Every build of the composite writes a patch of its own, though both have a :compileJava.
        assertThat(rebased.filter { it.contains("lib/src/main/java/lib/Slugs.java") }).hasSize(1)
        assertThat(rebased.filter { it.contains("Slugs.java") && it.contains("Customer.java") }).isEmpty()
        assertThat(sources.filter { (file, text) -> file.readText() != text }.keys).isEmpty()
    }

    private fun patchesIn(patchDir: Path) =
        patchDir.toFile().walkTopDown().filter { it.name == "error-prone.patch" }
            .joinToString("") { rebasePatch(it.readText(), it.parentFile.toPath(), dir.toRealPath()) }

    @Test
    fun `a fix build compiles in full only the tasks it fixes`() {
        // Everything up to date, the included build too, as after any build in the IDE.
        build("compileJava")
        val patchDir = Files.createDirectories(dir.resolve("patches")).toRealPath()
        val script = errorProneInitScript(listOf("MissingOverride", "StringCaseLocaleUsage"), patchDir, targets = listOf(":compileJava"))

        val run = build(":compileJava", arguments = listOf("--init-script", initScript(script)))

        assertThat(run.failed).describedAs(run.output).isFalse()
        assertThat(run.of(":compileJava").outcome).isEqualTo(CompileOutcome.FULL)
        // The included build it depends on stays up to date rather than recompiling in full.
        assertThat(run.commits.filter { it.task != ":compileJava" && it.task.endsWith("compileJava") }.map { it.outcome })
            .containsOnly(CompileOutcome.NONE)
        assertThat(patchesIn(patchDir)).contains("Customer.java").doesNotContain("Slugs.java")
    }

    @Test
    fun `a fix build reaches a task of an included build by its path in the build tree`() {
        val patchDir = Files.createDirectories(dir.resolve("patches")).toRealPath()
        val script = errorProneInitScript(listOf("StringCaseLocaleUsage"), patchDir, targets = listOf(":lib:compileJava"))

        val run = build(":lib:compileJava", arguments = listOf("--init-script", initScript(script)))

        assertThat(run.failed).describedAs(run.output).isFalse()
        assertThat(patchesIn(patchDir)).contains("lib/src/main/java/lib/Slugs.java")
    }

    @Test
    fun `a fix that adds an import keeps the file's import order`() {
        // IntelliJ's layout: static imports last. Error Prone's own default puts them first, and a fix that
        // adds an import reprints the whole block.
        dir.resolve("src/main/java/demo/Imports.java").toFile().writeText(
            "package demo;\n\nimport java.util.List;\n\nimport static java.util.Objects.requireNonNull;\n\n" +
                "public class Imports {\n  String first(List<String> s) { return requireNonNull(s).get(0).toUpperCase(); }\n}\n"
        )
        val patchDir = Files.createDirectories(dir.resolve("patches")).toRealPath()
        val script = errorProneInitScript(patchChecks = listOf("StringCaseLocaleUsage"), patchDir = patchDir, importOrder = "idea")

        val run = build(ERROR_PRONE_TASK, arguments = listOf("--init-script", initScript(script)))

        assertThat(run.failed).describedAs(run.output).isFalse()
        val patch = patchDir.toFile().walkTopDown().filter { it.name == "error-prone.patch" }
            .map { rebasePatch(it.readText(), it.parentFile.toPath(), dir.toRealPath()) { file -> file.fileName.toString() == "Imports.java" } }
            .joinToString("")
        assertThat(patch).contains("+import java.util.Locale;").doesNotContain("-import")
    }

    @Test
    fun `the Run Error Prone init script recompiles every source set in full`() {
        // Leaves everything up to date, which a plain rebuild would then skip.
        build("compileJava")

        val run = build(ERROR_PRONE_TASK, arguments = listOf("--init-script", initScript()))

        assertThat(run.failed).describedAs(run.output).isFalse()
        assertThat(run.of(":compileJava").outcome).isEqualTo(CompileOutcome.FULL)
        assertThat(run.of(":compileJava").own).hasSize(OWN_DIAGNOSTICS)
        // A source set of the build's own, which only "every JavaCompile" reaches.
        assertThat(run.of(":compileExtraJava").outcome).isEqualTo(CompileOutcome.FULL)
        assertThat(run.of(":compileExtraJava").diagnostics.map { File(it.path).name }).containsExactly("ReindexTool.java")
        // An included build the root depends on is compiled for it, and the init script reaches it too.
        assertThat(run.of(":lib:compileJava").outcome).isEqualTo(CompileOutcome.FULL)
        // It changes no input of the compile tasks, so the next ordinary build keeps what it found.
        assertThat(build("compileJava").of(":compileJava").outcome).isEqualTo(CompileOutcome.NONE)
    }

    @Test
    fun `the init script works with the configuration cache`() {
        val arguments = listOf("--init-script", initScript(), "--configuration-cache")
        build(ERROR_PRONE_TASK, arguments = arguments)

        val second = build(ERROR_PRONE_TASK, arguments = arguments)

        assertThat(second.failed).describedAs(second.output).isFalse()
        assertThat(second.output).contains("Reusing configuration cache.")
        assertThat(second.of(":compileJava").outcome).isEqualTo(CompileOutcome.FULL)
        assertThat(second.of(":compileJava").own).hasSize(OWN_DIAGNOSTICS)
    }
}

/**
 * What the shop in src/test/testData/sample shows, one diagnostic each: the list a new Error Prone
 * version or a change to the sample has to keep true.
 */
private val SHOP_CHECKS = setOf(
    "BigDecimalEquals", "BigDecimalLiteralDouble", "CatchAndPrintStackTrace", "DefaultCharset",
    "DoubleCheckedLocking", "EqualsGetClass", "FallThrough", "FloatingPointLiteralPrecision",
    "FutureReturnValueIgnored", "ImmutableEnumChecker", "InconsistentCapitalization", "IntLongMath",
    "InvalidParam", "JavaTimeDefaultTimeZone", "JavaUtilDate", "JdkObsolete", "LockNotBeforeTry",
    "MathAbsoluteNegative", "MissingCasesInEnumSwitch", "MissingOverride", "MixedMutabilityReturnType",
    "ModifiedButNotUsed", "MutablePublicArray", "NarrowCalculation", "NarrowingCompoundAssignment",
    "NonApiType", "OperatorPrecedence", "OrphanedFormatString", "ReferenceEquality", "ShortCircuitBoolean",
    "StringCaseLocaleUsage", "StringSplitter", "SynchronizeOnNonFinalField", "UnusedMethod", "UnusedVariable",
    "WaitNotInLoop",
)

/**
 * What NullAway finds in the sample's delivery package, the only code it checks (@NullMarked): one of
 * each kind, as the file it is in and the start of its message.
 */
private val NULLAWAY_FINDINGS = listOf(
    "Address.java" to "initializer method does not guarantee @NonNull field 'instructions'",
    "Address.java" to "dereferenced expression 'apartment' is @Nullable",
    "CourierRoster.java" to "dereferenced expression 'onShift.get(zone)' is @Nullable",
    "CourierRoster.java" to "unboxing of a @Nullable expression",
    "DeliveryPlanner.java" to "passing @Nullable parameter 'address.zone()' where @NonNull is required",
    "DeliveryPlanner.java" to "assigning @Nullable expression to @NonNull field",
    "DeliveryPlanner.java" to "returning @Nullable expression from method with @NonNull return type",
    "DeliveryPlanner.java" to "switch selector expression 'address.zone()' is @Nullable",
    "DeliveryPlanner.java" to "enhanced-for expression 'backlog' is @Nullable",
    "SmsNotifier.java" to "parameter phone is @NonNull, but parameter in superclass method",
    "NightEta.java" to "method returns @Nullable, but superclass method",
)

/** The shop's diagnostics, plus a MissingOverride each in the generated Tabs.java and Crlf.java. */
private val SHOP_DIAGNOSTICS = SHOP_CHECKS.size + 2

/** Everything the sample's own code gets from :compileJava. */
private val OWN_DIAGNOSTICS = SHOP_DIAGNOSTICS + NULLAWAY_FINDINGS.size
