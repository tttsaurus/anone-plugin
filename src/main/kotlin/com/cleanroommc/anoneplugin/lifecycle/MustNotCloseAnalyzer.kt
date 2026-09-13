package com.cleanroommc.anoneplugin.lifecycle

import com.intellij.psi.*
import com.intellij.psi.controlFlow.DefUseUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiUtil

private const val MUST_NOT_CLOSE = "com.cleanroommc.anone.lifecycle.MustNotClose"

internal class MustNotCloseAnalyzer {

    enum class Ownership {
        CLEAR,
        MUST_NOT_CLOSE,
        MAYBE_MUST_NOT_CLOSE,
        UNKNOWN;

        val mayNotBeClosed: Boolean
            get() = this == MUST_NOT_CLOSE ||
                    this == MAYBE_MUST_NOT_CLOSE
    }

    fun ownershipOf(expression: PsiExpression): Ownership {
        return ownershipOf(expression, HashSet())
    }

    fun isAutoCloseable(
        type: PsiType?,
        context: PsiElement
    ): Boolean {
        val clazz = PsiUtil.resolveClassInType(type) ?: return false

        val autoCloseable = JavaPsiFacade
            .getInstance(context.project)
            .findClass("java.lang.AutoCloseable", context.resolveScope) ?: return false

        return clazz == autoCloseable || clazz.isInheritor(autoCloseable, true)
    }

    //<editor-fold desc="ownership evaluation">
    private fun ownershipOf(
        expression: PsiExpression,
        resolving: MutableSet<PsiVariable>
    ): Ownership {
        return when (expression) {
            is PsiParenthesizedExpression ->
                expression.expression?.let {
                    ownershipOf(it, resolving)
                } ?: Ownership.UNKNOWN

            is PsiTypeCastExpression ->
                expression.operand?.let {
                    ownershipOf(it, resolving)
                } ?: Ownership.UNKNOWN

            is PsiConditionalExpression ->
                join(
                    expression.thenExpression?.let {
                        ownershipOf(it, resolving)
                    } ?: Ownership.UNKNOWN,

                    expression.elseExpression?.let {
                        ownershipOf(it, resolving)
                    } ?: Ownership.UNKNOWN
                )

            is PsiSwitchExpression ->
                join(
                    PsiUtil
                        .getSwitchResultExpressions(expression)
                        .map { ownershipOf(it, resolving) }
                )

            is PsiAssignmentExpression ->
                if (expression.operationTokenType != JavaTokenType.EQ) {
                    Ownership.UNKNOWN
                } else {
                    expression.rExpression?.let {
                        ownershipOf(it, resolving)
                    } ?: Ownership.UNKNOWN
                }

            is PsiReferenceExpression ->
                ownershipOfReference(expression, resolving)

            is PsiMethodCallExpression ->
                ownershipOfMethodCall(expression)

            is PsiNewExpression ->
                if (isAutoCloseable(expression.type, expression)) {
                    Ownership.CLEAR
                } else {
                    Ownership.UNKNOWN
                }

            is PsiLiteralExpression ->
                if (expression.value == null) {
                    Ownership.CLEAR
                } else {
                    Ownership.UNKNOWN
                }

            else -> Ownership.UNKNOWN
        }
    }

    private fun ownershipOfMethodCall(expression: PsiMethodCallExpression): Ownership {
        val method = expression.resolveMethod() ?: return Ownership.UNKNOWN
        if (!method.hasDirectAnnotation(MUST_NOT_CLOSE)) {
            return Ownership.UNKNOWN
        }
        if (!isAutoCloseable(method.returnType, method)) {
            return Ownership.UNKNOWN
        }

        return Ownership.MUST_NOT_CLOSE
    }

    private fun ownershipOfReference(
        expression: PsiReferenceExpression,
        resolving: MutableSet<PsiVariable>
    ): Ownership {
        val variable = expression.resolve() as? PsiVariable ?: return Ownership.UNKNOWN
        if (variable is PsiField) {
            return ownershipOfFieldAt(
                variable,
                expression,
                resolving
            )
        }

        val lambda = PsiTreeUtil.getParentOfType(
            expression,
            PsiLambdaExpression::class.java
        )
        if (lambda != null && !PsiTreeUtil.isAncestor(lambda, variable, false)) {
            return initialOwnership(variable, resolving)
        }

        return ownershipOfVariableAt(
            variable,
            expression,
            resolving
        )
    }

