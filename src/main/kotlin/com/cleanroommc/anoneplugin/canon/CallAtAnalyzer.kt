package com.cleanroommc.anoneplugin.canon

import com.cleanroommc.anoneplugin.canon.CallAtAnalyzer.ContractType
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootModificationTracker
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.AnnotatedElementsSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil
import java.util.ArrayDeque

private fun createAnnotatedMethods(project: Project):
        CachedValueProvider.Result<Map<ContractType, List<SmartPsiElementPointer<PsiMethod>>>> {

    return CachedValueProvider.Result.create(
        ContractType.entries.associateWith {
            findAnnotatedMethods(project, it)
        },
        PsiModificationTracker.MODIFICATION_COUNT,
        ProjectRootModificationTracker.getInstance(project)
    )
}

private fun findAnnotatedMethods(project: Project, contractType: ContractType):
        List<SmartPsiElementPointer<PsiMethod>> {

    val scope = GlobalSearchScope.allScope(project)
    val annotationClass = JavaPsiFacade
        .getInstance(project)
        .findClass(contractType.annotationName, scope) ?: return emptyList()

    val pointerManager = SmartPointerManager.getInstance(project)

    return AnnotatedElementsSearch
        .searchPsiMethods(annotationClass, scope)
        .findAll()
        .map { pointerManager.createSmartPsiElementPointer(it) }
        .toList()
}

private const val SUPER = "com.cleanroommc.anone.canon.Super"

internal class CallAtAnalyzer(private val project: Project) {

    enum class ContractType(val annotationName: String) {
        MUST_CALL_AT("com.cleanroommc.anone.canon.MustCallAt"),
        MUST_NOT_CALL_AT("com.cleanroommc.anone.canon.MustNotCallAt")
    }

    enum class Position {
        HEAD,
        IN_FINALLY,
        OUTERMOST_FINALLY,
        ANYWHERE
    }

    data class Result(
        val targetMethod: PsiMethod,
        val position: Position,
        val matchingCalls: List<PsiMethodCallExpression>
    ) {

        val matches: Boolean
            get() = matchingCalls.isNotEmpty()

        val targetName: String
            get() = targetMethod.containingClass?.qualifiedName?.let {
                "$it#${targetMethod.name}"
            } ?: targetMethod.name
    }

    private enum class Scope {
        DIRECT_OVERRIDERS,
        TRANSITIVE_OVERRIDERS
    }

    private data class Contract(
        val targetMethod: SmartPsiElementPointer<PsiMethod>,
        val position: Position,
        val scope: Scope,
        val scopeRoots: List<SmartPsiElementPointer<PsiClass>>
    )

    private data class ResolvedCall(
        val expression: PsiMethodCallExpression,
        val method: PsiMethod
    )

    fun analyze(method: PsiMethod, contractType: ContractType): List<Result> {
        if (method.body == null || DumbService.isDumb(project)) {
            return emptyList()
        }

        val superMethods = HashMap<Scope, List<PsiMethod>>()
        val contracts = annotatedMethods(contractType)
            .mapNotNull { targetMethodPointer ->
                val targetMethod = targetMethodPointer.element
                    ?: return@mapNotNull null
                val annotation = targetMethod.modifierList.findAnnotation(contractType.annotationName)
                    ?: return@mapNotNull null

                parseContract(targetMethodPointer, annotation)
            }
            .filter { contract -> matchesScopeRoots(method, contract, superMethods) }

        if (contracts.isEmpty()) {
            return emptyList()
        }

        val manager = PsiManager.getInstance(project)
        val calls = findMethodCalls(method)
            .mapNotNull { expression ->
                expression.resolveMethod()?.let {
                    ResolvedCall(expression, it)
                }
            }

        return contracts.mapNotNull { contract ->
            val targetMethod = contract.targetMethod.element ?: return@mapNotNull null

            Result(
                targetMethod,
                contract.position,
                calls
                    .filter { call ->
                        manager.areElementsEquivalent(
                            call.method,
                            targetMethod
                        ) && matchesPosition(
                            call.expression,
                            method,
                            contract.position
                        )
                    }
                    .map { it.expression }
            )
        }
    }

    private fun annotatedMethods(contractType: ContractType): List<SmartPsiElementPointer<PsiMethod>> {
        val currentProject = project
        val methods = CachedValuesManager
            .getManager(currentProject)
            .getCachedValue(currentProject) {
                createAnnotatedMethods(currentProject)
            }

        return methods[contractType].orEmpty()
    }

