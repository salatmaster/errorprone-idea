package io.github.salatmaster.errorprone

import com.intellij.notification.Notification
import com.intellij.notification.Notifications
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.LocalFileSystem
import org.assertj.core.api.Assertions.assertThat
import org.gradle.tooling.CancellationToken
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.LongRunningOperation
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.problems.ContextualLabel
import org.gradle.tooling.events.problems.LineInFileLocation
import org.gradle.tooling.events.problems.Problem
import org.gradle.tooling.events.problems.ProblemDefinition
import org.gradle.tooling.events.problems.ProblemId
import org.gradle.tooling.events.problems.SingleProblemEvent
import org.gradle.tooling.events.problems.TaskPathLocation
import org.gradle.tooling.events.task.TaskFinishEvent
import org.gradle.tooling.events.task.TaskOperationDescriptor
import org.gradle.tooling.events.task.TaskSuccessResult
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.build.BuildEnvironment
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files

/**
 * The IDE side of the Gradle hook: what the extension does with the context the IDE hands it. The
 * Tooling API objects are stand-ins answering only what the extension asks; how real Gradle builds
 * behave is ErrorProneEndToEndTest's business.
 */
class ErrorProneGradleExtensionTest : ErrorProneLightTestCase() {

    private val extension = ErrorProneGradleExtension()

    fun `test builds of two linked Gradle projects keep each other's diagnostics`() {
        val a = javaFile("A")
        val b = javaFile("B")

        build(context(root = a.parentFile), a)
        build(context(root = b.parentFile), b)

        // Both roots call their compile task `:compileJava`; a full compile of one must not wipe the other.
        assertThat(store.files().map { it.name }).containsExactlyInAnyOrder("A.java", "B.java")
    }

    fun `test a build that only writes fixes leaves the diagnostics alone`() {
        val a = javaFile("A")
        build(context(root = a.parentFile), a)

        // It runs the patched checks only, so what it reports is not the whole truth about any task.
        assertThat(build(context(root = a.parentFile, patchBuild = true), a)).isZero()

        assertThat(store.files().map { it.name }).containsExactly("A.java")
    }

    fun `test says Gradle is too old only when Run Error Prone was asked for`() {
        val shown = notifications()

        // Most projects on an old Gradle do not use Error Prone at all; an ordinary build says nothing.
        assertThat(build(context(version = "8.10"), javaFile("A"))).isZero()
        assertThat(shown).isEmpty()

        assertThat(build(context(version = "8.10", runErrorProne = true), javaFile("B"))).isZero()
        assertThat(shown).hasSize(1)
        assertThat(shown.single().content).contains("Gradle 8.14", "Gradle 8.10")
    }

    fun `test says as Error Prone when javac's warning limit cut it short, and how to get past it`() {
        val shown = notifications()
        val a = javaFile("A")

        build(context(root = a.parentFile), a, warnings = JAVAC_MAX_WARNINGS)

        val notification = shown.single()
        assertThat(notification.title).isEqualTo("Error Prone")
        assertThat(notification.content).contains(":compileJava")
        assertThat(notification.actions.map { it.templateText }).containsExactly("Copy Gradle snippet")
    }

    fun `test writes the javac limit snippet for every project, in the build's own DSL`() {
        val groovy = Files.createTempDirectory("errorprone-build").toFile()
        val kotlin = Files.createTempDirectory("errorprone-build").toFile().also { File(it, "settings.gradle.kts").writeText("") }

        // The task named is often a subproject's, and the root script is where people paste it.
        assertThat(maxWarningsSnippet(groovy)).isEqualTo(
            "allprojects { tasks.withType(JavaCompile).configureEach { options.compilerArgs.addAll(['-Xmaxwarns', '10000']) } }",
        )
        assertThat(maxWarningsSnippet(kotlin)).isEqualTo(
            "allprojects { tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf(\"-Xmaxwarns\", \"10000\")) } }",
        )
    }

    /** The notifications shown from now on, as they are shown. */
    private fun notifications(): List<Notification> = mutableListOf<Notification>().also { shown ->
        project.messageBus.connect(testRootDisposable).subscribe(Notifications.TOPIC, object : Notifications {
            override fun notify(notification: Notification) {
                shown += notification
            }
        })
    }

