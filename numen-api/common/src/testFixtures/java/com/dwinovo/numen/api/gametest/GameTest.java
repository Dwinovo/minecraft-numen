package com.dwinovo.numen.api.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一条用例:挂在套件类里的 {@code public static void m(GameTestHelper)} 上。原版 1.21.5 起删了注解入口,用例变成测试实例注册表里的
 * 条目;这里照原来的形状留一个注解,{@link GameTestSuites} 把它读成 {@link TestFunction},再由 {@link GameTestRegistration} 登记成
 * 测试实例。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTest {

    /** 结构模板名:带冒号的原样用,不带的前面接套件名;不写就是 {@code 小写类名.小写方法名}。 */
    String template() default "";

    /** 时限(刻)。 */
    int timeoutTicks() default 100;

    /** 批次名:同名的用例同一批跑,批前批后做 {@link BeforeBatch}、{@link AfterBatch} 登记的事。 */
    String batch() default "defaultBatch";

    /** 场地摆好之后、用例开始之前等几刻。 */
    int setupTicks() default 0;

    /** 失败是否算整套失败。 */
    boolean required() default true;

    /** 场地顶上是否露天(不露天就用屏障封顶)。 */
    boolean skyAccess() default false;

    /** 场地转几个 90 度。 */
    int rotationSteps() default 0;
}
