package com.dwinovo.numen.core.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一条游戏内用例的声明。等价于旧代的 {@code @GameTest}——1.21.5 把注解驱动的
 * gametest 整套删掉了(测试改成 {@code minecraft:test_instance} 注册表里的数据条目),
 * 所以自带一个同形状的注解,用例方法的写法一字不改,由
 * {@link NumenGameTests} 反射扫描后注册成 1.21.5 的测试实例。
 *
 * <p>不改成"一张 47 行的登记表"是有意的:表和方法分居两处必然漂移,而注解和
 * 方法体永远同生共死;同时也让本文件与其余版本分支的用例代码保持逐行可比。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenTest {

    /** 结构模板名(对应 {@code gameteststructures/<template>.snbt})。 */
    String template();

    /** 超时游戏刻,对应 1.21.5 的 {@code TestData.maxTicks}。 */
    int timeoutTicks();

    /** 批次名。1.21.5 的批次由"测试环境"代言,同名批次共用一个环境条目。 */
    String batch();
}