    fun `test raises Gradle's problem threshold once, and only for task runs on a supported Gradle`() {
        fun thresholds(context: GradleExecutionContext): Int {
            extension.configureSettings(context.settings, context)
            extension.configureSettings(context.settings, context)
            return context.settings.arguments.count { it == ErrorProneGradleExtension.THRESHOLD_ARGUMENT }
        }

        assertThat(thresholds(context())).isEqualTo(1)
        assertThat(thresholds(context(type = ExternalSystemTaskType.RESOLVE_PROJECT))).isZero()
        assertThat(thresholds(context(version = "8.10"))).isZero()
    }

    /** A Java file on disk, in a directory of its own that stands for a Gradle build root. */
    private fun javaFile(name: String): File {
        val root = Files.createTempDirectory("errorprone-build").toFile()
        return File(root, "$name.java").apply { writeText("class $name { public String toString() { return \"\"; } }\n") }
            .also { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
    }

    /**
     * Runs one Gradle execution through the extension: `:compileJava` reports [warnings] diagnostics in
     * [file] and finishes in full. Returns how many listeners the extension attached.
     */
    private fun build(context: GradleExecutionContext, file: File, warnings: Int = 1): Int {
        val listeners = mutableListOf<ProgressListener>()
        val operation = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LongRunningOperation::class.java)) { proxy, method, args ->
            if (method.name == "addProgressListener" && args[0] is ProgressListener) listeners += args[0] as ProgressListener
            proxy
        } as LongRunningOperation

        extension.configureOperation(operation, context)
        for (listener in listeners) {
            repeat(warnings) { listener.statusChanged(problem(file.path, ":compileJava")) }
            listener.statusChanged(finished(":compileJava"))
        }
        return listeners.size
    }

    private fun context(
        type: ExternalSystemTaskType = ExternalSystemTaskType.EXECUTE_TASK,
        version: String = "9.7",
        root: File = Files.createTempDirectory("errorprone-build").toFile(),
        runErrorProne: Boolean = false,
        patchBuild: Boolean = false,
    ): GradleExecutionContext = FakeContext(project, type, version, root, runErrorProne, patchBuild)

    private class FakeContext(
        override val project: Project,
        type: ExternalSystemTaskType,
        version: String,
        root: File,
        runErrorProne: Boolean,
        patchBuild: Boolean,
    ) : UserDataHolderBase(), GradleExecutionContext {
        override val projectPath: String = root.path
        override val taskId: ExternalSystemTaskId = ExternalSystemTaskId.create(GradleConstants.SYSTEM_ID, type, project)
        override val settings: GradleExecutionSettings =
            GradleExecutionSettings().apply {
                if (runErrorProne) putUserData(RUN_ERROR_PRONE, true)
                if (patchBuild) putUserData(PATCH_BUILD, true)
            }
        override val listener: ExternalSystemTaskNotificationListener = object : ExternalSystemTaskNotificationListener {}
        override val cancellationToken: CancellationToken = GradleConnector.newCancellationTokenSource().token()
        override val buildEnvironment: BuildEnvironment =
            fake("getBuildIdentifier" to fake<BuildIdentifier>("getRootDir" to root))
        override val gradleVersion: GradleVersion = GradleVersion.version(version)
    }
}

private fun problem(path: String, task: String): SingleProblemEvent = fake(
    "getProblem" to fake<Problem>(
        "getDefinition" to fake<ProblemDefinition>("getId" to fake<ProblemId>("getName" to "compiler.warn.error.prone")),
        "getContextualLabel" to fake<ContextualLabel>(
            "getContextualLabel" to "[MissingOverride] toString overrides method in Object; expected @Override",
        ),
        "getDetails" to null,
        "getOriginLocations" to listOf(fake<LineInFileLocation>("getPath" to path, "getLine" to 1, "getColumn" to 1, "getLength" to 0)),
        "getContextualLocations" to listOf(fake<TaskPathLocation>("getBuildTreePath" to task)),
    ),
)

private fun finished(task: String): TaskFinishEvent = fake(
    "getDescriptor" to fake<TaskOperationDescriptor>("getTaskPath" to task),
    "getResult" to fake<TaskSuccessResult>("isUpToDate" to false, "isFromCache" to false, "isIncremental" to false),
)

/** A stand-in for a Tooling API interface that answers [answers] by method name and fails on anything else. */
private inline fun <reified T> fake(vararg answers: Pair<String, Any?>): T {
    val byName = answers.toMap()
    return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args[0]
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "fake ${T::class.java.simpleName}"
            in byName -> byName[method.name]
            else -> error("fake ${T::class.java.simpleName} was asked for ${method.name}")
        }
    } as T
}
