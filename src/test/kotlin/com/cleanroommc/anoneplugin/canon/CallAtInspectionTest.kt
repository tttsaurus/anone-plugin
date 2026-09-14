package com.cleanroommc.anoneplugin.canon

import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class CallAtInspectionTest : LightJavaCodeInsightFixtureTestCase() {

    private fun addAnnotations() {
        myFixture.addClass(
            """
            package com.cleanroommc.anone.canon;

            public enum CallPosition {
                HEAD,
                IN_FINALLY,
                OUTERMOST_FINALLY,
                ANYWHERE
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package com.cleanroommc.anone.canon;

            public enum CallScope {
                DIRECT_OVERRIDERS,
                TRANSITIVE_OVERRIDERS
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package com.cleanroommc.anone.canon;

            public final class Super {

                private Super() {}
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package com.cleanroommc.anone.canon;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.CLASS)
            public @interface MustCallAt {

                CallPosition position() default CallPosition.HEAD;

                CallScope scope() default CallScope.TRANSITIVE_OVERRIDERS;

                Class<?>[] scopeRoot() default { Super.class };
            }
            """.trimIndent()
        )

        myFixture.addClass(
            """
            package com.cleanroommc.anone.canon;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Target(ElementType.METHOD)
            @Retention(RetentionPolicy.CLASS)
            public @interface MustNotCallAt {

                CallPosition position() default CallPosition.HEAD;

                CallScope scope() default CallScope.TRANSITIVE_OVERRIDERS;

                Class<?>[] scopeRoot() default { Super.class };
            }
            """.trimIndent()
        )
    }

    private fun check(body: String) {
        myFixture.configureByText(
            "Test.java",
            """
            package test;

            import com.cleanroommc.anone.canon.*;

            ${body.trimIndent()}
            """.trimIndent()
        )

        myFixture.checkHighlighting()
    }

    override fun setUp() {
        super.setUp()

        addAnnotations()

        myFixture.enableInspections(
            MustCallAtInspection(),
            MustNotCallAtInspection()
        )
    }

    override fun getProjectDescriptor(): LightProjectDescriptor {
        return JAVA_17
    }

    fun testSuper() {
        check(
            """
            class RequiredBase {

                @MustCallAt
                void run() {}
            }

            class RequiredGood extends RequiredBase {

                @Override
                void run() {
                    super.run();
                    work();
                }

                void work() {}
            }

            class RequiredBad extends RequiredBase {

                @Override
                void <error>run</error>() {
                    work();
                }

                void work() {}
            }

            class ForbiddenBase {

                @MustNotCallAt
                void run() {}
            }

            class ForbiddenBad extends ForbiddenBase {

                @Override
                void run() {
                    super.<error>run</error>();
                }
            }

            class ForbiddenGood extends ForbiddenBase {

                @Override
                void run() {
                    work();
                    super.run();
                }

                void work() {}
            }
            """
        )
    }

    fun testScopeRoot() {
        check(
            """
            class Calls {

                @MustCallAt(scopeRoot = { Base.class })
                static void check(Base value) {}

                @MustNotCallAt(
                    position = CallPosition.ANYWHERE,
                    scopeRoot = { Base.class }
                )
                static void internal() {}
            }

            abstract class Base {

                abstract void first();

                abstract void second();
            }

            class Good extends Base {

                @Override
                void first() {
                    Calls.check(this);
                }

                @Override
                void second() {
                    Calls.check(this);
                }
            }

            class Bad extends Base {

                @Override
                void <error>first</error>() {}

                @Override
                void second() {
                    Calls.check(this);
                    Calls.<error>internal</error>();
                }
            }
            """
        )
    }

    fun testScopeRootUnion() {
        check(
            """
            class UnionCalls {

                @MustCallAt(scopeRoot = { First.class, Second.class })
                static void required() {}

                @MustNotCallAt(
                    position = CallPosition.ANYWHERE,
                    scopeRoot = { First.class, Second.class }
                )
                static void forbidden() {}
            }

            class First {

                void first() {}
            }

            class Second {

                void second() {}
            }

            class Unrelated {

                void other() {}
            }

            class FirstImpl extends First {

                @Override
                void <error>first</error>() {}
            }

            class SecondImpl extends Second {

                @Override
                void second() {
                    UnionCalls.required();
                    UnionCalls.<error>forbidden</error>();
                }
            }

            class UnrelatedImpl extends Unrelated {

                @Override
                void other() {
                    UnionCalls.forbidden();
                }
            }
            """
        )
    }

    fun testScope() {
        check(
            """
            class DirectCalls {

                @MustCallAt(
                    scope = CallScope.DIRECT_OVERRIDERS,
                    scopeRoot = { DirectBase.class }
                )
                static void required() {}

                @MustNotCallAt(
                    position = CallPosition.ANYWHERE,
                    scope = CallScope.DIRECT_OVERRIDERS,
                    scopeRoot = { DirectBase.class }
                )
                static void forbidden() {}
            }

            class DirectBase {

                void run() {}
            }

            class DirectMiddle extends DirectBase {

                @Override
                void run() {
                    DirectCalls.required();
                    DirectCalls.<error>forbidden</error>();
                }
            }

            class DirectLeaf extends DirectMiddle {

                @Override
                void run() {
                    DirectCalls.forbidden();
                }
            }

            class TransitiveCalls {

                @MustCallAt(scopeRoot = { TransitiveBase.class })
                static void required() {}

                @MustNotCallAt(
                    position = CallPosition.ANYWHERE,
                    scopeRoot = { TransitiveBase.class }
                )
                static void forbidden() {}
            }

            class TransitiveBase {

                void run() {}
            }

            class TransitiveMiddle extends TransitiveBase {

                @Override
                void run() {
                    TransitiveCalls.required();
                }
            }

            class TransitiveLeaf extends TransitiveMiddle {

                @Override
                void <error>run</error>() {
                    TransitiveCalls.<error>forbidden</error>();
                }
            }
            """
        )
    }
}
