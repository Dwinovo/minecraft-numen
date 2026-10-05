package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 登记时现算出一组用例:挂在 {@code static Collection<TestCase> m()} 上,所在的类要有 {@link NumenTestHolder}。
 * 用例的个数与内容要看运行参数时用它(评测按选中的场景给用例),写死的用例用 {@link NumenTest}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NumenTestGenerator {
}
