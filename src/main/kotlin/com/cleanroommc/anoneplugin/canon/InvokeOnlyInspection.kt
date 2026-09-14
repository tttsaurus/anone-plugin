package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner
import java.util.ArrayDeque

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

                val invokeOnlySuper = method.findInvokeOnlySuper() ?: return

                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    AnoNeBundle.message(
                        "inspection.anone.invokeOnly.problem",
                        invokeOnlySuper.containingClass?.name + "#" + invokeOnlySuper.name
                    )
                )
            }
        }
    }

    private fun PsiMethod.findInvokeOnlySuper(): PsiMethod? {
        val visited = HashSet<PsiMethod>()
        val remaining = ArrayDeque<PsiMethod>()
        remaining.addAll(findSuperMethods())

        while (remaining.isNotEmpty()) {
            val superMethod = remaining.removeFirst()
            if (!visited.add(superMethod)) {
                continue
            }

            if (superMethod.hasDirectAnnotation(INVOKE_ONLY)) {
                return superMethod
            }

            remaining.addAll(superMethod.findSuperMethods())
        }

        return null
    }

    private fun PsiModifierListOwner.hasDirectAnnotation(qualifiedName: String): Boolean {
        return modifierList?.findAnnotation(qualifiedName) != null
    }
}
