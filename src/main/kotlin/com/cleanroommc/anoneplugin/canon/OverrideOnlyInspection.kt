package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiSuperExpression

private const val OVERRIDE_ONLY = "com.cleanroommc.anone.canon.OverrideOnly"

class OverrideOnlyInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val target = expression.resolveMethod() ?: return
                if (!target.hasDirectAnnotation(OVERRIDE_ONLY)) {
                    return
                }

                val qualifier = expression.methodExpression.qualifierExpression
                if (qualifier is PsiSuperExpression) {
                    return
                }

                holder.registerProblem(
                    expression.methodExpression.referenceNameElement ?: expression.methodExpression,
                    AnoNeBundle.message(
                        "inspection.anone.overrideOnly.problem",
                        target.containingClass?.name + "#" + target.name
                    )
                )
            }
        }
    }

    private fun PsiModifierListOwner.hasDirectAnnotation(qualifiedName: String): Boolean {
        return modifierList?.findAnnotation(qualifiedName) != null
    }
}
