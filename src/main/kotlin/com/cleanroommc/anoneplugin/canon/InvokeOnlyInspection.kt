package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner

private const val INVOKE_ONLY = "com.cleanroommc.anone.canon.InvokeOnly"

class InvokeOnlyInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                if (method.isConstructor) {
                    return
                }

                val invokeOnlySuper = method.findSuperMethods().firstOrNull {
                    it.hasDirectAnnotation(INVOKE_ONLY)
                } ?: return

                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    AnoNeBundle.message("inspection.anone.invokeOnly.problem", invokeOnlySuper.name)
                )
            }
        }
    }

    private fun PsiModifierListOwner.hasDirectAnnotation(qualifiedName: String): Boolean {
        return modifierList?.findAnnotation(qualifiedName) != null
    }
}
