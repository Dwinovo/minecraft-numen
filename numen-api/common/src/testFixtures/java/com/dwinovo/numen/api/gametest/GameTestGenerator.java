package com.dwinovo.numen.api.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 登记时现算出一批用例的方法:{@code public static Collection<TestFunction> m()},用例有几条要看运行参数时用(评测按选中的场景出题)。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GameTestGenerator {
}
