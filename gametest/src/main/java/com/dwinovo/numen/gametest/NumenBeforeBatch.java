package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个批次开跑前做的准备,挂在 {@code public static void m(ServerLevel)} 上。形状同旧代的 {@code @BeforeBatch}:
 * 1.21.5 起批次由"测试环境"代言,环境的 {@code setup} 在每个批次开跑前调用一次,于是这里把准备方法挂到同名批次的环境上。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenBeforeBatch {

    /** 给哪个批次做准备(同 {@link NumenTest#batch()})。 */
    String batch();
}
