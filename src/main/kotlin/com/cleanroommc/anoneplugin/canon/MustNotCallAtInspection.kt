package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

class MustNotCallAtInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        val analyzer = CallAtAnalyzer(holder.project)

        return object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                for (result in analyzer.analyze(method, CallAtAnalyzer.ContractType.MUST_NOT_CALL_AT)) {
                    if (!result.matches) {
                        continue
                    }

                    for (call in result.matchingCalls) {
                        holder.registerProblem(
                            call.methodExpression.referenceNameElement ?: call.methodExpression,
                            AnoNeBundle.message(
                                "inspection.anone.mustNotCallAt.problem",
                                result.targetName,
                                result.position.name
                            )
                        )
                    }
                }
            }
        }
    }
}
