package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一条游戏内用例,挂在 {@code public static void m(GameTestHelper)} 上。形状同旧代的 {@code @GameTest}:
 * 用例方法的写法一字不改,由 {@link NumenGameTests} 登记成 {@code minecraft:test_instance} 条目。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenTest {

    /** 结构模板名(对应模板目录里的 {@code <template>.snbt})。 */
    String template();

    /** 超时游戏刻。 */
    int timeoutTicks();

    /** 批次名:同名批次的用例分在同一批里跑,批次开跑前先做 {@link NumenBeforeBatch} 登记的准备。 */
    String batch();
}
