package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一个例子:一段真实可用的程序,调到这个函数,帮助里按写的顺序原样列出。可以写多个。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Repeatable(Example.All.class)
public @interface Example {
    String value();

    /** 写了多个时的容器。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface All {
        Example[] value();
    }
}
