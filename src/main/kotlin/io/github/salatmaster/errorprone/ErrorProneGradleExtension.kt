package io.github.salatmaster.errorprone

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.gradle.tooling.LongRunningOperation
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.ProgressListener
import org.gradle.tooling.events.problems.*
import org.gradle.tooling.events.task.*
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.execution.GradleExecutionContext
import org.jetbrains.plugins.gradle.service.project.GradleExecutionHelperExtension
import org.jetbrains.plugins.gradle.settings.GradleExecutionSettings
import java.awt.datatransfer.StringSelection
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the Error Prone diagnostics of every Gradle task execution the IDE starts: Build Project
 * delegated to Gradle, Gradle tasks and run configurations, and Run Error Prone. All of them go
 * through GradleExecutionHelper.prepareForExecution, which calls this extension; sync does too, and
 * is left alone.
 *
 * Gradle turns each javac diagnostic into a Problems API problem, which the Tooling API delivers as a
 * SingleProblemEvent. The IDE does not subscribe to those itself, so the listener added here is their
 * only consumer. JetBrains' own GradleTaskExecutionMeasuringExtension attaches its listener the same
 * way, and adding a listener is additive: it cannot clobber what other extensions set up.
 *
 * The interface is not a stable API; the plugin verifier in CI catches a signature change.
 */
class ErrorProneGradleExtension : GradleExecutionHelperExtension {

    private val log = Logger.getInstance(ErrorProneGradleExtension::class.java)

    override fun configureSettings(settings: GradleExecutionSettings, context: GradleExecutionContext) {
        try {
            if (!isSupportedTaskExecution(context)) return
            // Called more than once for one execution; the argument must go in once.
            if (settings.arguments.none { it.startsWith(THRESHOLD_PREFIX) }) settings.withArgument(THRESHOLD_ARGUMENT)
        } catch (e: Exception) {
            log.warn("Could not raise Gradle's problem summary threshold", e)
        }
    }

    override fun configureOperation(operation: LongRunningOperation, context: GradleExecutionContext) {
        try {
            if (context.taskId.type != ExternalSystemTaskType.EXECUTE_TASK) return
            val patched = context.settings.getUserData(PATCH_BUILD)
            if (patched != null && context.gradleVersion < TREE_PATHS) return
            val project = context.project
            if (context.gradleVersion < MIN_GRADLE_VERSION) {
                // Most projects on an older Gradle do not use Error Prone at all, so only someone who
                // asked for Run Error Prone is told why nothing will show.
                if (context.settings.getUserData(RUN_ERROR_PRONE) == true) {
                    project.service<ErrorProneNotifier>().once(
                        "gradle-too-old",
                        "Error Prone diagnostics need Gradle ${MIN_GRADLE_VERSION.version} or newer; this project " +
                            "builds with Gradle ${context.gradleVersion.version}. Builds run as usual, but their " +
                            "diagnostics are not shown in the editor.",
                    )
                }
                return
            }
            val store = ErrorProneDiagnostics.getInstance(project)
            // An IDE project can link several Gradle builds, and each calls its root compile task
            // `:compileJava`; the build root keeps one build's full compile from replacing another's.
            val build = context.buildEnvironment.buildIdentifier.rootDir.path
            val listener = ErrorProneBuildListener(
                onTaskFinished = { task, outcome, diagnostics ->
                    // An up-to-date compile reports nothing, which is the usual answer to "why is
                    // nothing shown" after a restart; the log is where that question is settled.
                    if (log.isDebugEnabled && (outcome != CompileOutcome.NONE || diagnostics.isNotEmpty())) {
                        log.debug("$task in $build finished: $outcome, ${diagnostics.size} Error Prone diagnostics")
                    }
                    if (patched == null || task !in patched) store.commit("$build|$task", outcome, resolve(diagnostics))
                },
                onCutOff = { count ->
                    project.service<ErrorProneNotifier>().once(
                        "cut-off",
                        "Gradle withheld $count Error Prone diagnostics from the IDE, so the editor shows only " +
                            "some of them. Please report this, with your Gradle version, at $ISSUES_URL.",
                    )
                },
                onJavacLimit = { task ->
                    if (patched != null && task in patched) return@ErrorProneBuildListener
                    project.service<ErrorProneNotifier>().once(
                        "javac-limit",
                        "javac reported only the first $JAVAC_MAX_WARNINGS warnings of $task, so some of Error " +
                            "Prone's findings there are missing. To see all of them, raise javac's limit in the " +
                            "build's root script.",
                        NotificationAction.createSimple("Copy Gradle snippet") {
                            CopyPasteManager.getInstance().setContents(StringSelection(maxWarningsSnippet(File(build))))
                        },
                    )
                },
            )
            operation.addProgressListener(listener, OperationType.PROBLEMS, OperationType.TASK)
            if (log.isDebugEnabled) log.debug("Listening for Error Prone diagnostics in ${context.taskId} (Gradle ${context.gradleVersion.version})")
        } catch (e: Exception) {
            log.warn("Could not attach the Error Prone listener to a Gradle execution", e)
        }
    }