    private fun ownershipOfVariableAt(
        variable: PsiVariable,
        context: PsiElement,
        resolving: MutableSet<PsiVariable>
    ): Ownership {
        if (!resolving.add(variable)) {
            return Ownership.UNKNOWN
        }

        try {
            val block = enclosingCodeBlock(variable, context) ?: return initialOwnership(variable, resolving)
            val defs = DefUseUtil.getDefs(
                block,
                variable,
                context
            )

            if (defs.isEmpty()) {
                return initialOwnership(variable, resolving)
            }

            return join(defs.map { ownershipOfDefinition(it, variable, resolving) })
        } finally {
            resolving.remove(variable)
        }
    }

    private fun ownershipOfFieldAt(
        field: PsiField,
        context: PsiReferenceExpression,
        resolving: MutableSet<PsiVariable>
    ): Ownership {
        if (!resolving.add(field)) {
            return Ownership.UNKNOWN
        }

        try {
            val block = enclosingExecutableBlock(context) ?: return initialOwnership(field, resolving)
            val state = fieldDefinitionsBeforeContext(
                block,
                context,
                FieldDefinitionState(
                    true,
                    emptySet(),
                    true
                ),
                field,
                context
            )

            if (!state.mayCompleteNormally) {
                return Ownership.UNKNOWN
            }

            val ownerships = ArrayList<Ownership>()
            if (state.hasEntryDefinition) {
                ownerships.add(initialOwnership(field, resolving))
            }

            state.definitions.mapTo(ownerships) {
                ownershipOfDefinition(it, field, resolving)
            }

            return join(ownerships)
        } finally {
            resolving.remove(field)
        }
    }

    private fun ownershipOfDefinition(
        definition: PsiElement?,
        variable: PsiVariable,
        resolving: MutableSet<PsiVariable>
    ): Ownership {
        if (definition == null) {
            return Ownership.UNKNOWN
        }

        return when {
            definition === variable || PsiTreeUtil.isAncestor(variable, definition, false) ->
                initialOwnership(variable, resolving)

            definition is PsiParameter ->
                initialOwnership(definition, resolving)

            definition is PsiLocalVariable ->
                definition.initializer?.let {
                    ownershipOf(it, resolving)
                } ?: Ownership.UNKNOWN

            definition is PsiReferenceExpression -> {
                val assignment = definition.parent as? PsiAssignmentExpression

                if (assignment != null &&
                    assignment.lExpression === definition &&
                    assignment.operationTokenType == JavaTokenType.EQ
                ) {
                    assignment.rExpression?.let {
                        ownershipOf(it, resolving)
                    } ?: Ownership.UNKNOWN
                } else {
                    Ownership.UNKNOWN
                }
            }

            definition is PsiExpression ->
                ownershipOf(definition, resolving)

            else ->
                Ownership.UNKNOWN
        }
    }

    private fun initialOwnership(
        variable: PsiVariable,
        resolving: MutableSet<PsiVariable>
    ): Ownership {
        if ((variable is PsiParameter || variable is PsiField) &&
            variable.hasDirectAnnotation(MUST_NOT_CLOSE) &&
            isAutoCloseable(variable.type, variable)
        ) {
            return Ownership.MUST_NOT_CLOSE
        }

        if (variable is PsiLocalVariable) {
            return variable.initializer?.let {
                ownershipOf(it, resolving)
            } ?: Ownership.UNKNOWN
        }

        return Ownership.UNKNOWN
    }
    //</editor-fold>

    //<editor-fold desc="field definition evaluation">
    private data class FieldDefinitionState(
        val hasEntryDefinition: Boolean,
        val definitions: Set<PsiReferenceExpression>,
        val mayCompleteNormally: Boolean
    )

