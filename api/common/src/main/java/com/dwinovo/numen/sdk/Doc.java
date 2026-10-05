package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 一句说明:写在参数或返回值 record 的组件上是那个参数、那个字段的说明,写在 record 上是这个类的说明。帮助与签名里原样列出。 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.RECORD_COMPONENT, ElementType.TYPE})
public @interface Doc {
    String value();
}