    private fun isSupportedTaskExecution(context: GradleExecutionContext): Boolean =
        context.taskId.type == ExternalSystemTaskType.EXECUTE_TASK && context.gradleVersion >= MIN_GRADLE_VERSION

    /** A path that is not a local file (a WSL or Docker build target, say) has nothing to highlight. */
    private fun resolve(diagnostics: List<ErrorProneDiagnostic>): Map<VirtualFile, List<ErrorProneDiagnostic>> =
        diagnostics.groupBy { it.path }.mapNotNull { (path, list) ->
            val file = try {
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(path))
            } catch (_: InvalidPathException) {
                null
            }
            if (file == null) log.debug("Dropping ${list.size} Error Prone diagnostics for $path: not a local file")
            file?.let { it to list }
        }.toMap()

    companion object {
        /** The oldest Gradle whose builds report javac diagnostics, with their task, to the Tooling API. */
        val MIN_GRADLE_VERSION: GradleVersion = GradleVersion.version("8.14")

        private const val THRESHOLD_PREFIX = "-Dorg.gradle.internal.problem.summary.threshold="

        /**
         * Gradle forwards only the first 15 problems per problem id to the IDE and summarises the rest,
         * and every Error Prone warning shares one id. This internal option lifts the cap. If a Gradle
         * release renames it, ErrorProneBuildListener sees a ProblemSummariesEvent and says so.
         */
        const val THRESHOLD_ARGUMENT = "${THRESHOLD_PREFIX}100000"

        private const val ISSUES_URL = "https://github.com/salatmaster/errorprone-idea/issues"
    }
}

/**
 * Turns the Tooling API events of one Gradle execution into per-task commits.
 *
 * Problems arrive before the TaskFinishEvent of the task that reported them, and each one names that
 * task, so they are buffered per task and handed over when it finishes: only then is its outcome, and
 * so the way its diagnostics merge, known. A cancelled build never finishes its tasks, and their
 * buffers go away with the listener.
 *
 * Free of the IDE, so the end-to-end test can drive it with a real build.
 */
