package com.dwinovo.numen.gametest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 一个装着用例的类。{@link NumenGameTests} 在登记时从模组的扫描数据里找到所有挂着它的类,再读类上的
 * {@link NumenTest}、{@link NumenBeforeBatch}、{@link NumenGameTestGenerator}。
 *
 * <p>旧代(1.21.4 及以前)NeoForge 的 {@code @GameTestHolder} 做的就是这件事;1.21.5 起它没了,这里自带一个同形状的。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface NumenGameTestHolder {

    /** 命名空间:用例的编号 {@code <命名空间>:<类名>.<方法名>} 与运行配置的 {@code neoforge.enabledGameTestNamespaces} 都认它。 */
    String value();
}
