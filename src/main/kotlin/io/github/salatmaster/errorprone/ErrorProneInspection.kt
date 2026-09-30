package io.github.salatmaster.errorprone

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.codeInspection.ex.ExternalAnnotatorBatchInspection
import com.intellij.psi.PsiElement

/**
 * The annotator's paired inspection. It does no analysis of its own; it exists because the platform
 * offers two things only through an inspection: switching the highlighting off in the inspection
 * profile, and listing the diagnostics in Code | Inspect Code. The short name comes from plugin.xml.
 */
class ErrorProneInspection : LocalInspectionTool(), ExternalAnnotatorBatchInspection {

    // Error Prone is silenced with @SuppressWarnings("CheckName"), which it reads itself. An IDE
    // suppression comment would hide the highlight while the build went on reporting the finding.
    override fun getBatchSuppressActions(element: PsiElement?): Array<out SuppressQuickFix> = SuppressQuickFix.EMPTY_ARRAY

    companion object {
        const val SHORT_NAME = "ErrorProne"
    }
}
