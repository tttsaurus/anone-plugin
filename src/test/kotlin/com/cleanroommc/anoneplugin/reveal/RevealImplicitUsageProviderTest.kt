package com.cleanroommc.anoneplugin.reveal

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class RevealImplicitUsageProviderTest : LightJavaCodeInsightFixtureTestCase() {

    private lateinit var provider: RevealImplicitUsageProvider

    private fun addAnnotation(name: String) {
        myFixture.addClass(
            """
            package com.cleanroommc.anone.reveal;

            public @interface $name {}
            """.trimIndent()
        )
    }

    private fun configureClass(body: String): PsiClass {
        val file = myFixture.configureByText(
            "Test.java",
            """
            import com.cleanroommc.anone.reveal.*;

            class Test {
                ${body.trimIndent()}
            }
            """.trimIndent()
        ) as PsiJavaFile

        return file.classes.single()
    }

    override fun setUp() {
        super.setUp()

        provider = RevealImplicitUsageProvider()

        addAnnotation("GeneratedAccess")
        addAnnotation("ReflectiveAccess")
        addAnnotation("GeneratedInvocation")
        addAnnotation("ReflectiveInvocation")
    }

    fun testGeneratedAccess() {
        val field = configureClass(
            """
            @GeneratedAccess
            private int value;
            """
        ).findFieldByName("value", false)!!

        assertTrue(provider.isImplicitUsage(field))
        assertTrue(provider.isImplicitRead(field))
        assertTrue(provider.isImplicitWrite(field))
    }

    fun testReflectiveAccess() {
        val field = configureClass(
            """
            @ReflectiveAccess
            private int value;
            """
        ).findFieldByName("value", false)!!

        assertTrue(provider.isImplicitUsage(field))
        assertTrue(provider.isImplicitRead(field))
        assertTrue(provider.isImplicitWrite(field))
    }

    fun testGeneratedInvocation() {
        val method = configureClass(
            """
            @GeneratedInvocation
            private void invoke() {}
            """
        ).findMethodsByName("invoke", false).single()

        assertTrue(provider.isImplicitUsage(method))
        assertFalse(provider.isImplicitRead(method))
        assertFalse(provider.isImplicitWrite(method))
    }

    fun testReflectiveInvocation() {
        val method = configureClass(
            """
            @ReflectiveInvocation
            private void invoke() {}
            """
        ).findMethodsByName("invoke", false).single()

        assertTrue(provider.isImplicitUsage(method))
        assertFalse(provider.isImplicitRead(method))
        assertFalse(provider.isImplicitWrite(method))
    }

    fun testInvocationAnnotationOnConstructor() {
        val clazz = configureClass(
            """
            @ReflectiveInvocation
            private Test() {}
            """
        )

        val constructor = clazz.constructors.single()

        assertTrue(provider.isImplicitUsage(constructor))
        assertFalse(provider.isImplicitRead(constructor))
        assertFalse(provider.isImplicitWrite(constructor))
    }

    fun testUnannotatedMembersAreNotImplicitlyUsed() {
        val clazz = configureClass(
            """
            private int value;
            private void func() {}
            """
        )

        val field = clazz.findFieldByName("value", false)!!
        val method = clazz.findMethodsByName("func", false).single()

        assertFalse(provider.isImplicitUsage(field))
        assertFalse(provider.isImplicitRead(field))
        assertFalse(provider.isImplicitWrite(field))

        assertFalse(provider.isImplicitUsage(method))
        assertFalse(provider.isImplicitRead(method))
        assertFalse(provider.isImplicitWrite(method))
    }

    fun testLocalVariableIsIgnored() {
        val clazz = configureClass(
            """
            private void func() {
                int local = 0;
            }
            """
        )

        val method = clazz.findMethodsByName("func", false).single()
        val local = PsiTreeUtil.findChildOfType(
            method,
            PsiLocalVariable::class.java
        )!!

        assertFalse(provider.isImplicitUsage(local))
        assertFalse(provider.isImplicitRead(local))
        assertFalse(provider.isImplicitWrite(local))
    }
}
