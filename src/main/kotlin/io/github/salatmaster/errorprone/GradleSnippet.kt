package io.github.salatmaster.errorprone

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JEditorPane
import kotlin.math.ceil

internal enum class Dsl(val label: String, val extension: String) { GROOVY("Groovy", "gradle"), KOTLIN("Kotlin", "kts") }

/** A check's severity as gradle-errorprone-plugin sets it: [method] is the plugin's. */
internal enum class Level(val label: String, val method: String) { OFF("Off", "disable"), ERROR("Error", "error") }

/** What a snippet changes in a Gradle build. */
internal sealed interface GradleChange {
    data class Severity(val check: String) : GradleChange
    data object WarningLimit : GradleChange
}

/**
 * What sets [check]'s severity in the script that applies net.ltgt.errorprone, as gradle-errorprone-plugin's
 * README configures it. Kotlin needs the plugin's extension imported, which resolves only where the plugin
 * is on the script's classpath: the script that applies it.
 */
internal fun checkSeveritySnippet(dsl: Dsl, level: Level, check: String): String {
    val body = """
        |    options.errorprone {
        |        ${level.method}("$check")
        |    }
        |}
    """.trimMargin()
    return when (dsl) {
        Dsl.KOTLIN -> "import net.ltgt.gradle.errorprone.errorprone\n\ntasks.withType<JavaCompile>().configureEach {\n$body"
        Dsl.GROOVY -> "tasks.withType(JavaCompile).configureEach {\n$body"
    }
}

/** What raises javac's warning limit for the Java compile tasks of the project whose script it is in. */
internal fun maxWarningsSnippet(dsl: Dsl): String = when (dsl) {
    Dsl.KOTLIN -> "tasks.withType<JavaCompile>().configureEach {\n    options.compilerArgs.addAll(listOf(\"-Xmaxwarns\", \"10000\"))\n}"
    Dsl.GROOVY -> "tasks.withType(JavaCompile).configureEach {\n    options.compilerArgs += [\"-Xmaxwarns\", \"10000\"]\n}"
}

/** javac's default -Xmaxwarns: it reports no more warnings than this per compilation. */
internal const val JAVAC_MAX_WARNINGS = 100

/**
 * The build scripts under [root] that apply net.ltgt.errorprone, by its id or by a version catalog alias
 * naming it, shallowest first: where a snippet that configures it goes. A settings script, or a plugins
 * block that only declares it (`apply false`), applies nothing.
 */
// ponytail: scripts only; a binary convention plugin applying it from a .kt or .java file goes unnamed.
internal fun scriptsApplyingErrorProne(root: File): List<File> =
    root.walkTopDown()
        .onEnter { it == root || !(it.name.startsWith(".") || it.name in OUTPUT_DIRECTORIES) }
        .filter { it.isFile && (it.name.endsWith(".gradle") || it.name.endsWith(".gradle.kts")) && !it.name.startsWith("settings.gradle") }
        .filter { script -> script.useLines { lines -> lines.any { APPLIES_ERROR_PRONE.containsMatchIn(it) && "apply false" !in it } } }
        .sortedBy { it.relativeTo(root).path.count { c -> c == File.separatorChar } }
        .toList()

private val OUTPUT_DIRECTORIES = setOf("build", "out", "node_modules")
private val APPLIES_ERROR_PRONE = Regex("""net\.ltgt\.errorprone|alias\([^)]*errorprone""")

/** The DSL of the build at [root], judged by its root scripts. */
private fun dslOf(root: File): Dsl =
    if (File(root, "settings.gradle.kts").exists() || File(root, "build.gradle.kts").exists()) Dsl.KOTLIN else Dsl.GROOVY

/**
 * Shows what [change] takes in the Gradle build at [root], once its scripts are searched for the ones that
 * apply Error Prone, which can take a while in a large repository.
 */
internal fun showGradleSnippet(project: Project, root: File, change: GradleChange) {
    val application = ApplicationManager.getApplication()
    application.executeOnPooledThread {
        val scripts = scriptsApplyingErrorProne(root)
        application.invokeLater({ GradleSnippetDialog(project, change, root, scripts).show() }, project.disposed)
    }
}

/**
 * What to add to a Gradle build script for [change], in Groovy or Kotlin, and which of the build's
 * [scripts] apply Error Prone, where it goes. The plugin never edits a build script: what goes in the
 * build stays the build's. Modeless, so a script it opens can be edited with it still showing.
 */