    private fun fieldDefinitionsBeforeContext(
        block: PsiCodeBlock?,
        context: PsiElement,
        initialState: FieldDefinitionState,
        field: PsiField,
        target: PsiReferenceExpression
    ): FieldDefinitionState {
        if (block == null || !initialState.mayCompleteNormally) {
            return initialState
        }

        var state = initialState

        for (statement in block.statements) {
            if (PsiTreeUtil.isAncestor(statement, context, false)) {
                return fieldDefinitionsBeforeContext(
                    statement,
                    context,
                    state,
                    field,
                    target
                )
            }

            if (statement.textOffset >= context.textOffset) {
                break
            }

            state = fieldDefinitionsAfter(
                statement,
                state,
                field,
                target
            )

            if (!state.mayCompleteNormally) {
                break
            }
        }

        return state
    }

    private fun fieldDefinitionsBeforeContext(
        statement: PsiStatement?,
        context: PsiElement,
        initialState: FieldDefinitionState,
        field: PsiField,
        target: PsiReferenceExpression
    ): FieldDefinitionState {
        if (statement == null || !initialState.mayCompleteNormally) {
            return initialState
        }

        return when (statement) {
            is PsiBlockStatement ->
                fieldDefinitionsBeforeContext(
                    statement.codeBlock,
                    context,
                    initialState,
                    field,
                    target
                )

            is PsiIfStatement -> {
                val conditionState = fieldDefinitionsAfter(
                    statement.condition,
                    initialState,
                    field,
                    target
                )

                when {
                    PsiTreeUtil.isAncestor(statement.condition, context, false) ->
                        initialState

                    statement.thenBranch?.let {
                        PsiTreeUtil.isAncestor(it, context, false)
                    } == true ->
                        fieldDefinitionsBeforeContext(
                            statement.thenBranch,
                            context,
                            conditionState,
                            field,
                            target
                        )

                    statement.elseBranch?.let {
                        PsiTreeUtil.isAncestor(it, context, false)
                    } == true ->
                        fieldDefinitionsBeforeContext(
                            statement.elseBranch,
                            context,
                            conditionState,
                            field,
                            target
                        )

                    else ->
                        conditionState
                }
            }

            is PsiWhileStatement ->
                fieldDefinitionsBeforeLoopBody(
                    statement.condition,
                    statement.body,
                    context,
                    initialState,
                    field,
                    target,
                    false
                )

            is PsiDoWhileStatement ->
                fieldDefinitionsBeforeLoopBody(
                    statement.condition,
                    statement.body,
                    context,
                    initialState,
                    field,
                    target,
                    true
                )

            is PsiForStatement -> {
                var state = fieldDefinitionsAfter(
                    statement.initialization,
                    initialState,
                    field,
                    target
                )
                state = fieldDefinitionsAfter(
                    statement.condition,
                    state,
                    field,
                    target
                )

                if (statement.body?.let { PsiTreeUtil.isAncestor(it, context, false) } == true) {
                    val repeatedState = fieldDefinitionsAfter(
                        statement.body,
                        state,
                        field,
                        target
                    )
                    val updatedState = fieldDefinitionsAfter(
                        statement.update,
                        repeatedState,
                        field,
                        target
                    )

                    state = mergeFieldDefinitionStates(state, updatedState)

                    fieldDefinitionsBeforeContext(
                        statement.body,
                        context,
                        state,
                        field,
                        target
                    )
                } else {
                    state
                }
            }

            is PsiForeachStatement -> {
                val state = fieldDefinitionsAfter(
                    statement.iteratedValue,
                    initialState,
                    field,
                    target
                )

                if (statement.body?.let { PsiTreeUtil.isAncestor(it, context, false) } == true) {
                    val repeatedState = fieldDefinitionsAfter(
                        statement.body,
                        state,
                        field,
                        target
                    )

                    fieldDefinitionsBeforeContext(
                        statement.body,
                        context,
                        mergeFieldDefinitionStates(state, repeatedState),
                        field,
                        target
                    )
                } else {
                    state
                }
            }

            is PsiSynchronizedStatement ->
                if (PsiTreeUtil.isAncestor(statement.body, context, false)) {
                    fieldDefinitionsBeforeContext(
                        statement.body,
                        context,
                        fieldDefinitionsAfter(
                            statement.lockExpression,
                            initialState,
                            field,
                            target
                        ),
                        field,
                        target
                    )
                } else {
                    initialState
                }

            is PsiTryStatement -> {
                val tryState = fieldDefinitionsAfter(
                    statement.tryBlock,
                    initialState,
                    field,
                    target
                )
                var state = tryState

                for (section in statement.catchSections) {
                    if (PsiTreeUtil.isAncestor(section, context, false)) {
                        return fieldDefinitionsBeforeContext(
                            section.catchBlock,
                            context,
                            initialState,
                            field,
                            target
                        )
                    }

                    state = mergeFieldDefinitionStates(
                        state,
                        fieldDefinitionsAfter(
                            section.catchBlock,
                            initialState,
                            field,
                            target
                        )
                    )
                }

                when {
                    PsiTreeUtil.isAncestor(statement.tryBlock, context, false) ->
                        fieldDefinitionsBeforeContext(
                            statement.tryBlock,
                            context,
                            initialState,
                            field,
                            target
                        )

                    statement.finallyBlock?.let {
                        PsiTreeUtil.isAncestor(it, context, false)
                    } == true ->
                        fieldDefinitionsBeforeContext(
                            statement.finallyBlock,
                            context,
                            state,
                            field,
                            target
                        )

                    else ->
                        initialState
                }
            }

            is PsiLabeledStatement ->
                statement.statement?.let {
                    fieldDefinitionsBeforeContext(
                        it,
                        context,
                        initialState,
                        field,
                        target
                    )
                } ?: initialState

            else ->
                initialState
        }
    }

