package com.cleanroommc.anoneplugin.reveal

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierListOwner

private val ACCESS_ANNOTATIONS = setOf(
    "com.cleanroommc.anone.reveal.GeneratedAccess",
    "com.cleanroommc.anone.reveal.ReflectiveAccess",
)

private val INVOCATION_ANNOTATIONS = setOf(
    "com.cleanroommc.anone.reveal.GeneratedInvocation",
    "com.cleanroommc.anone.reveal.ReflectiveInvocation",
)

class RevealImplicitUsageProvider : ImplicitUsageProvider {

    override fun isImplicitUsage(element: PsiElement): Boolean {
        return when (element) {
            is PsiField -> element.hasAnyAnnotation(ACCESS_ANNOTATIONS)
            is PsiMethod -> element.hasAnyAnnotation(INVOCATION_ANNOTATIONS)
            else -> false
        }
    }

    override fun isImplicitRead(element: PsiElement): Boolean {
        return element is PsiField && element.hasAnyAnnotation(ACCESS_ANNOTATIONS)
    }

    override fun isImplicitWrite(element: PsiElement): Boolean {
        return element is PsiField && element.hasAnyAnnotation(ACCESS_ANNOTATIONS)
    }

    private fun PsiModifierListOwner.hasAnyAnnotation(annotations: Set<String>): Boolean {
        val modifierList = modifierList ?: return false
        return annotations.any { annotation ->
            modifierList.findAnnotation(annotation) != null
        }
    }
}
