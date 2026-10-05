package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 登记时现算出一批用例的方法:{@code public static Collection<NumenTestCase> m()}。形状同旧代的 {@code @GameTestGenerator},
 * 给"用例有几条要看运行参数"的场合用(真模型评测按 {@code -Dbench.scenarios} 选场景)。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenGameTestGenerator {
}
