package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写在返回值 record 的一个组件上:脚本照常读得到这个字段,{@code pairs} 数不到,印出来与回执里也不带——大而少用的字段(一条路的
 * 每一步)不把回执撑满。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Folded {
}
