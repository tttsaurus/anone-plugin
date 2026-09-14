package com.cleanroommc.anoneplugin.lifecycle

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.psi.util.TypeConversionUtil

private const val MUST_NOT_CLOSE = "com.cleanroommc.anone.lifecycle.MustNotClose"

class InvalidMustNotCloseTargetInspection : LocalInspectionTool() {

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                checkTarget(method, method.returnType)
            }

            override fun visitField(field: PsiField) {
                checkTarget(field, field.type)
            }

            override fun visitParameter(parameter: PsiParameter) {
                checkTarget(parameter, parameter.type)
            }

            private fun checkTarget(
                target: PsiModifierListOwner,
                type: PsiType?
            ) {
                val annotation = target.modifierList?.findAnnotation(MUST_NOT_CLOSE) ?: return
                if (!isDefinitelyInvalidType(type, target)) {
                    return
                }

                holder.registerProblem(
                    annotation.nameReferenceElement ?: annotation,
                    AnoNeBundle.message("inspection.anone.invalidMustNotCloseTarget.problem")
                )
            }
        }
    }

    private fun isDefinitelyInvalidType(
        type: PsiType?,
        context: PsiElement
    ): Boolean {
        if (type == null) {
            return false
        }
        if (type is PsiPrimitiveType || type is PsiArrayType) {
            return true
        }
        if (type is PsiClassType && type.resolve() == null) {
            return false
        }

        val autoCloseable = JavaPsiFacade
            .getInstance(context.project)
            .findClass("java.lang.AutoCloseable", context.resolveScope) ?: return false

        val autoCloseableType = JavaPsiFacade
            .getElementFactory(context.project)
            .createType(autoCloseable)

        return !TypeConversionUtil.isAssignable(autoCloseableType, type)
    }
}
