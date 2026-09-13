package com.cleanroommc.anoneplugin.lifecycle

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*

class MustNotCloseInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        val analyzer = MustNotCloseAnalyzer()

        return object : JavaElementVisitor() {

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val method = expression.resolveMethod() ?: return
                if (method.name != "close" || method.parameterList.parametersCount != 0) {
                    return
                }

                val qualifier = expression.methodExpression.qualifierExpression ?: return
                if (!analyzer.isAutoCloseable(qualifier.type, qualifier)) {
                    return
                }

                val ownership = analyzer.ownershipOf(qualifier)
                if (!ownership.mayNotBeClosed) {
                    return
                }

                holder.registerProblem(
                    expression.methodExpression.referenceNameElement ?: expression.methodExpression,
                    if (ownership == MustNotCloseAnalyzer.Ownership.MUST_NOT_CLOSE) {
                        AnoNeBundle.message("inspection.anone.mustNotClose.problem")
                    } else {
                        AnoNeBundle.message("inspection.anone.mustNotClose.possible.problem")
                    }
                )
            }

            override fun visitResourceVariable(variable: PsiResourceVariable) {
                val initializer = variable.initializer ?: return
                if (!analyzer.isAutoCloseable(variable.type, variable)) {
                    return
                }

                val ownership = analyzer.ownershipOf(initializer)
                if (!ownership.mayNotBeClosed) {
                    return
                }

                holder.registerProblem(
                    variable.nameIdentifier ?: variable,
                    if (ownership == MustNotCloseAnalyzer.Ownership.MUST_NOT_CLOSE) {
                        AnoNeBundle.message("inspection.anone.mustNotClose.resource.problem")
                    } else {
                        AnoNeBundle.message("inspection.anone.mustNotClose.resource.possible.problem")
                    }
                )
            }

            override fun visitResourceExpression(resource: PsiResourceExpression) {
                val expression = resource.expression
                if (!analyzer.isAutoCloseable(expression.type, expression)) {
                    return
                }

                val ownership = analyzer.ownershipOf(expression)
                if (!ownership.mayNotBeClosed) {
                    return
                }

                holder.registerProblem(
                    resource,
                    if (ownership == MustNotCloseAnalyzer.Ownership.MUST_NOT_CLOSE) {
                        AnoNeBundle.message("inspection.anone.mustNotClose.resource.problem")
                    } else {
                        AnoNeBundle.message("inspection.anone.mustNotClose.resource.possible.problem")
                    }
                )
            }
        }
    }
}