    private fun fieldDefinitionsBeforeLoopBody(
        condition: PsiExpression?,
        body: PsiStatement?,
        context: PsiElement,
        initialState: FieldDefinitionState,
        field: PsiField,
        target: PsiReferenceExpression,
        bodyExecutesAtLeastOnce: Boolean
    ): FieldDefinitionState {
        var state = if (bodyExecutesAtLeastOnce) {
            initialState
        } else {
            fieldDefinitionsAfter(
                condition,
                initialState,
                field,
                target
            )
        }

        if (body?.let { PsiTreeUtil.isAncestor(it, context, false) } != true) {
            return state
        }

        val repeatedState = fieldDefinitionsAfter(
            body,
            state,
            field,
            target
        )
        state = mergeFieldDefinitionStates(
            state,
            fieldDefinitionsAfter(
                condition,
                repeatedState,
                field,
                target
            )
        )

        return fieldDefinitionsBeforeContext(
            body,
            context,
            state,
            field,
            target
        )
    }

    private fun fieldDefinitionsAfter(
        block: PsiCodeBlock?,
        initialState: FieldDefinitionState,
        field: PsiField,
        target: PsiReferenceExpression
    ): FieldDefinitionState {
        if (block == null || !initialState.mayCompleteNormally) {
            return initialState
        }

        var state = initialState

        for (statement in block.statements) {
            state = fieldDefinitionsAfter(
                statement,
                state,
                field,
                target
            )

            if (!state.mayCompleteNormally) {
                break
            }
        }

        return state
    }

