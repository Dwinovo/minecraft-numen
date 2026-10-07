package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写在返回值 record 上:这个类的值带方法,方法写在这个 Lua 模块里与类同名(类名的最后一段)的表上({@code @Methods("numen.scan")}
 * 的 Cluster 用 {@code function M.Cluster:filter(keep)}),值交给脚本时挂上它当元表。帮助里类的方法从模块的注释读出来。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Methods {
    String value();
}
