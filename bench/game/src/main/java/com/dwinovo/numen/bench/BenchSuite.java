package com.dwinovo.numen.bench;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标在评测源码集里的一个 {@code public static Optional<Bench.Run>} 无参方法上:它返回 {@link Bench#suite} 登记的那一组。
 * {@link NumenBench} 在游戏登记用例时扫出所有标了它的方法,各调一次。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface BenchSuite {
}