    private fun fieldDefinitionsAfter(
        statement: PsiStatement?,
        initialState: FieldDefinitionState,
        field: PsiField,
        target: PsiReferenceExpression
    ): FieldDefinitionState {
        if (statement == null || !initialState.mayCompleteNormally) {
            return initialState
        }

        return when (statement) {
            is PsiBlockStatement ->
                fieldDefinitionsAfter(
                    statement.codeBlock,
                    initialState,
                    field,
                    target
                )

            is PsiExpressionStatement ->
                fieldDefinitionsAfter(
                    statement.expression,
                    initialState,
                    field,
                    target
                )

            is PsiExpressionListStatement -> {
                var state = initialState

                for (expression in statement.expressionList.expressions) {
                    state = fieldDefinitionsAfter(
                        expression,
                        state,
                        field,
                        target
                    )
                }

                state
            }

            is PsiDeclarationStatement -> {
                var state = initialState

                for (element in statement.declaredElements) {
                    val variable = element as? PsiVariable ?: continue
                    state = fieldDefinitionsAfter(
                        variable.initializer,
                        state,
                        field,
                        target
                    )
                }

                state
            }

            is PsiIfStatement -> {
                val state = fieldDefinitionsAfter(
                    statement.condition,
                    initialState,
                    field,
                    target
                )
                val thenState = fieldDefinitionsAfter(
                    statement.thenBranch,
                    state,
                    field,
                    target
                )
                val elseState = statement.elseBranch?.let {
                    fieldDefinitionsAfter(
                        it,
                        state,
                        field,
                        target
                    )
                } ?: state

                mergeFieldDefinitionStates(thenState, elseState)
            }

            is PsiWhileStatement -> {
                val state = fieldDefinitionsAfter(
                    statement.condition,
                    initialState,
                    field,
                    target
                )
                val repeatedState = fieldDefinitionsAfter(
                    statement.body,
                    state,
                    field,
                    target
                )

                mergeFieldDefinitionStates(
                    state,
                    fieldDefinitionsAfter(
                        statement.condition,
                        repeatedState,
                        field,
                        target
                    )
                )
            }

            is PsiDoWhileStatement -> {
                val state = fieldDefinitionsAfter(
                    statement.body,
                    initialState,
                    field,
                    target
                )
                val conditionState = fieldDefinitionsAfter(
                    statement.condition,
                    state,
                    field,
                    target
                )

                mergeFieldDefinitionStates(
                    state,
                    fieldDefinitionsAfter(
                        statement.body,
                        conditionState,
                        field,
                        target
                    )
                )
            }

            is PsiForStatement -> {
                var state = fieldDefinitionsAfter(
                    statement.initialization,
                    initialState,
                    field,
                    target
                )
                state = fieldDefinitionsAfter(
                    statement.condition,
                    state,
                    field,
                    target
                )

                val repeatedState = fieldDefinitionsAfter(
                    statement.update,
                    fieldDefinitionsAfter(
                        statement.body,
                        state,
                        field,
                        target
                    ),
                    field,
                    target
                )

                mergeFieldDefinitionStates(
                    state,
                    fieldDefinitionsAfter(
                        statement.condition,
                        repeatedState,
                        field,
                        target
                    )
                )
            }

            is PsiForeachStatement -> {
                val state = fieldDefinitionsAfter(
                    statement.iteratedValue,
                    initialState,
                    field,
                    target
                )
                val repeatedState = fieldDefinitionsAfter(
                    statement.body,
                    state,
                    field,
                    target
                )

                mergeFieldDefinitionStates(state, repeatedState)
            }

            is PsiSynchronizedStatement ->
                fieldDefinitionsAfter(
                    statement.body,
                    fieldDefinitionsAfter(
                        statement.lockExpression,
                        initialState,
                        field,
                        target
                    ),
                    field,
                    target
                )

            is PsiTryStatement -> {
                var state = fieldDefinitionsAfter(
                    statement.tryBlock,
                    initialState,
                    field,
                    target
                )

                for (section in statement.catchSections) {
                    state = mergeFieldDefinitionStates(
                        state,
                        fieldDefinitionsAfter(
                            section.catchBlock,
                            initialState,
                            field,
                            target
                        )
                    )
                }

                statement.finallyBlock?.let {
                    fieldDefinitionsAfter(
                        it,
                        state,
                        field,
                        target
                    )
                } ?: state
            }

            is PsiLabeledStatement ->
                fieldDefinitionsAfter(
                    statement.statement,
                    initialState,
                    field,
                    target
                )

            is PsiReturnStatement ->
                fieldDefinitionsAfter(
                    statement.returnValue,
                    initialState,
                    field,
                    target
                ).copy(mayCompleteNormally = false)

            is PsiThrowStatement ->
                fieldDefinitionsAfter(
                    statement.exception,
                    initialState,
                    field,
                    target
                ).copy(mayCompleteNormally = false)

            else ->
                initialState
        }
    }

