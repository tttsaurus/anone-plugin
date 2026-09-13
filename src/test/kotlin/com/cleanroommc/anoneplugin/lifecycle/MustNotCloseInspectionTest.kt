package com.cleanroommc.anoneplugin.lifecycle

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class MustNotCloseInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    private fun addMustNotCloseAnnotation() {
        myFixture.addClass(
            """
            package com.cleanroommc.anone.lifecycle;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target({ ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD })
            @Retention(RetentionPolicy.CLASS)
            public @interface MustNotClose { }
            """.trimIndent()
        )
    }

    private fun addResClass() {
        myFixture.addClass(
            """
            package test;

            public class Res implements AutoCloseable {

                @Override
                public void close() {}
            }
            """.trimIndent()
        )
    }

    private fun check(body: String) {
        myFixture.configureByText(
            "Test.java",
            """
            package test;

            import com.cleanroommc.anone.lifecycle.MustNotClose;

            class Test {

                private final Res source = new Res();

                @MustNotClose
                private Res borrowed = source;

                @MustNotClose
                private static Res staticBorrowed = new Res();

                @MustNotClose
                Res getRes() {
                    return source;
                }

                Res createRes() {
                    return new Res();
                }

                ${body.trimIndent()}
            }
            """.trimIndent()
        )

        myFixture.checkHighlighting()
    }

    override fun setUp() {
        super.setUp()

        addMustNotCloseAnnotation()
        addResClass()

        myFixture.enableInspections(MustNotCloseInspection())
    }

    override fun getProjectDescriptor(): LightProjectDescriptor {
        return JAVA_17
    }

    fun testMethod() {
        check(
            """
            void use() {
                getRes().<error>close</error>();
                createRes().close();
                new Res().close();
            }
            """
        )
    }

    fun testAliases() {
        check(
            """
            void use() {
                Res first = getRes();
                Res second = first;
                Res third = second;

                first.<error>close</error>();
                second.<error>close</error>();
                third.<error>close</error>();

                third = new Res();
                third.close();
            }
            """
        )
    }

    fun testParameter() {
        check(
            """
            void use(@MustNotClose Res value) {
                value.<error>close</error>();

                value = new Res();
                value.close();

                value = getRes();
                value.<error>close</error>();
            }
            """
        )
    }

    fun testField() {
        check(
            """
            void use() {
                borrowed.<error>close</error>();

                Res value = borrowed;
                value.<error>close</error>();

                borrowed = new Res();
                borrowed.close();
            }
            """
        )
    }

    fun testReceiver() {
        check(
            """
            void use(Test other) {
                other.borrowed.<error>close</error>();

                other.borrowed = new Res();
                other.borrowed.close();

                this.borrowed.<error>close</error>();

                borrowed = new Res();
                this.borrowed.close();
            }
            """
        )
    }

    fun testReceiverChange() {
        check(
            """
            void use(Test other) {
                other.borrowed = new Res();
                other.borrowed.close();

                other = new Test();
                other.borrowed.<error>close</error>();
            }
            """
        )
    }

    fun testReceiverMethod() {
        check(
            """
            void use(Test other) {
                other.getRes().<error>close</error>();

                Res value = other.getRes();
                value.<error>close</error>();

                value = new Res();
                value.close();
            }
            """
        )
    }

    fun testBranches() {
        check(
            """
            void use(boolean condition) {
                if (condition) {
                    borrowed = new Res();
                } else {
                    borrowed = new Res();
                }

                borrowed.close();

                if (condition) {
                    borrowed = getRes();
                } else {
                    borrowed = new Res();
                }

                borrowed.<error>close</error>();
            }
            """
        )
    }

    fun testNestedBranches() {
        check(
            """
            void use(boolean first, boolean second) {
                if (first) {
                    borrowed = new Res();
                } else if (second) {
                    borrowed = new Res();
                } else {
                    borrowed = new Res();
                }

                borrowed.close();
            }
            """
        )
    }

    fun testLocalBranches() {
        check(
            """
            void use(boolean condition) {
                Res first;
                if (condition) {
                    first = getRes();
                } else {
                    first = new Res();
                }
                first.<error>close</error>();

                Res second;
                if (condition) {
                    second = new Res();
                } else {
                    second = new Res();
                }
                second.close();
            }
            """
        )
    }

    fun testParameterBranch() {
        check(
            """
            void use(@MustNotClose Res value, boolean condition) {
                if (condition) {
                    value = new Res();
                }

                value.<error>close</error>();
            }
            """
        )
    }

    fun testPartialBranch() {
        check(
            """
            void use(boolean condition) {
                if (condition) {
                    borrowed = new Res();
                }

                borrowed.<error>close</error>();
            }
            """
        )
    }

    fun testLoops() {
        check(
            """
            void use(boolean condition) {
                while (condition) {
                    borrowed = new Res();
                    borrowed.close();
                    break;
                }

                borrowed.<error>close</error>();
            }

            void useDoWhile() {
                do {
                    borrowed = new Res();
                } while (false);

                borrowed.close();
            }
            """
        )
    }

    fun testTry() {
        check(
            """
            void use(boolean condition) {
                try {
                    borrowed = new Res();
                } catch (RuntimeException exception) {
                    borrowed = condition ? getRes() : new Res();
                }

                borrowed.<error>close</error>();
            }

            void useFinally() {
                try {
                    borrowed = getRes();
                } finally {
                    borrowed = new Res();
                }

                borrowed.close();
            }
            """
        )
    }

    fun testExits() {
        check(
            """
            void use(boolean condition) {
                if (condition) {
                    return;
                } else {
                    borrowed = new Res();
                }

                borrowed.close();
            }

            void useThrow(boolean condition) {
                if (condition) {
                    throw new IllegalStateException();
                } else {
                    borrowed = new Res();
                }
              
                borrowed.close();
            }
            """
        )
    }

    fun testConditional() {
        check(
            """
            void use(boolean condition) {
                Res first = condition ? getRes() : new Res();
                first.<error>close</error>();

                Res second = condition ? new Res() : new Res();
                second.close();

                Res third = condition ? getRes() : getRes();
                third.<error>close</error>();
            }
            """
        )
    }

    fun testSwitch() {
        check(
            """
            void use(int value) {
                Res first = switch (value) {
                    case 0 -> getRes();
                    default -> new Res();
                };
                first.<error>close</error>();

                Res second = switch (value) {
                    case 0 -> new Res();
                    default -> new Res();
                };
                second.close();
            }
            """
        )
    }

    fun testLambda() {
        check(
            """
            void use() {
                Runnable first = () -> {
                    borrowed.<error>close</error>();

                    borrowed = new Res();
                    borrowed.close();
                };

                Res value = getRes();
                Runnable second = () -> value.<error>close</error>();

                first.run();
                second.run();
            }
            """
        )
    }

    fun testResources() {
        check(
            """
            void use() {
                try (Res <error>first</error> = getRes()) {
                }

                try (Res second = new Res()) {
                }

                Res third = getRes();
                try (<error>third</error>) {
                }
            }
            """
        )
    }

    fun testCasts() {
        check(
            """
            void use() {
                ((Res) getRes()).<error>close</error>();

                Res value = (getRes());
                value.<error>close</error>();
            }
            """
        )
    }

    fun testStatic() {
        check(
            """
            void use() {
                staticBorrowed.<error>close</error>();
                Test.staticBorrowed.<error>close</error>();

                Test.staticBorrowed = new Res();
                staticBorrowed.close();
            }
            """
        )
    }

    fun testUnknown() {
        check(
            """
            void use(Res parameter) {
                parameter.close();
                createRes().close();
            }
            """
        )
    }
}
