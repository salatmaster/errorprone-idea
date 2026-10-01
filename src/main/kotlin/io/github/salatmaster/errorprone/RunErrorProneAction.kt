package io.github.salatmaster.errorprone

import com.intellij.build.BuildViewManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import org.gradle.util.GradleVersion
import org.jetbrains.plugins.gradle.service.task.GradleTaskManager
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Build | Run Error Prone: recompiles every Java source set of every linked Gradle build in full, so
 * Error Prone reports on every file rather than on the ones an incremental build happened to touch.
 * The diagnostics arrive through ErrorProneGradleExtension, like any other build's.
 */
class RunErrorProneAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isVisible = project != null && GradleSettings.getInstance(project).linkedProjectsSettings.isNotEmpty()
        // A second one would recompile everything again, in a second Gradle daemon.
        e.presentation.isEnabled = e.presentation.isVisible && !ErrorProneBuilds.getInstance(project!!).running
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(::runErrorProne)
    }
}

/** How a Run Error Prone ended: [at] when, the diagnostics shown [before] and [after] it, and whether every build of it [succeeded]. */
internal data class RunResult(val at: Long, val before: Int, val after: Int, val succeeded: Boolean)

/** The builds of this plugin that have not finished yet. */
@Service(Service.Level.PROJECT)
internal class ErrorProneBuilds(private val project: Project) {

    /** Gradle builds of the Run Error Prone in progress still running; [before] and [failed] are its so far. */
    private var pending = 0
    private var before = 0
    private var failed = false

    val running: Boolean get() = synchronized(this) { pending > 0 }

    /** How the last Run Error Prone of this session ended. */
    @Volatile
    var lastRun: RunResult? = null
        private set

    /** Starts a Run Error Prone of [builds] Gradle builds, or returns false while one is running. */
    fun startRun(builds: Int): Boolean {
        // Counted outside the monitor: it takes a read lock, which may wait.
        val count = ErrorProneDiagnostics.getInstance(project).count()
        synchronized(this) {
            if (pending > 0) return false
            pending = builds
            before = count
            failed = false
        }
        changed()
        return true
    }

    /** One build of the run ended; returns how the run did once this was its last. */
    fun finishRun(succeeded: Boolean): RunResult? {
        val count = ErrorProneDiagnostics.getInstance(project).count()
        val result = synchronized(this) {
            if (!succeeded) failed = true
            if (--pending > 0) return null
            RunResult(System.currentTimeMillis(), before, count, !failed)
        }
        lastRun = result
        changed()
        return result
    }

    private fun changed() = project.messageBus.syncPublisher(ErrorProneDiagnostics.TOPIC).diagnosticsChanged()

    private val fixes = ConcurrentHashMap.newKeySet<String>()

    /** Whether a build writing [check]'s fix for [file] may start: false while one already runs. */
    fun startFix(file: VirtualFile, check: String): Boolean = fixes.add("${file.path}|$check")

    fun finishFix(file: VirtualFile, check: String) {
        fixes.remove("${file.path}|$check")
    }

    fun isFixing(file: VirtualFile, check: String): Boolean = "${file.path}|$check" in fixes

