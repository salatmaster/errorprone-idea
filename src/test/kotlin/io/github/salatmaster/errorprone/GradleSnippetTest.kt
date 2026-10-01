package io.github.salatmaster.errorprone

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.dsl.builder.components.SegmentedButtonComponent
import com.intellij.util.ui.UIUtil
import org.assertj.core.api.Assertions.assertThat
import java.awt.datatransfer.DataFlavor
import java.io.File

/** What the plugin shows people to add to their Gradle build, and the window it shows it in. */
class GradleSnippetTest : ErrorProneLightTestCase() {

    fun `test sets a check's severity where Error Prone is configured, in either DSL`() {
        // The way gradle-errorprone-plugin's README configures it: no allprojects, which reaches into
        // other projects from the outside.
        assertThat(checkSeveritySnippet(Dsl.KOTLIN, Level.OFF, "MissingOverride")).isEqualTo(
            """
            import net.ltgt.gradle.errorprone.errorprone

            tasks.withType<JavaCompile>().configureEach {
                options.errorprone {
                    disable("MissingOverride")
                }
            }
            """.trimIndent(),
        )
        assertThat(checkSeveritySnippet(Dsl.GROOVY, Level.ERROR, "MissingOverride")).isEqualTo(
            """
            tasks.withType(JavaCompile).configureEach {
                options.errorprone {
                    error("MissingOverride")
                }
            }
            """.trimIndent(),
        )
    }

    fun `test raises javac's warning limit in either DSL`() {
        assertThat(maxWarningsSnippet(Dsl.KOTLIN)).isEqualTo(
            """
            tasks.withType<JavaCompile>().configureEach {
                options.compilerArgs.addAll(listOf("-Xmaxwarns", "10000"))
            }
            """.trimIndent(),
        )
        assertThat(maxWarningsSnippet(Dsl.GROOVY)).isEqualTo(
            """
            tasks.withType(JavaCompile).configureEach {
                options.compilerArgs += ["-Xmaxwarns", "10000"]
            }
            """.trimIndent(),
        )
    }

    fun `test finds the scripts that apply Error Prone, not those that only declare it`() {
        val root = tempDir()
        fun script(path: String, text: String) = File(root, path).apply { parentFile.mkdirs(); writeText(text) }
        script("settings.gradle.kts", "pluginManagement { plugins { id(\"net.ltgt.errorprone\") version \"5.1.0\" } }")
        script("build.gradle.kts", "plugins {\n    id(\"net.ltgt.errorprone\") version \"5.1.0\" apply false\n}\n")
        val convention = script("build-logic/src/main/kotlin/java-conventions.gradle.kts", "plugins {\n    id(\"net.ltgt.errorprone\")\n}\n")
        val app = script("app/build.gradle", "plugins {\n    alias(libs.plugins.errorprone)\n}\n")
        script("app/build/tmp/copied.gradle.kts", "plugins { id(\"net.ltgt.errorprone\") }")
        script(".gradle/cache/copied.gradle", "apply plugin: 'net.ltgt.errorprone'")
        script("other/build.gradle", "plugins { id 'java' }")

        // Shallowest first: the script nearest the build's root is the likeliest to reach every project.
        assertThat(scriptsApplyingErrorProne(root)).containsExactly(app, convention)
    }

    fun `test shows the snippet in the DSL of the script that applies Error Prone, and copies what it shows`() {
        val root = tempDir()
        val script = File(root, "build.gradle").apply { writeText("plugins { id 'net.ltgt.errorprone' }") }
        File(root, "settings.gradle.kts").writeText("")
        val dialog = GradleSnippetDialog(project, GradleChange.Severity("MissingOverride"), root, listOf(script))
        try {
            // The settings script is Kotlin, but the snippet goes where Error Prone is applied.
            assertThat(dialog.dsl).isEqualTo(Dsl.GROOVY)
            assertThat(dialog.shown).isEqualTo(checkSeveritySnippet(Dsl.GROOVY, Level.OFF, "MissingOverride"))
            assertThat(dialog.where).contains("build.gradle")

            dialog.dsl = Dsl.KOTLIN
            dialog.level = Level.ERROR
            assertThat(dialog.shown).isEqualTo(checkSeveritySnippet(Dsl.KOTLIN, Level.ERROR, "MissingOverride"))
            assertThat(dialog.explanation).contains("import")

            dialog.copy()
            assertThat(CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)).isEqualTo(dialog.shown)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test starts in Kotlin for a Kotlin script, with both switches showing what is selected`() {
        val root = tempDir()
        val script = File(root, "build.gradle.kts").apply { writeText("plugins { id(\"net.ltgt.errorprone\") }") }
        val dialog = GradleSnippetDialog(project, GradleChange.Severity("MissingOverride"), root, listOf(script))
        try {
            assertThat(dialog.dsl).isEqualTo(Dsl.KOTLIN)
            assertThat(dialog.shown).isEqualTo(checkSeveritySnippet(Dsl.KOTLIN, Level.OFF, "MissingOverride"))
            // What the switches show, not the property they are bound to, which is what their selectedItem reads.
            val switches = UIUtil.findComponentsOfType(dialog.content, SegmentedButtonComponent::class.java)
            assertThat(switches.map { it.selectedItem }).containsExactly(Level.OFF, Dsl.KOTLIN)
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }

    fun `test shows how to raise javac's limit in the build's own DSL when no script applies Error Prone`() {
        val root = tempDir().also { File(it, "settings.gradle.kts").writeText("") }
        val dialog = GradleSnippetDialog(project, GradleChange.WarningLimit, root, emptyList())
        try {
            assertThat(dialog.shown).isEqualTo(maxWarningsSnippet(Dsl.KOTLIN))
            assertThat(dialog.where).isEmpty()
        } finally {
            Disposer.dispose(dialog.disposable)
        }
    }
}