    private fun fieldDefinitionsAfter(
        expression: PsiExpression?,
        initialState: FieldDefinitionState,
        field: PsiField,
        target: PsiReferenceExpression
    ): FieldDefinitionState {
        if (expression == null || !initialState.mayCompleteNormally) {
            return initialState
        }

        return when (expression) {
            is PsiParenthesizedExpression ->
                fieldDefinitionsAfter(
                    expression.expression,
                    initialState,
                    field,
                    target
                )

            is PsiTypeCastExpression ->
                fieldDefinitionsAfter(
                    expression.operand,
                    initialState,
                    field,
                    target
                )

            is PsiAssignmentExpression -> {
                val state = fieldDefinitionsAfter(
                    expression.rExpression,
                    initialState,
                    field,
                    target
                )
                val reference = expression.lExpression as? PsiReferenceExpression

                if (expression.operationTokenType == JavaTokenType.EQ &&
                    reference != null &&
                    isSameFieldReference(reference, target, field)
                ) {
                    FieldDefinitionState(
                        false,
                        setOf(reference),
                        true
                    )
                } else {
                    state
                }
            }

            is PsiConditionalExpression -> {
                val state = fieldDefinitionsAfter(
                    expression.condition,
                    initialState,
                    field,
                    target
                )
                val thenState = fieldDefinitionsAfter(
                    expression.thenExpression,
                    state,
                    field,
                    target
                )
                val elseState = fieldDefinitionsAfter(
                    expression.elseExpression,
                    state,
                    field,
                    target
                )

                mergeFieldDefinitionStates(thenState, elseState)
            }

            is PsiPolyadicExpression -> {
                var state = initialState

                for ((index, operand) in expression.operands.withIndex()) {
                    val operandState = fieldDefinitionsAfter(
                        operand,
                        state,
                        field,
                        target
                    )

                    state = if (
                        index > 0 &&
                        (expression.operationTokenType == JavaTokenType.ANDAND ||
                                expression.operationTokenType == JavaTokenType.OROR)
                    ) {
                        mergeFieldDefinitionStates(state, operandState)
                    } else {
                        operandState
                    }
                }

                state
            }

            is PsiMethodCallExpression -> {
                var state = fieldDefinitionsAfter(
                    expression.methodExpression.qualifierExpression,
                    initialState,
                    field,
                    target
                )

                for (argument in expression.argumentList.expressions) {
                    state = fieldDefinitionsAfter(
                        argument,
                        state,
                        field,
                        target
                    )
                }

                state
            }

            is PsiNewExpression -> {
                var state = initialState

                for (argument in expression.argumentList?.expressions.orEmpty()) {
                    state = fieldDefinitionsAfter(
                        argument,
                        state,
                        field,
                        target
                    )
                }

                state
            }

            else ->
                initialState
        }
    }

    private fun mergeFieldDefinitionStates(
        a: FieldDefinitionState,
        b: FieldDefinitionState
    ): FieldDefinitionState {
        if (!a.mayCompleteNormally) {
            return b
        }
        if (!b.mayCompleteNormally) {
            return a
        }

        val definitions = LinkedHashSet<PsiReferenceExpression>()
        definitions.addAll(a.definitions)
        definitions.addAll(b.definitions)

        return FieldDefinitionState(
            a.hasEntryDefinition || b.hasEntryDefinition,
            definitions,
            true
        )
    }
    //</editor-fold>

    private fun isSameFieldReference(
        reference: PsiReferenceExpression,
        target: PsiReferenceExpression,
        field: PsiField
    ): Boolean {
        if (reference.resolve() !== field) {
            return false
        }
        if (field.hasModifierProperty(PsiModifier.STATIC)) {
            return true
        }

        return isSameFieldQualifier(
            reference.qualifierExpression,
            target.qualifierExpression
        )
    }

