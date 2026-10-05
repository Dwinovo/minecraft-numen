package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 做完这件事下一步通常用的函数(或模块函数),写全名:{@code @SeeAlso({"numen.scan.entities", "numen.work.collect"})}。 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SeeAlso {
    String[] value();
}