    private fun parseContract(
        targetMethod: SmartPsiElementPointer<PsiMethod>,
        annotation: PsiAnnotation
    ): Contract? {

        fun enumConstantName(value: PsiAnnotationMemberValue?): String? {
            val reference = value as? PsiReferenceExpression ?: return null
            return (reference.resolve() as? PsiEnumConstant)?.name ?: reference.referenceName
        }

        val position = when (enumConstantName(annotation.findAttributeValue("position"))) {
            "HEAD" -> Position.HEAD
            "IN_FINALLY" -> Position.IN_FINALLY
            "OUTERMOST_FINALLY" -> Position.OUTERMOST_FINALLY
            "ANYWHERE" -> Position.ANYWHERE
            else -> return null
        }

        val scope = when (enumConstantName(annotation.findAttributeValue("scope"))) {
            "DIRECT_OVERRIDERS" -> Scope.DIRECT_OVERRIDERS
            "TRANSITIVE_OVERRIDERS" -> Scope.TRANSITIVE_OVERRIDERS
            else -> return null
        }

        fun annotationValues(value: PsiAnnotationMemberValue?): List<PsiAnnotationMemberValue> {
            return when (value) {
                is PsiArrayInitializerMemberValue ->
                    value.initializers.toList()

                null ->
                    emptyList()

                else ->
                    listOf(value)
            }
        }

        val pointerManager = SmartPointerManager.getInstance(project)
        val scopeRoots = annotationValues(annotation.findAttributeValue("scopeRoot"))
            .mapNotNull { value ->
                val classObject = value as? PsiClassObjectAccessExpression ?: return@mapNotNull null

                PsiUtil.resolveClassInType(classObject.operand.type)?.let {
                    pointerManager.createSmartPsiElementPointer(it)
                }
            }

        if (scopeRoots.isEmpty()) {
            return null
        }

        return Contract(
            targetMethod,
            position,
            scope,
            scopeRoots
        )
    }

    private fun findMethodCalls(method: PsiMethod): List<PsiMethodCallExpression> {

        fun belongsToMethod(expression: PsiMethodCallExpression, method: PsiMethod): Boolean {
            var parent = expression.parent
            while (parent != null && parent !== method) {
                if (parent is PsiMethod || parent is PsiClass) {
                    return false
                }

                parent = parent.parent
            }

            return parent === method
        }

        val body = method.body ?: return emptyList()

        return PsiTreeUtil
            .findChildrenOfType(body, PsiMethodCallExpression::class.java)
            .filter { belongsToMethod(it, method) }
            .sortedBy { it.textOffset }
    }

    private fun matchesScopeRoots(
        method: PsiMethod,
        contract: Contract,
        superMethods: MutableMap<Scope, List<PsiMethod>>
    ): Boolean {

        fun transitiveSuperMethods(method: PsiMethod): List<PsiMethod> {
            val result = LinkedHashSet<PsiMethod>()
            val pending = ArrayDeque<PsiMethod>()
            pending.addAll(method.findSuperMethods())

            while (pending.isNotEmpty()) {
                val current = pending.removeFirst()
                if (!result.add(current)) {
                    continue
                }

                pending.addAll(current.findSuperMethods())
            }

            return result.toList()
        }

        val candidates = superMethods.getOrPut(contract.scope) {
            when (contract.scope) {
                Scope.DIRECT_OVERRIDERS ->
                    method.findSuperMethods().toList()

                Scope.TRANSITIVE_OVERRIDERS ->
                    transitiveSuperMethods(method)
            }
        }
        val manager = PsiManager.getInstance(project)
        val targetMethod = contract.targetMethod.element ?: return false

        for (scopeRootPointer in contract.scopeRoots) {
            val scopeRoot = scopeRootPointer.element ?: continue

            if (scopeRoot.qualifiedName == SUPER) {
                if (candidates.any { manager.areElementsEquivalent(it, targetMethod) }) {
                    return true
                }
            } else {
                if (candidates.any {
                        val containingClass = it.containingClass
                        containingClass != null && manager.areElementsEquivalent(containingClass, scopeRoot)
                    }
                ) {
                    return true
                }
            }
        }

        return false
    }

    private fun matchesPosition(
        expression: PsiMethodCallExpression,
        method: PsiMethod,
        position: Position
    ): Boolean {
        if (position == Position.ANYWHERE) {
            return true
        }

        fun directStatement(expression: PsiMethodCallExpression): PsiExpressionStatement? {
            val statement = expression.parent as? PsiExpressionStatement ?: return null
            if (statement.expression !== expression) {
                return null
            }

            return statement
        }

        val statement = directStatement(expression) ?: return false

        fun matchesHead(statement: PsiExpressionStatement, method: PsiMethod): Boolean {
            val body = method.body ?: return false
            val firstStatement = body.statements.firstOrNull() ?: return false

            if (firstStatement === statement) {
                return true
            }

            val tryStatement = firstStatement as? PsiTryStatement ?: return false
            if (tryStatement.resourceList != null) {
                return false
            }

            return tryStatement.tryBlock?.statements?.firstOrNull() === statement
        }

        fun isDirectlyInFinally(statement: PsiExpressionStatement): Boolean {
            val block = statement.parent as? PsiCodeBlock ?: return false
            val tryStatement = block.parent as? PsiTryStatement ?: return false

            return tryStatement.finallyBlock === block
        }

        fun isDirectlyInOutermostFinally(statement: PsiExpressionStatement, method: PsiMethod): Boolean {
            val body = method.body ?: return false
            val tryStatement = body.statements.singleOrNull() as? PsiTryStatement ?: return false

            return statement.parent === tryStatement.finallyBlock
        }

        return when (position) {
            Position.HEAD ->
                matchesHead(statement, method)

            Position.IN_FINALLY ->
                isDirectlyInFinally(statement)

            Position.OUTERMOST_FINALLY ->
                isDirectlyInOutermostFinally(statement, method)
        }
    }
}
