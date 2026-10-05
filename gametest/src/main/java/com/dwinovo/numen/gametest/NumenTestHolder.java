package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标出一个装着游戏内用例的类,{@link #value} 是用例的命名空间:登记出来的用例叫 {@code <命名空间>:<类名>.<方法名>},
 * 模板也取这个命名空间。运行配置经 {@code neoforge.enabledGameTestNamespaces} 只开哪些命名空间,没开的类一个都不加载。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NumenTestHolder {

    String value();
}
