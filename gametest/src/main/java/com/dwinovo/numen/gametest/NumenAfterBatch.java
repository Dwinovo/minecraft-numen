package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个批次收场后做的善后,挂在 {@code public static void m(ServerLevel)} 上。形状同旧代的 {@code @AfterBatch}:
 * 对应测试环境的 {@code teardown}(批次跑完、换下一个环境之前调用)。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenAfterBatch {

    /** 给哪个批次善后(同 {@link NumenTest#batch()})。 */
    String batch();
}