    private fun isSameFieldQualifier(
        a: PsiExpression?,
        b: PsiExpression?
    ): Boolean {
        val first = unwrapExpression(a)
        val second = unwrapExpression(b)

        if (isCurrentInstanceQualifier(first) && isCurrentInstanceQualifier(second)) {
            return true
        }

        val firstReference = first as? PsiReferenceExpression ?: return false
        val secondReference = second as? PsiReferenceExpression ?: return false
        val variable = firstReference.resolve() as? PsiVariable ?: return false

        if (secondReference.resolve() !== variable) {
            return false
        }
        if (variable.hasModifierProperty(PsiModifier.FINAL)) {
            return true
        }
        if (variable !is PsiLocalVariable && variable !is PsiParameter) {
            return false
        }

        val block = enclosingCodeBlock(variable, secondReference) ?: return false
        if (!PsiTreeUtil.isAncestor(block, firstReference, false)) {
            return false
        }

        val firstDefinitions = DefUseUtil.getDefs(
            block,
            variable,
            firstReference
        )
        val secondDefinitions = DefUseUtil.getDefs(
            block,
            variable,
            secondReference
        )

        return firstDefinitions.size == secondDefinitions.size &&
                firstDefinitions.all { definition -> secondDefinitions.any { it === definition } }
    }

    private fun unwrapExpression(expression: PsiExpression?): PsiExpression? {
        return when (expression) {
            is PsiParenthesizedExpression ->
                unwrapExpression(expression.expression)

            is PsiTypeCastExpression ->
                unwrapExpression(expression.operand)

            else ->
                expression
        }
    }

    private fun isCurrentInstanceQualifier(expression: PsiExpression?): Boolean {
        return expression == null ||
                expression is PsiThisExpression && expression.qualifier == null ||
                expression is PsiSuperExpression && expression.qualifier == null
    }

    private fun enclosingCodeBlock(
        variable: PsiVariable,
        context: PsiElement
    ): PsiCodeBlock? {
        return when (variable) {
            is PsiParameter -> when (val scope = variable.declarationScope) {
                is PsiMethod ->
                    scope.body

                is PsiLambdaExpression ->
                    scope.body as? PsiCodeBlock

                is PsiCatchSection ->
                    scope.catchBlock

                else ->
                    PsiTreeUtil.getParentOfType(
                        variable,
                        PsiCodeBlock::class.java
                    )
            }

            is PsiLocalVariable ->
                PsiTreeUtil.getParentOfType(
                    variable,
                    PsiCodeBlock::class.java
                )

            is PsiField ->
                enclosingExecutableBlock(context)

            else ->
                null
        }
    }

    private fun enclosingExecutableBlock(context: PsiElement): PsiCodeBlock? {
        var current: PsiElement? = context
        while (current != null) {
            when (current) {
                is PsiLambdaExpression ->
                    return current.body as? PsiCodeBlock

                is PsiMethod ->
                    return current.body

                is PsiClassInitializer ->
                    return current.body
            }

            current = current.parent
        }

        return null
    }

    private fun join(states: Collection<Ownership>): Ownership {
        if (states.isEmpty()) {
            return Ownership.UNKNOWN
        }

        var result: Ownership? = null
        for (state in states) {
            result = if (result == null) {
                state
            } else {
                join(result, state)
            }
        }

        return result ?: Ownership.UNKNOWN
    }

    private fun join(a: Ownership, b: Ownership): Ownership {
        if (a == b) {
            return a
        }

        if (a == Ownership.MAYBE_MUST_NOT_CLOSE || b == Ownership.MAYBE_MUST_NOT_CLOSE) {
            return Ownership.MAYBE_MUST_NOT_CLOSE
        }

        if (a == Ownership.MUST_NOT_CLOSE || b == Ownership.MUST_NOT_CLOSE) {
            return Ownership.MAYBE_MUST_NOT_CLOSE
        }

        if (a == Ownership.UNKNOWN || b == Ownership.UNKNOWN) {
            return Ownership.UNKNOWN
        }

        return Ownership.CLEAR
    }

    private fun PsiModifierListOwner.hasDirectAnnotation(qualifiedName: String): Boolean {
        return modifierList?.findAnnotation(qualifiedName) != null
    }
}
