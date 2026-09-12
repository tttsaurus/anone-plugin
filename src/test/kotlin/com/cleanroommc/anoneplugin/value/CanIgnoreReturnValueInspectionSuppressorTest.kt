package com.cleanroommc.anoneplugin.value

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class CanIgnoreReturnValueInspectionSuppressorTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var suppressor: CanIgnoreReturnValueInspectionSuppressor

    private fun addAnnotation(
        packageName: String,
        name: String
    ) {
        myFixture.addClass(
            """
            package $packageName;

            public @interface $name {}
            """.trimIndent()
        )
    }

    private fun configureClass(
        classAnnotations: String = "",
        body: String
    ): PsiClass {
        val file = myFixture.configureByText(
            "Test.java",
            """
            import com.cleanroommc.anone.value.CanIgnoreReturnValue;
            import org.jetbrains.annotations.CheckReturnValue;

            $classAnnotations
            class Test {
                ${body.trimIndent()}
            }
            """.trimIndent()
        ) as PsiJavaFile

        return file.classes.single()
    }

    private fun method(
        clazz: PsiClass,
        name: String = "func"
    ): PsiMethod {
        return clazz.findMethodsByName(name, false).single()
    }

    private fun isUnusedReturnValueSuppressed(method: PsiMethod): Boolean {
        return suppressor.isSuppressedFor(
            method.nameIdentifier!!,
            "UnusedReturnValue"
        )
    }

    override fun setUp() {
        super.setUp()

        suppressor = CanIgnoreReturnValueInspectionSuppressor()

        addAnnotation(
            "com.cleanroommc.anone.value",
            "CanIgnoreReturnValue"
        )

        addAnnotation(
            "org.jetbrains.annotations",
            "CheckReturnValue"
        )
    }

    fun testMethodCanIgnoreReturnValue() {
        val clazz = configureClass(
            body = """
                @CanIgnoreReturnValue
                int func() {
                    return 1;
                }
            """
        )

        assertTrue(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testMethodCheckReturnValue() {
        val clazz = configureClass(
            body = """
                @CheckReturnValue
                int func() {
                    return 1;
                }
            """
        )

        assertFalse(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testClassCanIgnoreReturnValue() {
        val clazz = configureClass(
            classAnnotations = "@CanIgnoreReturnValue",
            body = """
                int func() {
                    return 1;
                }
            """
        )

        assertTrue(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testClassCheckReturnValue() {
        val clazz = configureClass(
            classAnnotations = "@CheckReturnValue",
            body = """
                int func() {
                    return 1;
                }
            """
        )

        assertFalse(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testMethodCanIgnoreOverridesClassCheck() {
        val clazz = configureClass(
            classAnnotations = "@CheckReturnValue",
            body = """
                @CanIgnoreReturnValue
                int func() {
                    return 1;
                }
            """
        )

        assertTrue(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testMethodCheckOverridesClassCanIgnore() {
        val clazz = configureClass(
            classAnnotations = "@CanIgnoreReturnValue",
            body = """
                @CheckReturnValue
                int func() {
                    return 1;
                }
            """
        )

        assertFalse(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testMethodCanIgnoreWinsWhenBothPresent() {
        val clazz = configureClass(
            body = """
                @CanIgnoreReturnValue
                @CheckReturnValue
                int func() {
                    return 1;
                }
            """
        )

        assertTrue(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testClassCanIgnoreWinsWhenBothPresent() {
        val clazz = configureClass(
            classAnnotations = """
                @CanIgnoreReturnValue
                @CheckReturnValue
            """.trimIndent(),
            body = """
                int func() {
                    return 1;
                }
            """
        )

        assertTrue(isUnusedReturnValueSuppressed(method(clazz)))
    }

    fun testNoAnnotation() {
        val clazz = configureClass(
            body = """
                int func() {
                    return 1;
                }
            """
        )

        assertFalse(isUnusedReturnValueSuppressed(method(clazz)))
    }
}
