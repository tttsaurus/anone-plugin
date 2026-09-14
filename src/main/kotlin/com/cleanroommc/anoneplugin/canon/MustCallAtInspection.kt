package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

class MustCallAtInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        val analyzer = CallAtAnalyzer(holder.project)

        return object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                for (result in analyzer.analyze(method, CallAtAnalyzer.ContractType.MUST_CALL_AT)) {
                    if (result.matches) {
                        continue
                    }

                    holder.registerProblem(
                        method.nameIdentifier ?: method,
                        AnoNeBundle.message(
                            "inspection.anone.mustCallAt.problem",
                            result.targetName,
                            result.position.name
                        )
                    )
                }
            }
        }
    }
}