internal class GradleSnippetDialog(
    private val project: Project,
    private val change: GradleChange,
    private val root: File,
    private val scripts: List<File>,
) : DialogWrapper(project, null, true, DialogWrapper.IdeModalityType.MODELESS) {

    private val graph = PropertyGraph()
    private val dslProperty = graph.property(scripts.firstOrNull()?.let { if (it.name.endsWith(".kts")) Dsl.KOTLIN else Dsl.GROOVY } ?: dslOf(root))
    private val levelProperty = graph.property(Level.OFF)

    var dsl: Dsl
        get() = dslProperty.get()
        set(value) = dslProperty.set(value)
    var level: Level
        get() = levelProperty.get()
        set(value) = levelProperty.set(value)

    /** The snippet as the dialog shows it. */
    val shown: String get() = editor.text

    /** What the dialog says the snippet does and where it goes. */
    val explanation: String get() = StringUtil.removeHtmlTags(explanationPane.text)

    /** The scripts the dialog names as applying Error Prone. */
    val where: String get() = if (scripts.isEmpty()) "" else StringUtil.removeHtmlTags(wherePane.text)

    private val editor = EditorTextField("", project, fileTypeOf(dsl)).apply {
        isViewer = true
        setOneLineMode(false)
        setFontInheritedFromLAF(false)
        // Tall enough for the longest snippet it can show, so switching neither scrolls nor resizes.
        val scheme = EditorColorsManager.getInstance().globalScheme
        val line = ceil(getFontMetrics(scheme.getFont(EditorFontType.PLAIN)).height * scheme.lineSpacing).toInt()
        val lines = Dsl.entries.flatMap { dsl -> Level.entries.map { snippet(dsl, it) } }.maxOf { it.lines().size }
        preferredSize = Dimension(JBUI.scale(560), line * (lines + 1))
        addSettingsProvider { it.settings.isLineNumbersShown = false; it.settings.additionalLinesCount = 0 }
    }
    private lateinit var explanationPane: JEditorPane
    private lateinit var wherePane: JEditorPane

    private val copyAction = object : DialogWrapperAction("Copy") {
        override fun doAction(e: ActionEvent?) = copy()
    }

    init {
        title = when (change) {
            is GradleChange.Severity -> "Change the Severity of '${change.check}'"
            GradleChange.WarningLimit -> "Raise javac's Warning Limit"
        }
        setCancelButtonText("Close")
        copyAction.putValue(DEFAULT_ACTION, true)
        init()
        update()
        dslProperty.afterChange(disposable) { update() }
        levelProperty.afterChange(disposable) { update() }
    }

    override fun createActions(): Array<Action> = arrayOf(copyAction, cancelAction)

    override fun getPreferredFocusedComponent(): JComponent = editor

    /** The dialog's content: what it shows, built once. */
    internal lateinit var content: DialogPanel

    override fun createCenterPanel(): JComponent = panel {
        if (change is GradleChange.Severity) {
            row("Severity:") { segmentedButton(Level.entries) { text = it.label }.bind(levelProperty) }
        }
        row("Build script:") { segmentedButton(Dsl.entries) { text = it.label }.bind(dslProperty) }
        row { cell(editor).align(Align.FILL) }.resizableRow()
        row { explanationPane = text("").component }
        if (scripts.isNotEmpty()) {
            row {
                val links = scripts.take(5).mapIndexed { i, script -> "<a href='$i'>${script.relativeTo(root).path}</a>" }
                val more = if (scripts.size > 5) " and ${scripts.size - 5} more" else ""
                wherePane = text("Error Prone is applied in ${links.joinToString(", ")}$more.") { e -> open(scripts[e.description.toInt()]) }.component
            }
        }
        row {
            comment(
                "The plugin never edits build scripts. Compiler options are inputs of the compile tasks, so the next " +
                    "build compiles the Java sources again.",
            )
        }
    }.also { content = it }

    /** Copies the snippet shown, and says so on the button until it changes. */
    fun copy() {
        CopyPasteManager.getInstance().setContents(StringSelection(shown))
        copyAction.putValue(Action.NAME, "Copied")
    }

    private fun update() {
        editor.setFileType(fileTypeOf(dsl))
        editor.text = snippet(dsl, level)
        editor.setCaretPosition(0)
        copyAction.putValue(Action.NAME, "Copy")
        val what = when (change) {
            is GradleChange.Severity ->
                if (level == Level.OFF) "Error Prone stops reporting ${change.check}."
                else "${change.check} fails the build wherever Error Prone finds it."
            GradleChange.WarningLimit ->
                "javac reports at most $JAVAC_MAX_WARNINGS warnings per compilation and drops the rest, Error Prone's " +
                    "findings with them. This lets ten thousand through."
        }
        val import = if (dsl == Dsl.KOTLIN && change is GradleChange.Severity) " The import goes at the top of the script." else ""
        explanationPane.text = "$what Add it to the build script that applies the Error Prone plugin, " +
            "<code>net.ltgt.errorprone</code>; in a build of several projects, that is usually a convention plugin in " +
            "buildSrc or build-logic, which reaches every project that applies it.$import"
    }

    private fun snippet(dsl: Dsl, level: Level): String = when (change) {
        is GradleChange.Severity -> checkSeveritySnippet(dsl, level, change.check)
        GradleChange.WarningLimit -> maxWarningsSnippet(dsl)
    }

    private fun open(script: File) {
        val file = LocalFileSystem.getInstance().let { it.findFileByIoFile(script) ?: it.refreshAndFindFileByIoFile(script) } ?: return
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun fileTypeOf(dsl: Dsl): FileType =
        FileTypeManager.getInstance().getFileTypeByExtension(dsl.extension).takeIf { it !is UnknownFileType } ?: PlainTextFileType.INSTANCE
}
