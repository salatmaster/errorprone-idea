package io.github.salatmaster.errorprone

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected

/** The plugin's settings, the same for every project; see [ErrorProneConfigurable]. */
@Service(Service.Level.APP)
@State(name = "ErrorProneSettings", storages = [Storage("errorprone.xml")])
class ErrorProneSettings : SimplePersistentStateComponent<ErrorProneSettings.Options>(Options()) {

    class Options : BaseState() {
        var compileOnEdit by property(true)
        var compileOnAnyEdit by property(false)
    }

    /** Whether an edit near an Error Prone diagnostic recompiles its file in the background; see [CompileOnEdit]. */
    var compileOnEdit: Boolean
        get() = state.compileOnEdit
        set(value) {
            state.compileOnEdit = value
        }

    /** Whether any edit of Java code recompiles it, not only one near a diagnostic; see [CompileOnEdit]. */
    var compileOnAnyEdit: Boolean
        get() = state.compileOnAnyEdit
        set(value) {
            state.compileOnAnyEdit = value
        }

    companion object {
        fun getInstance(): ErrorProneSettings = service()
    }
}

/** Settings | Tools | Error Prone. */
class ErrorProneConfigurable : BoundConfigurable("Error Prone") {
    override fun createPanel(): DialogPanel = panel {
        val settings = ErrorProneSettings.getInstance()
        lateinit var compileOnEdit: Cell<JBCheckBox>
        row {
            compileOnEdit = checkBox("Recompile after editing code Error Prone reported on")
                .bindSelected(settings::compileOnEdit)
                .comment(
                    "Two seconds after you stop typing in a line, method or field that has an Error Prone " +
                        "diagnostic, and once the file has no errors, it is saved and Gradle compiles its source " +
                        "set in the background, with no build output: a warning you fixed goes away, and the " +
                        "others stay current.",
                )
        }
        indent {
            row {
                checkBox("After any edit of Java code, too")
                    .bindSelected(settings::compileOnAnyEdit)
                    .enabledIf(compileOnEdit.selected)
                    .comment("New code gets Error Prone's findings without a build, at the cost of a compile after every pause in typing.")
            }
        }
    }
}
