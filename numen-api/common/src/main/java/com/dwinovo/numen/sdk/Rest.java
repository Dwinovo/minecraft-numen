package com.dwinovo.numen.sdk;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 写在最后一个位置参数(一个 {@code List})上:它收下余下的全部对象——{@code f(a, b, c)} 与 {@code f({a, b, c})} 一样,
 * {@code f(a)} 是只有一项的一串。签名里写成 {@code T|T[]}。
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Rest {
}
