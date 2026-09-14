package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.AnoNeBundle
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.psi.*
import com.intellij.psi.util.PsiUtil

private const val MUST_CALL_AT = "com.cleanroommc.anone.canon.MustCallAt"
private const val MUST_NOT_CALL_AT = "com.cleanroommc.anone.canon.MustNotCallAt"
private const val SUPER = "com.cleanroommc.anone.canon.Super"

class InvalidCallAtContractInspection : LocalInspectionTool() {

    private data class Contract(
        val position: String,
        val scope: String,
        val scopeRoots: List<SmartPsiElementPointer<PsiClass>>
    )

    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ): PsiElementVisitor {
        return object : JavaElementVisitor() {

            override fun visitMethod(method: PsiMethod) {
                val mustCallAt = method.modifierList.findAnnotation(MUST_CALL_AT)
                val mustNotCallAt = method.modifierList.findAnnotation(MUST_NOT_CALL_AT)

                val mustCallAtValid = mustCallAt?.let {
                    checkContract(method, it)
                } ?: false
                val mustNotCallAtValid = mustNotCallAt?.let {
                    checkContract(method, it)
                } ?: false

                if (!mustCallAtValid || !mustNotCallAtValid) {
                    return
                }

                val required = parseContract(mustCallAt) ?: return
                val forbidden = parseContract(mustNotCallAt) ?: return
                if (!contractsEquivalent(required, forbidden)) {
                    return
                }

                holder.registerProblem(
                    method.nameIdentifier ?: method,
                    AnoNeBundle.message("inspection.anone.invalidCallAtContract.conflict.problem")
                )
            }

            private fun checkContract(
                method: PsiMethod,
                annotation: PsiAnnotation
            ): Boolean {
                val values = annotationValues(annotation.findAttributeValue("scopeRoot"))
                if (values.isEmpty()) {
                    holder.registerProblem(
                        annotation.findDeclaredAttributeValue("scopeRoot") ?: annotation,
                        AnoNeBundle.message("inspection.anone.invalidCallAtContract.emptyScope.problem")
                    )
                    return false
                }

                val resolvedRoots = values.mapNotNull { value ->
                    resolveScopeRoot(value)?.let { value to it }
                }
                val manager = PsiManager.getInstance(method.project)
                var valid = true

                for (index in resolvedRoots.indices) {
                    val (value, scopeRoot) = resolvedRoots[index]
                    val duplicate = resolvedRoots
                        .subList(0, index)
                        .any { (_, previousRoot) ->
                            manager.areElementsEquivalent(previousRoot, scopeRoot)
                        }
                    if (!duplicate) {
                        continue
                    }

                    holder.registerProblem(
                        value,
                        AnoNeBundle.message(
                            "inspection.anone.invalidCallAtContract.duplicateScope.problem",
                            scopeRoot.qualifiedName ?: scopeRoot.name ?: value.text
                        )
                    )
                    valid = false
                }

                val superClass = JavaPsiFacade
                    .getInstance(method.project)
                    .findClass(SUPER, method.resolveScope) ?: return valid
                val hasSuperScope = resolvedRoots.any { (_, scopeRoot) ->
                    manager.areElementsEquivalent(scopeRoot, superClass)
                }

                fun canBeOverridden(method: PsiMethod): Boolean {
                    if (method.isConstructor ||
                        method.hasModifierProperty(PsiModifier.STATIC) ||
                        method.hasModifierProperty(PsiModifier.PRIVATE) ||
                        method.hasModifierProperty(PsiModifier.FINAL)
                    ) {
                        return false
                    }

                    val containingClass = method.containingClass ?: return false
                    return !containingClass.hasModifierProperty(PsiModifier.FINAL) &&
                            !containingClass.isEnum &&
                            !containingClass.isAnnotationType &&
                            containingClass !is PsiAnonymousClass
                }

                if (hasSuperScope && !canBeOverridden(method)) {
                    holder.registerProblem(
                        annotation.nameReferenceElement ?: annotation,
                        AnoNeBundle.message("inspection.anone.invalidCallAtContract.superScope.problem")
                    )
                    valid = false
                }

                return valid
            }
        }
    }

    private fun parseContract(annotation: PsiAnnotation): Contract? {

        fun enumConstantName(value: PsiAnnotationMemberValue?): String? {
            val reference = value as? PsiReferenceExpression ?: return null
            return (reference.resolve() as? PsiEnumConstant)?.name
        }

        val position = enumConstantName(annotation.findAttributeValue("position")) ?: return null
        val scope = enumConstantName(annotation.findAttributeValue("scope")) ?: return null
        val values = annotationValues(annotation.findAttributeValue("scopeRoot"))
        if (values.isEmpty()) {
            return null
        }

        fun hasDuplicates(scopeRoots: List<PsiClass>): Boolean {
            if (scopeRoots.size < 2) {
                return false
            }

            val manager = PsiManager.getInstance(scopeRoots.first().project)
            for (index in scopeRoots.indices) {
                if (scopeRoots
                        .subList(0, index)
                        .any { manager.areElementsEquivalent(it, scopeRoots[index]) }
                ) {
                    return true
                }
            }

            return false
        }

        val resolvedRoots = values.mapNotNull { resolveScopeRoot(it) }
        if (resolvedRoots.size != values.size || hasDuplicates(resolvedRoots)) {
            return null
        }

        val pointerManager = SmartPointerManager.getInstance(annotation.project)
        val scopeRoots = resolvedRoots.map {
            pointerManager.createSmartPsiElementPointer(it)
        }

        return Contract(
            position,
            scope,
            scopeRoots
        )
    }

    private fun contractsEquivalent(first: Contract, second: Contract): Boolean {
        if (first.position != second.position ||
            first.scope != second.scope ||
            first.scopeRoots.size != second.scopeRoots.size
        ) {
            return false
        }

        val firstScopeRoot = first.scopeRoots.first().element ?: return false
        val manager = PsiManager.getInstance(firstScopeRoot.project)
        val unmatched = second.scopeRoots.toMutableList()
        for (scopeRootPointer in first.scopeRoots) {
            val scopeRoot = scopeRootPointer.element ?: return false
            val index = unmatched.indexOfFirst {
                val otherScopeRoot = it.element ?: return@indexOfFirst false
                manager.areElementsEquivalent(scopeRoot, otherScopeRoot)
            }
            if (index < 0) {
                return false
            }

            unmatched.removeAt(index)
        }

        return true
    }

    private fun annotationValues(value: PsiAnnotationMemberValue?): List<PsiAnnotationMemberValue> {
        return when (value) {
            is PsiArrayInitializerMemberValue ->
                value.initializers.toList()

            null ->
                emptyList()

            else ->
                listOf(value)
        }
    }

    private fun resolveScopeRoot(value: PsiAnnotationMemberValue): PsiClass? {
        val classObject = value as? PsiClassObjectAccessExpression ?: return null
        return PsiUtil.resolveClassInType(classObject.operand.type)
    }
}
