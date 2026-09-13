package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil

private const val OVERRIDE_ONLY = "com.cleanroommc.anone.canon.OverrideOnly"

class SusOverrideOnlyBypassInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {

            override fun visitMethodCallExpression(expression: PsiMethodCallExpression) {
                val target = expression.resolveMethod() ?: return
                if (target.hasDirectAnnotation(OVERRIDE_ONLY)) {
                    return
                }

                val contractOrigin = findOverrideOnlySuperMethod(target) ?: return

                if (isOverrideDelegation(expression, target)) {
                    return
                }

                holder.registerProblem(
                    expression.methodExpression.referenceNameElement ?: expression.methodExpression,
                    AnoNeBundle.message(
                        "inspection.anone.overrideOnlyBypass.problem",
                        target.name,
                        contractOrigin.containingClass?.name + "#" + contractOrigin.name
                    )
                )
            }
        }
    }

    private fun findOverrideOnlySuperMethod(method: PsiMethod): PsiMethod? {
        val visited = HashSet<PsiMethod>()

        fun visit(current: PsiMethod): PsiMethod? {
            if (!visited.add(current)) {
                return null
            }

            for (superMethod in current.findSuperMethods()) {
                if (superMethod.hasDirectAnnotation(OVERRIDE_ONLY)) {
                    return superMethod
                }

                val found = visit(superMethod)
                if (found != null) {
                    return found
                }
            }

            return null
        }

        return visit(method)
    }

    private fun isOverrideDelegation(
        expression: PsiMethodCallExpression,
        target: PsiMethod
    ): Boolean {
        val qualifier = expression.methodExpression.qualifierExpression
        if (qualifier !is PsiSuperExpression) {
            return false
        }

        val enclosingMethod = PsiTreeUtil.getParentOfType(
            expression,
            PsiMethod::class.java
        ) ?: return false

        return overrides(enclosingMethod, target)
    }

    private fun overrides(
        method: PsiMethod,
        target: PsiMethod
    ): Boolean {
        val visited = HashSet<PsiMethod>()

        fun visit(current: PsiMethod): Boolean {
            if (!visited.add(current)) {
                return false
            }

            for (superMethod in current.findSuperMethods()) {
                if (superMethod == target || visit(superMethod)) {
                    return true
                }
            }

            return false
        }

        return visit(method)
    }

    private fun PsiModifierListOwner.hasDirectAnnotation(qualifiedName: String): Boolean {
        return modifierList?.findAnnotation(qualifiedName) != null
    }
}