class ErrorProneBuildListener(
    private val onTaskFinished: (task: String, outcome: CompileOutcome, diagnostics: List<ErrorProneDiagnostic>) -> Unit,
    private val onCutOff: (count: Int) -> Unit,
    private val onJavacLimit: (task: String) -> Unit,
) : ProgressListener {

    private val log = Logger.getInstance(ErrorProneBuildListener::class.java)

    // Plain collections: Gradle hands one execution's events over one at a time, on one thread
    // (DaemonClient.monitorBuild; ProviderConnection calls it the contract).
    private val pending = HashMap<String, MutableList<ErrorProneDiagnostic>>()

    /** Every javac warning of a task, Error Prone's or not: javac's limit counts them all. */
    private val warnings = HashMap<String, Int>()

    override fun statusChanged(event: ProgressEvent) {
        // Anything thrown here would surface in the user's build.
        try {
            when (event) {
                is SingleProblemEvent -> collect(event.problem)
                is TaskFinishEvent -> {
                    val task = event.descriptor.taskPath
                    onTaskFinished(task, outcomeOf(event.result), pending.remove(task).orEmpty())
                    // javac stops handing warnings over once it reaches its limit, without a word to
                    // Gradle; exactly the default limit is the sign that it did.
                    if (warnings.remove(task) == JAVAC_MAX_WARNINGS) onJavacLimit(task)
                }
                is ProblemSummariesEvent -> {
                    val withheld = event.problemSummaries
                        .filter { it.problemId.name.endsWith(".error.prone") }
                        .sumOf { it.count }
                    if (withheld > 0) onCutOff(withheld)
                }
            }
        } catch (e: Exception) {
            log.warn("Could not process a Gradle event for Error Prone", e)
        }
    }

    private fun collect(problem: Problem) {
        val task = problem.contextualLocations.filterIsInstance<TaskPathLocation>().firstOrNull()?.buildTreePath ?: return
        if (problem.definition.id.name.startsWith("compiler.warn.")) warnings.merge(task, 1, Int::plus)
        val location = problem.originLocations.filterIsInstance<LineInFileLocation>().firstOrNull() ?: return
        val diagnostic = ErrorProneDiagnostic.fromProblem(
            code = problem.definition.id.name,
            label = problem.contextualLabel?.contextualLabel,
            details = problem.details?.details,
            path = location.path,
            line = location.line,
            column = location.column,
            length = location.length,
        ) ?: return
        pending.getOrPut(task) { ArrayList() }.add(diagnostic)
    }
}

/**
 * What raises javac's warning limit, in the DSL of the Gradle build at [root], for every project: the
 * task named is often a subproject's, and the root script is where the line goes.
 */
internal fun maxWarningsSnippet(root: File): String =
    if (File(root, "settings.gradle.kts").exists() || File(root, "build.gradle.kts").exists()) {
        """allprojects { tasks.withType<JavaCompile>().configureEach { options.compilerArgs.addAll(listOf("-Xmaxwarns", "10000")) } }"""
    } else {
        "allprojects { tasks.withType(JavaCompile).configureEach { options.compilerArgs.addAll(['-Xmaxwarns', '10000']) } }"
    }

/** javac's default -Xmaxwarns: it reports no more warnings than this per compilation. */
internal const val JAVAC_MAX_WARNINGS = 100

/** How a finished task's diagnostics merge with what it reported before. */
internal fun outcomeOf(result: TaskOperationResult): CompileOutcome = when {
    result is TaskFailureResult -> CompileOutcome.FAILED
    result is TaskSuccessResult && (result.isUpToDate || result.isFromCache) -> CompileOutcome.NONE
    result is TaskExecutionResult && result.isIncremental -> CompileOutcome.PARTIAL
    result is TaskSuccessResult -> CompileOutcome.FULL
    // Skipped: nothing ran.
    else -> CompileOutcome.NONE
}

/** Shows each kind of Error Prone notification at most once per project session. */
@Service(Service.Level.PROJECT)
internal class ErrorProneNotifier(private val project: Project) {

    private val shown = ConcurrentHashMap.newKeySet<String>()

    fun once(kind: String, content: String, vararg actions: AnAction) {
        if (shown.add(kind)) notifyErrorProne(project, content, NotificationType.WARNING, *actions)
    }
}

/** Every notification of the plugin, titled so it says whose it is. */
internal fun notifyErrorProne(project: Project, content: String, type: NotificationType, vararg actions: AnAction) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Error Prone")
        .createNotification("Error Prone", content, type)
        .apply { actions.forEach(::addAction) }
        .notify(project)
}
