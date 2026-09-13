package com.cleanroommc.anoneplugin.value

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiLambdaExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiStatement
import com.intellij.psi.util.PsiTreeUtil

private const val CAN_IGNORE_RETURN_VALUE =
    "com.cleanroommc.anone.value.CanIgnoreReturnValue"

private val CHECK_RETURN_VALUE_ANNOTATIONS = setOf(
    "com.google.errorprone.annotations.CheckReturnValue",
    "edu.umd.cs.findbugs.annotations.CheckReturnValue",
    "org.jetbrains.annotations.CheckReturnValue",
    "org.springframework.lang.CheckReturnValue",
    "javax.annotation.CheckReturnValue"
)

private const val UNUSED_RETURN_VALUE_ID =
    "UnusedReturnValue"

private val RESULT_IGNORED_INSPECTION_IDS = setOf(
    "ResultOfMethodCallIgnored",
    "IgnoreResultOfCall"
)

class CanIgnoreReturnValueInspectionSuppressor : InspectionSuppressor {

    override fun isSuppressedFor(
        element: PsiElement,
        toolId: String
    ): Boolean {
        val method = when (toolId) {
            UNUSED_RETURN_VALUE_ID ->
                findContainingMethod(element)

            in RESULT_IGNORED_INSPECTION_IDS ->
                resolveCalledMethod(element)

            else ->
                null
        } ?: return false

        return canIgnoreReturnValue(method)
    }

    override fun getSuppressActions(
        element: PsiElement?,
        toolId: String
    ): Array<out SuppressQuickFix?> {
        return emptyArray()
    }

    private fun canIgnoreReturnValue(method: PsiMethod): Boolean {
        if (method.hasDirectAnnotation(CAN_IGNORE_RETURN_VALUE)) {
            return true
        }
        if (method.hasDirectCheckReturnValueAnnotation()) {
            return false
        }

        val containingClass = method.containingClass ?: return false
        if (containingClass.hasDirectAnnotation(CAN_IGNORE_RETURN_VALUE)) {
            return true
        }
        if (containingClass.hasDirectCheckReturnValueAnnotation()) {
            return false
        }

        return false
    }

    private fun resolveCalledMethod(element: PsiElement): PsiMethod? {
        var current: PsiElement? = element
        while (current != null) {
            when (current) {
                is PsiMethodCallExpression ->
                    return current.resolveMethod()

                is PsiMethodReferenceExpression ->
                    return current.resolve() as? PsiMethod

                is PsiStatement,
                is PsiLambdaExpression,
                is PsiMethod ->
                    return null
            }

            current = current.parent
        }

        return null
    }

    private fun findContainingMethod(element: PsiElement): PsiMethod? {
        if (element is PsiMethod) {
            return element
        }

        return PsiTreeUtil.getParentOfType(
            element,
            PsiMethod::class.java,
            false
        )
    }

    private fun PsiModifierListOwner.hasDirectAnnotation(qualifiedName: String): Boolean {
        return modifierList?.findAnnotation(qualifiedName) != null
    }

    private fun PsiModifierListOwner.hasDirectCheckReturnValueAnnotation(): Boolean {
        return modifierList?.annotations?.any { annotation ->
            val qualifiedName = annotation.qualifiedName ?: return@any false

            qualifiedName in CHECK_RETURN_VALUE_ANNOTATIONS || qualifiedName.endsWith(".CheckReturnValue")
        } == true
    }
}