    companion object {
        fun getInstance(project: Project): ErrorProneBuilds = project.service()
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
 * configuration cache. Task selection by name stays in the root build, but the script reaches included
 * builds too: one the root build depends on compiles in full with it, one it does not is left out.
 * Nothing here is an input of JavaCompile, so the next ordinary build finds the tasks up to date and
 * keeps what this one reported; a compiler argument (javac's -Xmaxwarns, say) would recompile them all.
 *
 * With [patchChecks], Error Prone also writes the fixes of those checks into [patchDir], one patch
 * per compile task, and runs only those checks. That takes the net.ltgt.errorprone plugin: the flags
 * go into its errorproneArgs, which exist once the plugin has configured the task, hence withId. Each
 * task gets its own directory, keyed by build as well, since an included build has a :compileJava too.
 * [importOrder] is how a fix that adds an import lays out the block, which it reprints whole.
 *
 * With [targets], task paths in the build tree, only those tasks run in full (and write fixes): the
 * compile tasks they depend on stay as incremental as ever, rather than every module upstream of a fix
 * recompiling in full. An included build's tasks have their tree path only from Gradle 9.7, as the
 * diagnostics that name them do; before it, every task still counts.
 */
internal fun errorProneInitScript(
    patchChecks: Collection<String> = emptyList(),
    patchDir: Path? = null,
    importOrder: String = "static-first",
    targets: Collection<String>? = null,
): String {
    val selection = if (targets == null) "" else """
        |def targets = GradleVersion.current() >= GradleVersion.version('${TREE_PATHS.version}') ? [${targets.joinToString { "'$it'" }}] as Set : null
        |def targeted = { task -> targets == null || ((task.project.gradle.buildPath == ':' ? '' : task.project.gradle.buildPath) + task.path) in targets }
        |""".trimMargin()
    val guard = if (targets == null) "" else "if (!targeted(task)) return; "
    val patching = if (patchChecks.isEmpty() || patchDir == null) "" else """
        |    plugins.withId("net.ltgt.errorprone") {
        |        tasks.withType(JavaCompile).configureEach { task ->
        |            ${guard}def dir = new File('${patchDir.toString().replace('\\', '/')}', task.path.replace(':', '_') + '-' + Integer.toHexString(rootDir.absolutePath.hashCode()))
        |            options.errorprone.errorproneArgs.addAll('-XepPatchChecks:${patchChecks.joinToString(",")}', '-XepPatchLocation:' + dir.absolutePath, '-XepPatchImportOrder:$importOrder')
        |        }
        |    }
        |""".trimMargin()
    return """
        |${selection}allprojects {
        |    tasks.withType(JavaCompile).configureEach { task ->
        |        ${guard}outputs.upToDateWhen(Specs.SATISFIES_NONE)
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

/** The first Gradle to give an included build's tasks their path in the build tree, problems included. */
internal val TREE_PATHS: GradleVersion = GradleVersion.version("9.7")

/**
 * Starts Run Error Prone for every Gradle build linked to [project], unless one is running. Once all of
 * them succeed, the Problems tool window shows the Error Prone tab in place of the Build one; a failure
 * leaves the Build window, which says what went wrong.
 */
fun runErrorProne(project: Project) {
    val linked = GradleSettings.getInstance(project).linkedProjectsSettings
    val builds = ErrorProneBuilds.getInstance(project)
    if (linked.isEmpty() || !builds.startRun(linked.size)) return
    for (build in linked) {
        // A build that does not start has told the run so (runGradle); the others go on.
        try {
            runGradle(
                project, build.externalProjectPath, listOf(ERROR_PRONE_TASK), "Run Error Prone",
                ERROR_PRONE_INIT_SCRIPT, RUN_ERROR_PRONE, true, activate = true,
            ) { succeeded ->
                if (builds.finishRun(succeeded)?.succeeded == true) {
                    ApplicationManager.getApplication().invokeLater({ showErrorProneTab(project) }, project.disposed)
                }
            }
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: RuntimeException) {
            logger<ErrorProneBuilds>().warn("Could not start Run Error Prone in ${build.externalProjectPath}", e)
        }
    }
}

/** Brings the Problems tool window forward on its Error Prone tab. */
internal fun showErrorProneTab(project: Project) {
    val window = ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROBLEMS_VIEW) ?: return
    window.show {
        window.contentManager.contents.firstOrNull { it.component is ErrorProneTab }?.let(window.contentManager::setSelectedContent)
    }
}

/**
 * Runs [tasks] of the Gradle build at [root] with [initScript], its output in the Build tool window
 * under [name]. [mark], set to [value], tells the Gradle hook which of this plugin's builds it is. The
 * Build window comes forward when the build starts only if [activate], and when it fails. [onFinished]
 * runs however it ended, told whether it succeeded.
 */
internal fun <T : Any> runGradle(
    project: Project,
    root: String,
    tasks: List<String>,
    name: String,
    initScript: String,
    mark: Key<T>,
    value: T,
    activate: Boolean = false,
    onFinished: (succeeded: Boolean) -> Unit = {},
) {
    val settings = ExternalSystemTaskExecutionSettings().apply {
        executionName = name
        externalSystemIdString = GradleConstants.SYSTEM_ID.id
        externalProjectPath = root
        taskNames = tasks
    }
    val userData = UserDataHolderBase().apply {
        putUserData(mark, value)
        // GradleTaskManager writes the script to a temporary file and passes --init-script.
        putUserData(GradleTaskManager.INIT_SCRIPT_KEY, initScript)
        putUserData(GradleTaskManager.INIT_SCRIPT_PREFIX_KEY, "errorprone")
        // The Build tool window, where compiler output belongs, rather than the Run one.
        putUserData(ExternalSystemRunConfiguration.PROGRESS_LISTENER_KEY, BuildViewManager::class.java)
    }
    val spec = TaskExecutionSpec.create()
        .withProject(project)
        .withSystemId(GradleConstants.SYSTEM_ID)
        .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
        .withSettings(settings)
        .withUserData(userData)
        .withProgressExecutionMode(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
        .withActivateToolWindowBeforeRun(activate)
        .withCallback(object : TaskCallback {
            override fun onSuccess() = onFinished(true)
            override fun onFailure() = onFinished(false)
        })
        .build()
    // Whoever waits for the end is told of one that never began, too.
    try {
        ExternalSystemUtil.runTask(spec)
    } catch (e: RuntimeException) {
        onFinished(false)
        throw e
    }
}
