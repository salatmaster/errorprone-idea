package io.github.salatmaster.errorprone

import com.intellij.build.BuildViewManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import org.jetbrains.plugins.gradle.service.task.GradleTaskManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path

/**
 * Build | Run Error Prone: recompiles every Java source set of every linked Gradle build in full, so
 * Error Prone reports on every file rather than on the ones an incremental build happened to touch.
 * The diagnostics arrive through ErrorProneGradleExtension, like any other build's.
 */
class RunErrorProneAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::runErrorProne)
    }
}

/** Marks the Gradle execution Run Error Prone starts, so the Gradle hook can tell it from any other build. */
internal val RUN_ERROR_PRONE: Key<Boolean> = Key.create("errorprone.run")

/** The task Run Error Prone runs; the init script registers it in every project. */
internal const val ERROR_PRONE_TASK = "errorProneCompile"

/** The Run Error Prone init script, without patching. */
internal val ERROR_PRONE_INIT_SCRIPT: String = errorProneInitScript()

/**
 * The init script of a Run Error Prone build, applied to that build only. It makes every JavaCompile
 * run in full: never up to date, never loaded from the build cache, never incremental, so every file
 * is compiled and reported on. The tasks compilation depends on (code generation, say) stay
 * incremental. doNotTrackState would say it in one line, but JavaCompile then fails with "Changes are
 * not tracked"; the Specs constants rather than closures keep the script usable with the
 * configuration cache. Task selection by name does not reach included builds; the README says so.
 * Nothing here is an input of JavaCompile, so the next ordinary build finds the tasks up to date and
 * keeps what this one reported; a compiler argument (javac's -Xmaxwarns, say) would recompile them all.
 *
 * With [patchChecks], Error Prone also writes the fixes of those checks into [patchDir], one patch
 * per compile task, and runs only those checks. That takes the net.ltgt.errorprone plugin: the flags
 * go into its errorproneArgs, which exist once the plugin has configured the task, hence withId. Each
 * task gets its own directory, keyed by build as well, since an included build has a :compileJava too.
 */
internal fun errorProneInitScript(patchChecks: Collection<String> = emptyList(), patchDir: Path? = null): String {
    val patching = if (patchChecks.isEmpty() || patchDir == null) "" else """
        |    plugins.withId("net.ltgt.errorprone") {
        |        tasks.withType(JavaCompile).configureEach { task ->
        |            def dir = new File('${patchDir.toString().replace('\\', '/')}', task.path.replace(':', '_') + '-' + Integer.toHexString(rootDir.absolutePath.hashCode()))
        |            options.errorprone.errorproneArgs.addAll('-XepPatchChecks:${patchChecks.joinToString(",")}', '-XepPatchLocation:' + dir.absolutePath)
        |        }
        |    }
        |""".trimMargin()
    return """
        |allprojects {
        |    tasks.withType(JavaCompile).configureEach {
        |        outputs.upToDateWhen(Specs.SATISFIES_NONE)
        |        outputs.cacheIf(Specs.SATISFIES_NONE)
        |        options.incremental = false
        |    }
        |    tasks.register("$ERROR_PRONE_TASK") {
        |        group = "verification"
        |        description = "Compiles every Java source set in full, so Error Prone reports on every file."
        |        dependsOn(tasks.withType(JavaCompile))
        |    }
        |$patching}
        |""".trimMargin()
}

/** Starts Run Error Prone for every Gradle build linked to [project]. */
fun runErrorProne(project: Project) {
    for (linked in GradleSettings.getInstance(project).linkedProjectsSettings) {
        runGradle(
            project, linked.externalProjectPath, listOf(ERROR_PRONE_TASK), "Run Error Prone",
            ERROR_PRONE_INIT_SCRIPT, RUN_ERROR_PRONE, activate = true,
        )
    }
}

/**
 * Runs [tasks] of the Gradle build at [root] with [initScript], its output in the Build tool
 * window under [name]. [mark] tells the Gradle hook which of this plugin's builds it is. The Build window
 * comes forward when the build starts only if [activate], and when it fails.
 * [onFinished] runs however it ended.
 */
internal fun runGradle(
    project: Project,
    root: String,
    tasks: List<String>,
    name: String,
    initScript: String,
    mark: Key<Boolean>,
    activate: Boolean = false,
    onFinished: () -> Unit = {},
) {
    val settings = ExternalSystemTaskExecutionSettings().apply {
        executionName = name
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        externalProjectPath = root
        taskNames = tasks
    }
    val userData = UserDataHolderBase().apply {
        putUserData(mark, true)
        // GradleTaskManager writes the script to a temporary file and passes --init-script.
        putUserData(GradleTaskManager.INIT_SCRIPT_KEY, initScript)
        putUserData(GradleTaskManager.INIT_SCRIPT_PREFIX_KEY, "errorprone")
        // The Build tool window, where compiler output belongs, rather than the Run one.
        putUserData(ExternalSystemRunConfiguration.PROGRESS_LISTENER_KEY, BuildViewManager::class.java)
    }
    ExternalSystemUtil.runTask(
        TaskExecutionSpec.create()
            .withProject(project)
            .withSystemId(GradleConstants.SYSTEM_ID)
            .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
            .withSettings(settings)
            .withUserData(userData)
            .withProgressExecutionMode(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
            .withActivateToolWindowBeforeRun(activate)
            .withCallback(object : TaskCallback {
                override fun onSuccess() = onFinished()
                override fun onFailure() = onFinished()
            })
            .build()
    )
}
